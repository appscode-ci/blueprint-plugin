package com.appscode.ci.plugins.blueprint;

import com.appscode.ci.model.blueprint.Job;
import com.appscode.ci.model.blueprint.Job.Docker.Volume;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.remoting.Callable;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.security.MasterToSlaveCallable;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.lang.StringUtils.isEmpty;

/**
 * Decorate Launcher so that every command executed by a build step is actually ran inside docker container.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerBuildWrapper extends BuildWrapper {

    private transient Job blueprint;

    @DataBoundConstructor
    public DockerBuildWrapper() {
    }

    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
        final BuiltInContainer runInContainer = new BuiltInContainer();
        build.addAction(runInContainer);

        DockerLauncher decorated = new DockerLauncher(launcher, runInContainer, build);
        return decorated;
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        // setUp is executed after checkout, so hook here to prepare and run Docker image to host the build
        blueprint = Blueprints.loadJob(build);
        if (blueprint.getDocker() == null) {
            throw new NullPointerException("Job blueprint is not configured run inside Docker.");
        }
        if (Util.fixEmpty(blueprint.getDocker().getImage()) == null
            && Util.fixEmpty(blueprint.getDocker().getImageDockerfile()) == null) {
            throw new NullPointerException("Job blueprint does not specify image or imageDockerFile.");
        }

        if (Util.fixEmpty(blueprint.getDocker().getCommand()) == null) {
            blueprint.getDocker().setCommand("/bin/cat");
        }

        BuiltInContainer runInContainer = build.getAction(BuiltInContainer.class);
        runInContainer.setUserId(whoAmI(launcher));
        runInContainer.setDocker(new Docker(build, launcher, listener, blueprint.getDocker().isVerbose(), blueprint.getDocker().isPrivileged()));

        // mount slave root in Docker container so build process can access project workspace, tools, as well as jars copied by maven plugin.
        final String root = Computer.currentComputer().getNode().getRootPath().getRemote();
        runInContainer.bindMount(root);

        // mount tmpdir so we can access temporary file created to run shell build steps (and few others)
        String tmp = build.getWorkspace().act(GetTmpdir);
        runInContainer.bindMount(tmp);

        // Mount directories so installed tools are available inside container
        Set<Job.Docker.Volume> volumes = new HashSet<Job.Docker.Volume>();
        if (blueprint.getDocker().getVolumes() != null) {
            volumes.addAll(blueprint.getDocker().getVolumes());
        }

        String buildDataPath = build.getWorkspace().act(new BuildDataDirCreator(build.getUrl()));
        volumes.add(new Volume(buildDataPath,                  "/mnt/build-data"));
        volumes.add(new Volume("/var/lib/jenkins/.ssh",        "/root/.ssh"));
        volumes.add(new Volume("/var/lib/jenkins/.m2",         "/root/.m2"));
        volumes.add(new Volume("/var/lib/jenkins/.appscode",   "/root/.appscode"));
        volumes.add(new Volume("/var/lib/jenkins/.gitconfig",  "/root/.gitconfig"));
        volumes.add(new Volume("/var/lib/jenkins/.kube",       "/root/.kube"));
        volumes.add(new Volume("/usr/local/bin/kubectl",       "/usr/local/bin/kubectl"));
        volumes.add(new Volume("/usr/local/bin/appctl",        "/usr/local/bin/appctl"));

        for (Volume volume : volumes) {
            runInContainer.bindMount(volume.getHostPath(), volume.getPath());
        }

        if (runInContainer.container == null) {
            if (runInContainer.image == null) {
                try {
                    runInContainer.image = Blueprints.prepareDockerImage(blueprint, runInContainer.getDocker(), build, listener);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted");
                }
            }

            runInContainer.container = startBuildContainer(runInContainer, build, listener);
            listener.getLogger().println("Docker container " + runInContainer.container + " started to host the build");
        }

        // We are all set, DockerDecoratedLauncher now can wrap launcher commands with docker-exec
        runInContainer.enable();

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                return build.getAction(BuiltInContainer.class).tearDown();
            }
        };
    }

    private String startBuildContainer(BuiltInContainer runInContainer, AbstractBuild build, BuildListener listener) throws IOException {
        try {
            EnvVars environment = buildContainerEnvironment(build, listener);

            String workdir = build.getWorkspace().getRemote();

            Map<String, String> links = new HashMap<String, String>();

            String[] command = blueprint.getDocker().getCommand().length() > 0 ? blueprint.getDocker().getCommand().split(" ") : new String[0];

            return runInContainer.getDocker().runDetached(runInContainer.image, workdir,
                    runInContainer.getVolumes(build), runInContainer.getPortsMap(), links,
                    environment, build.getSensitiveBuildVariables(), blueprint.getDocker().getNet(), blueprint.getDocker().getMemory(), blueprint.getDocker().getCpu(),
                    command); // Command expected to hung until killed

        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted");
        }
    }

    /**
     * Create the container environment.
     * We can't just pass result of {@link AbstractBuild#getEnvironment(TaskListener)}, as this one do include slave host
     * environment, that may not make any sense inside container (consider <code>PATH</code> for sample).
     */
    private EnvVars buildContainerEnvironment(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
        EnvVars env = build.getEnvironment(listener);
        env.remove("PATH");

        for (String key : Computer.currentComputer().getEnvironment().keySet()) {
            env.remove(key);
        }

        LOGGER.log(Level.FINE, "reduced environment: {0}", env);
        EnvVars.resolve(env);
        return env;
    }

    private String whoAmI(Launcher launcher) throws IOException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        launcher.launch().cmds("id", "-u").stdout(bos).quiet(true).join();
        String uid = bos.toString().trim();

        String gid = blueprint.getDocker().getGroup();
        if (isEmpty(blueprint.getDocker().getGroup())) {
            ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
            launcher.launch().cmds("id", "-g").stdout(bos2).quiet(true).join();
            gid = bos2.toString().trim();
        }
        return uid + ":" + gid;
    }

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public String getDisplayName() {
            return "Build inside a Docker container";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }

    private static Callable<String, IOException> GetTmpdir = new MasterToSlaveCallable<String, IOException>() {
        @Override
        public String call() {
            return System.getProperty("java.io.tmpdir");
        }
    };

    private static final class BuildDataDirCreator extends MasterToSlaveCallable<String, IOException> {
        private String buildUrl;

        public BuildDataDirCreator(String buildUrl) {
            this.buildUrl = buildUrl;
        }

        @Override
        public String call() {
            // e.g.: buildUrl = job/jetty-demo/7/
            int index = buildUrl.indexOf("job/");
            if (index == -1) {
                return null;
            }
            buildUrl = buildUrl.substring(index + "job/".length());
            index = buildUrl.indexOf("/");
            String path = "/mnt/ci-data/" + (index == -1 ? "" : buildUrl.substring(index + 1)) + "build-data";
            boolean created = new File(path).mkdirs();
            System.out.println(created ? "Build data dir is created" : "WARNING!!! Build data dir failed to create.");
            return path;
        }
    };

    private static final Logger LOGGER = Logger.getLogger(DockerBuildWrapper.class.getName());
}
