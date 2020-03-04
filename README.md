# Jenkins Perfci Plugin

This plugin can launch a performance test with JMeter, and generate a test report after test finished. It also includes features such as comparing 2 test run report, showing trend report over multiple builds, and integrating with NMON monitoring to target servers.


## Configuration

### Global configuration

Before using perfci on a project, you can configure some global
settings.  Go to Jenkins System configuration page.  Manage Jenkins -\>
Configure System

The section titled PerfCI is where you can
configure global perfci properties.   The inputs labeled 	Default Jmeter command and  Default Perfcharts command, allow you to configure the default perfchats command and default jmetercommd , it has low priority than local variables.When you configure it correctly here, you don't need to specify these parameters in the pipeline script . The input labeled DefaulterfCI inclusion JMX files can be used to set default jmx files, it supports regular expression and this is a comma separated list of Jmx files (these three variables  can
be overridden at the project level). This can be used to greatly
simplify the configuration you need to do for all projects.

### Project configuration

For a project if you don't want to use the perfci plugin, you need to disable  it in the project configuration page.  Select the checkbox labeled "disable all" in the  "Build section".

#### Basic Configuration

There are four fields that you can edit when the plugin is enabled.

-   **Performance test tasks** -  This is a Drop-down box

which can add mutiple the Apache jmeter and clone from other project tasks and you should launch  jmeter command on docker way to avoid the command configure.
-   **Resource monitors**  -  This is a Drop-down box

which can add nmon monitor to monitor the machine performace  when you launch a test.you shounld input your ssh key and password to these label （suggest to use Fingerprint way to identify to avoid secret leak）.
-   **Report Generation** -  This part is use perfchart programm to generate report , you should configure Perfcharts command	use the docker way.
### Advanced configuration

To see the advanced configuration for the plugin, first click on
Override Global setting checkbox, then click the "Advanced" button. 
This section allows you to specify recipients for each type of email
trigger 

### Pipeline script example

Examples:

-   Simple build scripts

``` groovy
// this is the sample use 
pipeline {
    agent {
        node {
            label ''
        }
    }
    stages {
        stage('Build') {
            steps {         
                checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '']], submoduleCfg: [], userRemoteConfigs: [[name: '', url: '']]])
                perfTestBuilder performanceTesters:[Jmeter()],resourceMonitors:[Nmon(host: "", name: "", password: "")]
            }        
        }
    }
}
```
``` groovy
// This is sample way to use copybuild
pipeline {
    agent {
        node {
            label ''
        }
    }
    stages {
        stage('Build') {
            steps {                
               perfTestBuilder performanceTesters:[Clone(copyBuildID: xxx)]
            }        
        }
    }

```

-   Advanced build scripts

``` groovy
// This scripts is equal to the simple build scripts and you can overwrite any number of params,  all the params are default in programme if you don't overwrite them.
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {                
                checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '']], submoduleCfg: [], userRemoteConfigs: [[name: '', url: '']]])
                perfTestBuilder excludedTransactionPattern: "", fallbackTimezone: "UTC",keepBuilds: 5, 
                    reportTemplate: "perf-baseline", resultDir: "perf-output",
                    perfchartsCommand: "docker run --net=host --rm -v $WORKSPACE:/data:rw docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 perfcharts",
                performanceTesters: 
                    [Jmeter(disabled: false, jmeterArgs: "-Djmeter.save.saveservice.output_format=xml", 
                        jmeterCommand: "docker run --net=host --rm -v $WORKSPACE:/data:rw -w /data/$PERFCI_WORKING_DIR docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 jmeter", 
                        jmxExcludingPattern: "", jmxIncludingPattern: "*.jmx", noAutoJTL: false),
                    Clone(copyBuildID: xxx, disabled: false, includingPattern: "**/*")], 
                resourceMonitors:
                    [Nmon(host: "xxx", name: "xxx", password: "xxx",isDisabled: false, fingerprint: "")]
                
            }        
        }
    }
}
```

### Use script in openshift
   You can refer https://github.com/zxiong/jmeter-openshift 