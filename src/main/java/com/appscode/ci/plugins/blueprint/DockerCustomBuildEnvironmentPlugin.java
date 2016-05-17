package com.appscode.ci.plugins.blueprint;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.Items;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class DockerCustomBuildEnvironmentPlugin extends Plugin {

    static {
        Items.XSTREAM2.aliasPackage("com.cloudbees.jenkins.plugins.okidocki", "com.appscode.ci.plugins.blueprint");
    }
}
