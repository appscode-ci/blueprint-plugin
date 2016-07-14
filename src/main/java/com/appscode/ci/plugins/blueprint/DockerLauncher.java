package com.appscode.ci.plugins.blueprint;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.Environment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.apache.commons.lang.StringUtils.isEmpty;

/**
* @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
*/
public class DockerLauncher extends Launcher.DecoratedLauncher {

    private final BuiltInContainer runInContainer;
    private final AbstractBuild build;
    private EnvVars env;
    private final Launcher launcher;

    public DockerLauncher(Launcher launcher, BuiltInContainer runInContainer, AbstractBuild build)
            throws IOException, InterruptedException {
        super(launcher);
        this.launcher = launcher;
        this.runInContainer = runInContainer;
        this.build = build;
    }

    public Proc launch(String[] cmd, boolean[] mask, String[] env, InputStream in, OutputStream out, FilePath workDir) throws IOException {
        return launch(launch().cmds(cmd).masks(mask).envs(env).stdin(in).stdout(out).pwd(workDir));
    }

    @Override
    public Proc launch(ProcStarter starter) throws IOException {
        // Do not decorate launcher until SCM checkout completed
        if (!runInContainer.isEnabled()) return super.launch(starter);

        try {
            EnvVars environment = buildContainerEnvironment();
            runInContainer.getDocker().executeIn(runInContainer.container, runInContainer.getUserId(), starter, environment);
        } catch (InterruptedException e) {
            throw new IOException("Caught InterruptedException", e);
        }

        return super.launch(starter);
    }

    private EnvVars buildContainerEnvironment() throws IOException, InterruptedException {
        if (this.env == null) {
            this.env = runInContainer.getDocker().getEnv(runInContainer.container, launcher);
        }
        EnvVars environment = new EnvVars(env);

        // Let BuildWrapper customize environment, including PATH
        for (Environment e : build.getEnvironments()) {
            e.buildEnvVars(environment);
        }
        String originalPath = env.get("PATH", "");
        String currentPath = environment.get("PATH", "");
        if (!currentPath.equals(originalPath) && !originalPath.isEmpty()) {
            this.getListener().error("PATH can't be changed by build wrappers");
            environment.override("PATH", originalPath);
        }

        return environment;
    }
}
