package org.jenkinsci.plugins.perfci.common;

import java.io.File;
import java.util.StringTokenizer;

/**
 * Created by vfreex on 11/26/15.
 */
public class Constants {
    public final static String PERF_CHARTS_RELATIVE_PATH = "perfcharts";
    public final static String INPUT_DIR_RELATIVE_PATH = PERF_CHARTS_RELATIVE_PATH
            + File.separator + "rawdata";
    public final static String OUTPUT_DIR_RELATIVE_PATH = PERF_CHARTS_RELATIVE_PATH
            + File.separator + "report";
    public final static String CMP_DIR_RELATIVE_PATH = PERF_CHARTS_RELATIVE_PATH
            + File.separator + "comparison";
    public final static String MONO_REPORT_NAME = "mono_report.html";
    public final static String TREND_DIR_RELATIVE_PATH = PERF_CHARTS_RELATIVE_PATH
            + File.separator + "trend";
    public final static String TREND_INPUT_DEFAULT_FILENAME = "trend_input.txt";
    public final static String TREND_INPUT_RELATIVE_PATH = TREND_DIR_RELATIVE_PATH
            + File.separator + "trend_input.txt";
    public final static String TREND_MONO_REPORT_NAME = "trend_report.html";
    public final static String JMETERCOMMAND = "docker run --net=host --rm -v $WORKSPACE:/data:rw -w /data/$PERFCI_WORKING_DIR docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 jmeter";
    public final static String PERFCHARTSCOMMAND = "docker run --net=host --rm -v $WORKSPACE:/data:rw docker-registry.upshift.redhat.com/errata-qe-test/perfci-agent:3.2 perfcharts";
    public final static String JMETERARGS = "-Djmeter.save.saveservice.output_format=xml";

}
