package org.jenkinsci.plugins.perfci.builder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.perfci.action.PerfchartsBuildReportAction;
import org.jenkinsci.plugins.perfci.action.PerfchartsTrendReportAction;
import org.jenkinsci.plugins.perfci.common.*;
import org.jenkinsci.plugins.perfci.common.TaskQueue;
import org.jenkinsci.plugins.perfci.executor.PerfchartsNewExecutor;
import org.jenkinsci.plugins.perfci.model.PerformanceTester;
import org.jenkinsci.plugins.perfci.model.ResourceMonitor;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Created by vfreex on 11/23/15.
 */
public class PerformanceTestBuilder extends Builder implements SimpleBuildStep,Serializable {
    private boolean disabled = false;
    private String resultDir = "perf-out";
    private int keepBuilds = 5;
    private boolean reportDisabled = false;
    private String fallbackTimezone = "UTC";
    private List<PerformanceTester> performanceTesters = Collections.<PerformanceTester>emptyList();
    private List<ResourceMonitor> resourceMonitors = Collections.<ResourceMonitor>emptyList();
    private String perfchartsCommand = "docker run --net=host --rm -v /var/lib/jenkins/workspace/P2:/data:rw docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 perfcharts";
    private String excludedTransactionPattern = "";
    private String reportTemplate = "perf-baseline";


    public PerformanceTestBuilder(boolean disabled, String resultDir, int keepBuilds, boolean reportDisabled, String fallbackTimezone, List<PerformanceTester> performanceTesters, List<ResourceMonitor> resourceMonitors, String perfchartsCommand, String excludedTransactionPattern, String reportTemplate) {
        this.disabled = disabled;
        this.resultDir = resultDir;
        this.keepBuilds = keepBuilds;
        this.reportDisabled = reportDisabled;
        this.fallbackTimezone = fallbackTimezone;
        this.perfchartsCommand = perfchartsCommand;
        this.excludedTransactionPattern = excludedTransactionPattern;
        this.reportTemplate = reportTemplate;
        this.performanceTesters = performanceTesters != null ? performanceTesters : Collections.<PerformanceTester>emptyList();
        this.resourceMonitors = resourceMonitors != null ? resourceMonitors : Collections.<ResourceMonitor>emptyList();
    }

    @DataBoundConstructor
    public PerformanceTestBuilder(List<PerformanceTester> performanceTesters){
        this(false,"perf-out",5,false,"UTC",performanceTesters,Collections.<ResourceMonitor>emptyList(),"docker run --net=host --rm -v $WORKSPACE:/data:rw docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 perfcharts","","perf-baseline");
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public void perform(final  @Nonnull Run<?, ?> build, final @Nonnull FilePath workspace, final @Nonnull Launcher launcher, final @Nonnull TaskListener listener)
            throws InterruptedException, IOException {
        if (disabled) {
            listener.getLogger().println("[WARNING] Ignore PerformanceTestBuilder");
            return;
        }
        final TimeZone fallbackTimezoneObj = TimeZone.getTimeZone(fallbackTimezone);
        // `buildDir` here is the directory where we put all test results and logs for this build. It is a relative path to Jenkins workspace.
        final String resultDir = this.resultDir == null ? "" : this.resultDir;
        final String buildDir = resultDir + File.separator + "builds" + File.separator + build.number;
        final String baseDirForBuild = buildDir + File.separator + "rawdata";
        final String logDirForBuild = buildDir + File.separator + "log";
        final String reportDirForBuild = buildDir + File.separator + "report";
        EnvVars env = build.getEnvironment(listener);
        final String perfchartsCommand = env.expand(this.perfchartsCommand);

        // start resource monitors
        TaskQueue startMonitorTaskQueue = new TaskQueue();
        for (final ResourceMonitor resourceMonitor : resourceMonitors) {
            startMonitorTaskQueue.enqueue(new Runnable() {
                @Override
                public void run() {
                    if (resourceMonitor instanceof BaseDirectoryRelocatable) {
                        ((BaseDirectoryRelocatable) resourceMonitor).setBaseDirectory(baseDirForBuild);
                    }
                    if (resourceMonitor instanceof ResultDirectoryRelocatable) {
                        ((ResultDirectoryRelocatable) resourceMonitor).setResultDirectory(resultDir);
                    }
                    try {
                        resourceMonitor.start(build, workspace,launcher, listener);
                    } catch (Exception ex) {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, ex);
                    }
                }
            });
        }
        listener.getLogger().println("INFO: Wait at most 10 minutes for resource monitors to start");
        startMonitorTaskQueue.runAll(1000 * 60 * 10);
        // start performance test executors
        for (PerformanceTester performanceTester : performanceTesters) {
            if (performanceTester instanceof LogDirectoryRelocatable) {
                ((LogDirectoryRelocatable) performanceTester).setLogDirectory(logDirForBuild);
            }
            if (performanceTester instanceof BaseDirectoryRelocatable) {
                ((BaseDirectoryRelocatable) performanceTester).setBaseDirectory(baseDirForBuild);
            }
            if (performanceTester instanceof ResultDirectoryRelocatable) {
                ((ResultDirectoryRelocatable) performanceTester).setResultDirectory(resultDir);
            }
            performanceTester.run(build, workspace, launcher, listener);
        }

        // stop resource monitors and collect test results
        TaskQueue stopMonitorTaskQueue = new TaskQueue();
        for (final ResourceMonitor resourceMonitor : resourceMonitors) {
            stopMonitorTaskQueue.enqueue(new Runnable() {
                @Override
                public void run() {
                    if (resourceMonitor instanceof BaseDirectoryRelocatable) {
                        ((BaseDirectoryRelocatable) resourceMonitor).setBaseDirectory(baseDirForBuild);
                    }
                    try {
                        resourceMonitor.stop(build, workspace,launcher, listener);
                    } catch (Exception ex) {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, ex);
                    }
                }
            });
        }
        listener.getLogger().println("INFO: Waiting at most 6 hours for resource monitors to stop and complete data transfer...");
        stopMonitorTaskQueue.runAll(1000 * 60 * 60 * 6);

