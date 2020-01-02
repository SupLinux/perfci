package org.jenkinsci.plugins.perfci.model;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.perfci.common.ResultDirectoryRelocatable;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Created by vfreex on 11/27/15.
 */
public class CloneBuildTester extends PerformanceTester implements ResultDirectoryRelocatable {
    @Symbol("Clone")
    @Extension
    public static class DescriptorImpl extends PerformanceTester.PerformanceTesterDescriptor {
        @Override
        public String getDisplayName() {
            return "Clone result from other build";
        }
    }

    private boolean disabled;
    private String resultDirectory;
    private int copyBuildID;
    private String includingPattern;

    public CloneBuildTester(boolean disabled, int copyBuildID, String includingPattern) {
        this.disabled = disabled;
        this.copyBuildID = copyBuildID;
        this.includingPattern = includingPattern;
    }

    @DataBoundConstructor
    public CloneBuildTester(int copyBuildID) {
        this(false, copyBuildID, "**/*");

    }

    @Override
    public void run(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
        if (disabled) {
            listener.getLogger().printf("INFO: Won't copy build #" + (build.number - 1));
            return;
        }
        if (copyBuildID < 0) {
            throw new IOException("Could not found build #" + copyBuildID);
        }
        if (copyBuildID == 0) {
            if (build.number <= 1)
                throw new IOException("Could not copy last build. Because this is your first build.");
            listener.getLogger().printf("INFO: Will copy build #" + (build.number - 1));
        }
        Job<?, ?> project = build.getParent();
        Run<?, ?> thatBuild = copyBuildID > 0 ? project.getBuildByNumber(copyBuildID) : project.getBuildByNumber(build.number - 1);
        FilePath thisBuildDir = workspace.child(resultDirectory).child("builds").child(Integer.toString(build.number)).child("rawdata");
        //FilePath thatBuildDir = thatBuild.getWorkspace().child(resultDirectory).child("builds").child(Integer.toString(thatBuild.number)).child("rawdata");
        FilePath thatBuildDir = workspace.child(resultDirectory).child("builds").child(Integer.toString(thatBuild.number)).child("rawdata");
        listener.getLogger().printf("INFO: Zipping and Copying files build #%d to build #%d...\n", thatBuild.number, build.number);
        String zipFileName = "tmp-" + UUID.randomUUID().toString() + ".zip";
        FilePath zipFilePath = thatBuildDir.getParent().child(zipFileName);
        thatBuildDir.zip(zipFilePath.write(), includingPattern);
        zipFilePath.unzip(thisBuildDir);
        zipFilePath.delete();
        listener.getLogger().printf("INFO: Zipping and copying files build #%d to build #%d... done", thatBuild.number, build.number);
    }

    @Override
    public String getResultDirectory() {
        return resultDirectory;
    }

    @Override
    public void setResultDirectory(String resultDirectory) {
        this.resultDirectory = resultDirectory;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public int getCopyBuildID() {
        return copyBuildID;
    }

    public void setCopyBuildID(int copyBuildID) {
        this.copyBuildID = copyBuildID;
    }

    public String getIncludingPattern() {
        return includingPattern;
    }
}
