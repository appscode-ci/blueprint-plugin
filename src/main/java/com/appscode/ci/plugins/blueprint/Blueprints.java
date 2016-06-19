package com.appscode.ci.plugins.blueprint;

import com.appscode.ci.model.blueprint.Blueprint;
import com.appscode.ci.model.blueprint.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Node;
import hudson.model.TaskListener;

import java.io.IOException;

public final class Blueprints {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    private Blueprints(){}

    public static Blueprint load(AbstractBuild build)  throws IOException, InterruptedException {
        FilePath ws = build.getWorkspace();
        if (ws == null) {
            Node node = build.getBuiltOn();
            if (node == null) {
                throw new NullPointerException("no such build node: " + build.getBuiltOnStr());
            }
            throw new NullPointerException("no workspace from node " + node + " which is computer " + node.toComputer() + " and has channel " + node.getChannel());
        }
        if (!ws.child(".blueprint.yml").exists()) {
            throw new NullPointerException("no such .blueprint.yml");
        }

        String yamlString = ws.child(".blueprint.yml").readToString();
        return mapper.readValue(yamlString, Blueprint.class);
    }

    public static Job loadJob(AbstractBuild build)  throws IOException, InterruptedException {
        Blueprint bp = load(build);
        String jobName = build.getParent().getName();
        for (Job job: bp.getJobs()) {
            if (job.getName().equals(jobName)) {
                return job;
            }
        }
        throw new NullPointerException("no such job config: " + jobName + " in .blueprint.yml");
    }

    public static String prepareDockerImage(Job blueprint, Docker docker, AbstractBuild build, TaskListener listener) throws IOException, InterruptedException {
        if (Util.fixEmpty(blueprint.getDocker().getImage()) != null) {
            String expandedImage = build.getEnvironment(listener).expand(blueprint.getDocker().getImage());
            if (blueprint.getDocker().isForcePull() || !docker.hasImage(expandedImage)) {
                listener.getLogger().println("Pull Docker image " + expandedImage + " from repository ...");
                boolean pulled = docker.pullImage(expandedImage);
                if (!pulled) {
                    listener.getLogger().println("Failed to pull Docker image " + expandedImage);
                    throw new IOException("Failed to pull Docker image " + expandedImage);
                }
            }
            return expandedImage;
        } else {
            String contextPath = "";
            String dockerFilePath = "";
            String path = blueprint.getDocker().getImageDockerfile();
            if (path.startsWith("./")) {
                path = path.substring(2);
            }
            int index = path.lastIndexOf('/');
            if (index == -1) {
                dockerFilePath = path;
            } else {
                contextPath = path.substring(0, index);
                dockerFilePath = path.substring(index + 1);
            }

            String expandedContextPath = build.getEnvironment(listener).expand(contextPath);
            FilePath filePath = build.getWorkspace().child(expandedContextPath);

            FilePath dockerFile = filePath.child(dockerFilePath);
            if (!dockerFile.exists()) {
                listener.getLogger().println("Your project is missing a Dockerfile");
                throw new InterruptedException("Your project is missing a Dockerfile");
            }

            listener.getLogger().println("Build Docker image from " + expandedContextPath + "/" + dockerFilePath + " ...");
            return docker.buildImage(filePath, dockerFile.getRemote(), blueprint.getDocker().isForcePull());
        }
    }
}