        if (reportDisabled) {
            listener.getLogger().println("WARNING: No performance test reports will be generated according to your configuration.");
        } else {
            final String workspaceFullPathOnAgent = workspace.getRemote();
            launcher.getChannel().call(new hudson.remoting.Callable<Object, IOException>() {
                @Override
                public void checkRoles(RoleChecker checker) throws SecurityException {
                }

                @Override
                public Object call() throws IOException {
                    // generate a report
                    PerfchartsNewExecutor perfchartsExecutor = new PerfchartsNewExecutor(perfchartsCommand,
                            reportTemplate, workspaceFullPathOnAgent,
                            fallbackTimezoneObj,
                            baseDirForBuild,
                            reportDirForBuild,
                            reportDirForBuild + File.separator + Constants.MONO_REPORT_NAME,
                            PerformanceTestBuilder.this.excludedTransactionPattern,
                            listener.getLogger());


                    try {
                        if (perfchartsExecutor.run() != 0) {
                            listener.getLogger().println("ERROR: Perfcharts reported an error when generating a performance report.");
                            throw new InterruptedException("Perfcharts reported an error when generating a performance report.");
                        }
                        listener.getLogger().println("INFO: Performance report generated successfully.");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return null;
                }
            });
            // copy generated report to master
            listener.getLogger().println("INFO: Copying generated performance report to Jenkins master...");
            IOHelper.copyDirFromWorkspace(workspace.child(reportDirForBuild), Constants.PERF_CHARTS_RELATIVE_PATH, build,workspace, listener);
        }

        listener.getLogger().println("INFO: Preparing views for generated performance report...");
        build.addAction(new PerfchartsBuildReportAction(build));

        listener.getLogger().println("INFO: Everything is done.");
        return;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
        List<Action> actions = new ArrayList<Action>();
        actions.add(new PerfchartsTrendReportAction(project));
        return actions;
    }

    public boolean isDisabled() {
        return disabled;
    }

    @DataBoundSetter
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public String getFallbackTimezone() {
        return fallbackTimezone;
    }

    @DataBoundSetter
    public void setFallbackTimezone(String fallbackTimezone) {
        this.fallbackTimezone = fallbackTimezone;
    }

    public boolean isReportDisabled() {
        return reportDisabled;
    }

    @DataBoundSetter
    public void setReportDisabled(boolean reportDisabled) {
        this.reportDisabled = reportDisabled;
    }

    public String getResultDir() {
        return resultDir;
    }

    @DataBoundSetter
    public void setResultDir(String resultDir) {
        this.resultDir = resultDir;
    }

    public List<PerformanceTester> getPerformanceTesters() {
        return performanceTesters;
    }

    @DataBoundSetter
    public void setPerformanceTesters(List<PerformanceTester> performanceTesters) {
        this.performanceTesters = performanceTesters;
    }

    public int getKeepBuilds() {
        return keepBuilds;
    }

    @DataBoundSetter
    public void setKeepBuilds(int keepBuilds) {
        this.keepBuilds = keepBuilds;
    }

    public List<ResourceMonitor> getResourceMonitors() {
        return resourceMonitors;
    }

    @DataBoundSetter
    public void setResourceMonitors(List<ResourceMonitor> resourceMonitors) {
        this.resourceMonitors = resourceMonitors;
    }

    public String getPerfchartsCommand() {
        return perfchartsCommand;
    }

    @DataBoundSetter
    public void setPerfchartsCommand(String perfchartsCommand) {
        this.perfchartsCommand = perfchartsCommand;
    }

    public String getExcludedTransactionPattern() {
        return excludedTransactionPattern;
    }

