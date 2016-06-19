/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Kohsuke Kawaguchi, Jene Jasper, Yahoo! Inc., Seiji Sogabe, AppsCode Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.appscode.ci.plugins.blueprint.tasks;

import com.appscode.ci.plugins.blueprint.Blueprints;
import hudson.*;
import hudson.model.*;
import hudson.remoting.ChannelClosedException;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Messages;
import hudson.util.FormValidation;
import java.io.IOException;
import java.io.ObjectStreamException;
import hudson.util.LineEndingConversion;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes a script specified in blueprint.yml file by using a shell.
 * This imeplementaiton is based on {@link hudson.tasks.CommandInterpreter} and {@link hudson.tasks.Shell}.
 */
public class BlueprintShell extends Builder {

    @DataBoundConstructor
    public BlueprintShell() {
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
        return perform(build, launcher, (TaskListener) listener);
    }

    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) throws InterruptedException {
        FilePath ws = build.getWorkspace();
        if (ws == null) {
            Node node = build.getBuiltOn();
            if (node == null) {
                throw new NullPointerException("no such build node: " + build.getBuiltOnStr());
            }
            throw new NullPointerException("no workspace from node " + node + " which is computer " + node.toComputer() + " and has channel " + node.getChannel());
        }
        FilePath script = null;
        int r = -1;
        try {
            try {
                script = createScriptFile(build, ws);
            } catch (IOException e) {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_UnableToProduceScript()));
                return false;
            }

            try {
                EnvVars envVars = build.getEnvironment(listener);
                // on Windows environment variables are converted to all upper case,
                // but no such conversions are done on Unix, so to make this cross-platform,
                // convert variables to all upper cases.
                for (Map.Entry<String, String> e : build.getBuildVariables().entrySet())
                    envVars.put(e.getKey(), e.getValue());

                r = join(launcher.launch().cmds(buildCommandLine(script)).envs(envVars).stdout(listener).pwd(ws).start());
            } catch (IOException e) {
                Util.displayIOException(e, listener);
                e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_CommandFailed()));
            }
            return r == 0;
        } finally {
            try {
                if (script != null)
                    script.delete();
            } catch (IOException e) {
                if (r == -1 && e.getCause() instanceof ChannelClosedException) {
                    // JENKINS-5073
                    // r==-1 only when the execution of the command resulted in IOException,
                    // and we've already reported that error. A common error there is channel
                    // losing a connection, and in that case we don't want to confuse users
                    // by reporting the 2nd problem. Technically the 1st exception may not be
                    // a channel closed error, but that's rare enough, and JENKINS-5073 is common enough
                    // that this suppressing of the error would be justified
                    LOGGER.log(Level.FINE, "Script deletion failed", e);
                } else {
                    Util.displayIOException(e, listener);
                    e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_UnableToDelete(script)));
                }
            } catch (Exception e) {
                e.printStackTrace(listener.fatalError(Messages.CommandInterpreter_UnableToDelete(script)));
            }
        }
    }

    /**
     * Reports the exit code from the process.
     * <p>
     * This allows subtypes to treat the exit code differently (for example by treating non-zero exit code
     * as if it's zero, or to set the status to {@link Result#UNSTABLE}). Any non-zero exit code will cause
     * the build step to fail.
     *
     * @since 1.549
     */
    protected int join(Proc p) throws IOException, InterruptedException {
        return p.join();
    }

    /**
     * Older versions of bash have a bug where non-ASCII on the first line
     * makes the shell think the file is a binary file and not a script. Adding
     * a leading line feed works around this problem.
     */
    private static String addLineFeedForNonASCII(String s) {
        if (!s.startsWith("#!")) {
            if (s.indexOf('\n') != 0) {
                return "\n" + s;
            }
        }
        return s;
    }

    public String[] buildCommandLine(FilePath script) {
        return new String[]{getDescriptor().getShellOrDefault(script.getChannel()), "-xe", script.getRemote()};
    }

    /**
     * Creates a script file in a temporary name in the specified directory.
     */
    public FilePath createScriptFile(@Nonnull AbstractBuild<?, ?> build, FilePath ws) throws IOException, InterruptedException {
        return ws.createTextTempFile("hudson", getFileExtension(), getContents(build), false);
    }

    protected String getContents(AbstractBuild<?, ?> build) throws IOException, InterruptedException {
        return addLineFeedForNonASCII(LineEndingConversion.convertEOL(Blueprints.loadJob(build).getScript(), LineEndingConversion.EOLType.Unix));
    }

    protected String getFileExtension() {
        return ".sh";
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private Object readResolve() throws ObjectStreamException {
        return new BlueprintShell();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * Shell executable, or null to default.
         */
        private String shell;

        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getShell() {
            return shell;
        }

        /**
         * @deprecated 1.403
         * Use {@link #getShellOrDefault(hudson.remoting.VirtualChannel) }.
         */
        @Deprecated
        public String getShellOrDefault() {
            if (shell == null)
                return Functions.isWindows() ? "sh" : "/bin/sh";
            return shell;
        }

        public String getShellOrDefault(VirtualChannel channel) {
            if (shell != null)
                return shell;

            String interpreter = null;
            try {
                interpreter = channel.call(new Shellinterpreter());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, null, e);
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, null, e);
            }
            if (interpreter == null) {
                interpreter = getShellOrDefault();
            }

            return interpreter;
        }

        public void setShell(String shell) {
            this.shell = Util.fixEmptyAndTrim(shell);
            save();
        }

        public String getDisplayName() {
            return "Execute Shell from .blueprint.yml";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject data) throws FormException {
            req.bindJSON(this, data);
            return super.configure(req, data);
        }

        /**
         * Check the existence of sh in the given location.
         */
        public FormValidation doCheckShell(@QueryParameter String value) {
            // Executable requires admin permission
            return FormValidation.validateExecutable(value);
        }

        private static final class Shellinterpreter extends MasterToSlaveCallable<String, IOException> {

            private static final long serialVersionUID = 1L;

            public String call() throws IOException {
                return Functions.isWindows() ? "sh" : "/bin/sh";
            }
        }

    }

    private static final Logger LOGGER = Logger.getLogger(BlueprintShell.class.getName());
}