    @DataBoundSetter
    public void setExcludedTransactionPattern(String excludedTransactionPattern) {
        this.excludedTransactionPattern = excludedTransactionPattern;
    }

    public String getReportTemplate() {
        return reportTemplate;
    }

    @DataBoundSetter
    public void setReportTemplate(String reportTemplate) {
        this.reportTemplate = reportTemplate;
    }

    /**
     * Descriptor for {@link PerformanceTestBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Symbol({"performanceTestBuilder", "perfTestBuilder"})
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        private String defaultPerfchartsCommand = "docker run --net=host --rm -v $WORKSPACE:/data:rw docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 perfcharts";
        private String defaultJmeterCommand = "docker run --net=host --rm -v $WORKSPACE:/data:rw -w $PERFCI_WORKING_DIR docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 jmeter";
        private boolean defaultDisabled = false;
        private boolean defaultNoAutoJTL = false;
        private String defaultJmeterArgs = "-Djmeter.save.saveservice.output_format=xml";
        private String defaultJmxIncludingPattern = "*.jmx";
        private String defaultJmxExcludingPattern = "";
        private String nmonSSHKeys = "\"$HOME\"/.ssh/id_rsa,\"$HOME\"/.ssh/id_dsa";
        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }


        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Run performance test";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            defaultPerfchartsCommand = formData.getString("defaultPerfchartsCommand");
            defaultJmeterCommand = formData.getString("defaultJmeterCommand");
            defaultDisabled = formData.getBoolean("disabled");
            defaultNoAutoJTL = formData.getBoolean("noAutoJTL");
            defaultJmeterArgs = formData.getString("jmeterArgs");
            defaultJmxIncludingPattern = formData.getString("jmxIncludingPattern");
            defaultJmxExcludingPattern = formData.getString("jmxExcludingPattern");
            nmonSSHKeys = "\"$HOME\"/.ssh/id_rsa,\"$HOME\"/.ssh/id_dsa";
            save();
            return super.configure(req, formData);
        }

        public List<? extends PerformanceTester.PerformanceTesterDescriptor> getPerformanceTesterDescriptors() {
            return Jenkins
                    .getInstance()
                    .<PerformanceTester, PerformanceTester.PerformanceTesterDescriptor>getDescriptorList(
                            PerformanceTester.class);
        }

        public List<? extends ResourceMonitor.ResourceMonitorDescriptor> getResourceMonitorDescriptors() {
            return Jenkins
                    .getInstance()
                    .<ResourceMonitor, ResourceMonitor.ResourceMonitorDescriptor>getDescriptorList(
                            ResourceMonitor.class);
        }

        public ListBoxModel doFillReportTemplateItems() {
            return new ListBoxModel(new ListBoxModel.Option("Performance baseline test", "perf-baseline"),
                    new ListBoxModel.Option("General purpose performance test", "perf-general"));
        }

        public String getDefaultPerfchartsCommand() {
            return defaultPerfchartsCommand;
        }

        public void setDefaultPerfchartsCommand(String defaultPerfchartsCommand) {
            this.defaultPerfchartsCommand = defaultPerfchartsCommand;
        }

        public String getDefaultJmeterCommand() {
            return defaultJmeterCommand;
        }

        public void setDefaultJmeterCommand(String defaultJmeterCommand) {
            this.defaultJmeterCommand = defaultJmeterCommand;
        }

        public boolean isDefaultDisabled() {
            return defaultDisabled;
        }

        public void setDefaultDisabled(boolean defaultDisabled) {
            this.defaultDisabled = defaultDisabled;
        }

        public boolean isDefaultNoAutoJTL() {
            return defaultNoAutoJTL;
        }

        public void setDefaultNoAutoJTL(boolean defaultNoAutoJTL) {
            this.defaultNoAutoJTL = defaultNoAutoJTL;
        }

        public String getDefaultJmeterArgs() {
            return defaultJmeterArgs;
        }

        public void setDefaultJmeterArgs(String defaultJmeterArgs) {
            this.defaultJmeterArgs = defaultJmeterArgs;
        }

        public String getDefaultJmxIncludingPattern() {
            return defaultJmxIncludingPattern;
        }

        public void setDefaultJmxIncludingPattern(String defaultJmxIncludingPattern) {
            this.defaultJmxIncludingPattern = defaultJmxIncludingPattern;
        }

        public String getDefaultJmxExcludingPattern() {
            return defaultJmxExcludingPattern;
        }

        public void setDefaultJmxExcludingPattern(String defaultJmxExcludingPattern) {
            this.defaultJmxExcludingPattern = defaultJmxExcludingPattern;
        }

        public String getNmonSSHKeys() {
            return nmonSSHKeys;
        }

        public void setNmonSSHKeys(String nmonSSHKeys) {
            this.nmonSSHKeys = nmonSSHKeys;
        }
    }


}
