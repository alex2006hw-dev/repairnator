package fr.inria.spirals.repairnator.pipeline;

import static fr.inria.spirals.repairnator.config.RepairnatorConfig.LISTENER_MODE;
import java.lang.reflect.Constructor;
import fr.inria.spirals.repairnator.Listener;
import ch.qos.logback.classic.Level;
import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.stringparsers.EnumeratedStringParser;
import com.martiansoftware.jsap.stringparsers.FileStringParser;
import fr.inria.jtravis.JTravis;
import fr.inria.jtravis.entities.Build;
import fr.inria.jtravis.entities.StateType;
import fr.inria.spirals.repairnator.BuildToBeInspected;
import fr.inria.spirals.repairnator.InputBuildId;
import fr.inria.spirals.repairnator.LauncherType;
import fr.inria.spirals.repairnator.LauncherUtils;
import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.notifier.AbstractNotifier;
import fr.inria.spirals.repairnator.notifier.BugAndFixerBuildsNotifier;
import fr.inria.spirals.repairnator.notifier.ErrorNotifier;
import fr.inria.spirals.repairnator.notifier.PatchNotifier;
import fr.inria.spirals.repairnator.notifier.PatchNotifierImpl;
import fr.inria.spirals.repairnator.notifier.engines.NotifierEngine;
import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector;
import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector4Bears;
import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector4Checkstyle;
import fr.inria.spirals.repairnator.process.step.repair.NPERepair;
import fr.inria.spirals.repairnator.serializer.AbstractDataSerializer;
import fr.inria.spirals.repairnator.serializer.HardwareInfoSerializer;
import fr.inria.spirals.repairnator.serializer.InspectorSerializer;
import fr.inria.spirals.repairnator.serializer.InspectorSerializer4Bears;
import fr.inria.spirals.repairnator.serializer.InspectorTimeSerializer;
import fr.inria.spirals.repairnator.serializer.PatchesSerializer;
import fr.inria.spirals.repairnator.serializer.PipelineErrorSerializer;
import fr.inria.spirals.repairnator.serializer.PropertiesSerializer;
import fr.inria.spirals.repairnator.serializer.ToolDiagnosticSerializer;
import fr.inria.spirals.repairnator.serializer.PullRequestSerializer;
import fr.inria.spirals.repairnator.serializer.engines.SerializerEngine;
import fr.inria.spirals.repairnator.states.LauncherMode;
import fr.inria.spirals.repairnator.states.ScannedBuildStatus;
import fr.inria.spirals.repairnator.utils.Utils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * This class is the main entry point for the repairnator pipeline.
 */
public class Launcher {
    private static Logger LOGGER = LoggerFactory.getLogger(Launcher.class);
    private static Listener listener = null;
    private BuildToBeInspected buildToBeInspected;
    private List<SerializerEngine> engines;
    private List<AbstractNotifier> notifiers;
    private PatchNotifier patchNotifier;
    private ProjectInspector inspector;

    private static RepairnatorConfig getConfig() {
        return RepairnatorConfig.getInstance();
    }
    
    public Launcher(String[] args) throws JSAPException {
        InputStream propertyStream = getClass().getResourceAsStream("/version.properties");
        Properties properties = new Properties();
        if (propertyStream != null) {
            try {
                properties.load(propertyStream);
            } catch (IOException e) {
                LOGGER.error("Error while loading property file.", e);
            }
            LOGGER.info("PIPELINE VERSION: "+properties.getProperty("PIPELINE_VERSION"));
        } else {
            LOGGER.info("No information about PIPELINE VERSION has been found.");
        }

        JSAP jsap = this.defineArgs();
        JSAPResult arguments = jsap.parse(args);
        LauncherUtils.checkArguments(jsap, arguments, LauncherType.PIPELINE);
        this.initConfig(arguments);

        if (this.getConfig().getLauncherMode() == LauncherMode.REPAIR) {
            this.checkToolsLoaded(jsap);
            this.checkNopolSolverPath(jsap);
            LOGGER.info("The pipeline will try to repair the following build id: "+this.getConfig().getBuildId());
        } else {
            this.checkNextBuildId(jsap);
            LOGGER.info("The pipeline will try to reproduce a bug from build "+this.getConfig().getBuildId()+" and its corresponding patch from build "+this.getConfig().getNextBuildId());
        }

        if (this.getConfig().isDebug()) {
            Utils.setLoggersLevel(Level.DEBUG);
        } else {
            Utils.setLoggersLevel(Level.INFO);
        }

        this.initSerializerEngines();
        this.initNotifiers();
    }

    public static JSAP defineArgs() throws JSAPException {
        // Verbose output
        JSAP jsap = new JSAP();

        // -h or --help
        jsap.registerParameter(LauncherUtils.defineArgHelp());
        // -d or --debug
        jsap.registerParameter(LauncherUtils.defineArgDebug());
        // --runId
        jsap.registerParameter(LauncherUtils.defineArgRunId());
        // --bears
        jsap.registerParameter(LauncherUtils.defineArgBearsMode());
        // --checkstyle
        jsap.registerParameter(LauncherUtils.defineArgCheckstyleMode());
        // -o or --output
        jsap.registerParameter(LauncherUtils.defineArgOutput(LauncherType.PIPELINE, "Specify path to output serialized files"));
        // --dbhost
        jsap.registerParameter(LauncherUtils.defineArgMongoDBHost());
        // --dbname
        jsap.registerParameter(LauncherUtils.defineArgMongoDBName());
        // --smtpServer
        jsap.registerParameter(LauncherUtils.defineArgSmtpServer());
        // --smtpPort
        jsap.registerParameter(LauncherUtils.defineArgSmtpPort());
        // --smtpTLS
        jsap.registerParameter(LauncherUtils.defineArgSmtpTLS());
        // --smtpUsername
        jsap.registerParameter(LauncherUtils.defineArgSmtpUsername());
        // --smtpPassword
        jsap.registerParameter(LauncherUtils.defineArgSmtpPassword());
        // --notifyto
        jsap.registerParameter(LauncherUtils.defineArgNotifyto());
        // --pushurl
        jsap.registerParameter(LauncherUtils.defineArgPushUrl());
        // --ghOauth
        jsap.registerParameter(LauncherUtils.defineArgGithubOAuth());
        // --githubUserName
        jsap.registerParameter(LauncherUtils.defineArgGithubUserName());
        // --githubUserEmail
        jsap.registerParameter(LauncherUtils.defineArgGithubUserEmail());
        // --createPR
        jsap.registerParameter(LauncherUtils.defineArgCreatePR());

        FlaggedOption opt2 = new FlaggedOption("build");
        opt2.setShortFlag('b');
        opt2.setLongFlag("build");
        opt2.setStringParser(JSAP.INTEGER_PARSER);
        opt2.setRequired(true);
        opt2.setHelp("Specify the build id to use.");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("nextBuild");
        opt2.setShortFlag('n');
        opt2.setLongFlag("nextBuild");
        opt2.setStringParser(JSAP.INTEGER_PARSER);
        opt2.setDefault(InputBuildId.NO_PATCH+"");
        opt2.setHelp("Specify the next build id to use (only in BEARS mode).");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("z3");
        opt2.setLongFlag("z3");
        opt2.setDefault("./z3_for_linux");
        // opt2.setStringParser(FileStringParser.getParser().setMustBeFile(true).setMustExist(true));
        opt2.setHelp("Specify path to Z3");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("workspace");
        opt2.setLongFlag("workspace");
        opt2.setShortFlag('w');
        opt2.setDefault("./workspace");
        opt2.setStringParser(JSAP.STRING_PARSER);
        opt2.setHelp("Specify a path to be used by the pipeline at processing things like to clone the project of the build id being processed");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("projectsToIgnore");
        opt2.setLongFlag("projectsToIgnore");
        opt2.setStringParser(FileStringParser.getParser().setMustBeFile(true));
        opt2.setHelp("Specify the file containing a list of projects that the pipeline should deactivate serialization when processing builds from.");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("listenermode");
        opt2.setLongFlag("listenermode");
        opt2.setStringParser(JSAP.STRING_PARSER);
        opt2.setDefault(LISTENER_MODE.NOOP.name());
        opt2.setHelp("Possible string values KUBERNETES,NOOP . KUBERNETES is for running ActiveMQListener and "+LISTENER_MODE.NOOP.name()+" is for NoopRunner.");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("activemqurl");
        opt2.setLongFlag("activemqurl");
        opt2.setStringParser(JSAP.STRING_PARSER);
        opt2.setDefault("tcp://localhost:61616");
        opt2.setHelp("format: 'tcp://IP_OR_DNSNAME:61616', default as 'tcp://localhost:61616'");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("activemqlistenqueuename");
        opt2.setLongFlag("activemqlistenqueuename");
        opt2.setStringParser(JSAP.STRING_PARSER);
        opt2.setDefault("pipeline");
        opt2.setHelp("Just a name, default as 'pipeline'");
        jsap.registerParameter(opt2);

        opt2 = new FlaggedOption("repairTools");
        opt2.setLongFlag("repairTools");
        String availablerepairTools = StringUtils.join(RepairToolsManager.getRepairToolsName(), ",");

        opt2.setStringParser(EnumeratedStringParser.getParser(availablerepairTools.replace(',',';'), true));
        opt2.setList(true);
        opt2.setListSeparator(',');
        opt2.setHelp("Specify one or several repair tools to use among: "+availablerepairTools);
        opt2.setDefault(NPERepair.TOOL_NAME); // default one is not all available ones
        jsap.registerParameter(opt2);

        // This option will have a list and must have n*3 elements, otherwise the last will be ignored.
        opt2 = new FlaggedOption("experimentalPluginRepoList");
        opt2.setLongFlag("experimentalPluginRepoList");
        opt2.setList(true);
        opt2.setListSeparator(',');
        opt2.setStringParser(JSAP.STRING_PARSER);
        opt2.setHelp("The ids, names and urls of all experimental pluginrepos used. Must be a list of length n*3 in the order id, name, url, repeat.");
        jsap.registerParameter(opt2);

        return jsap;
    }

    private void initConfig(JSAPResult arguments) {
        if (LauncherUtils.getArgDebug(arguments)) {
            this.getConfig().setDebug(true);
        }
        this.getConfig().setClean(true);
        this.getConfig().setRunId(LauncherUtils.getArgRunId(arguments));
        this.getConfig().setGithubToken(LauncherUtils.getArgGithubOAuth(arguments));
        if (LauncherUtils.gerArgBearsMode(arguments)) {
            this.getConfig().setLauncherMode(LauncherMode.BEARS);
        } else if (LauncherUtils.gerArgCheckstyleMode(arguments)) {
            this.getConfig().setLauncherMode(LauncherMode.CHECKSTYLE);
        } else {
            this.getConfig().setLauncherMode(LauncherMode.REPAIR);
        }
        if (LauncherUtils.getArgOutput(arguments) != null) {
            this.getConfig().setOutputPath(LauncherUtils.getArgOutput(arguments).getPath());
        }
        this.getConfig().setMongodbHost(LauncherUtils.getArgMongoDBHost(arguments));
        this.getConfig().setMongodbName(LauncherUtils.getArgMongoDBName(arguments));
        this.getConfig().setSmtpServer(LauncherUtils.getArgSmtpServer(arguments));
        this.getConfig().setSmtpPort(LauncherUtils.getArgSmtpPort(arguments));
        this.getConfig().setSmtpTLS(LauncherUtils.getArgSmtpTLS(arguments));
        this.getConfig().setSmtpUsername(LauncherUtils.getArgSmtpUsername(arguments));
        this.getConfig().setSmtpPassword(LauncherUtils.getArgSmtpPassword(arguments));
        this.getConfig().setNotifyTo(LauncherUtils.getArgNotifyto(arguments));

        if (LauncherUtils.getArgPushUrl(arguments) != null) {
            this.getConfig().setPush(true);
            this.getConfig().setPushRemoteRepo(LauncherUtils.getArgPushUrl(arguments));
        }
        this.getConfig().setCreatePR(LauncherUtils.getArgCreatePR(arguments));

        // we fork if we need to create a PR or if we need to notify
        if (this.getConfig().isCreatePR() || (this.getConfig().getSmtpServer() != null && !this.getConfig().getSmtpServer().isEmpty() && this.getConfig().getNotifyTo() != null && this.getConfig().getNotifyTo().length > 0)) {
            this.getConfig().setFork(true);
        }
        this.getConfig().setBuildId(arguments.getInt("build"));
        if (this.getConfig().getLauncherMode() == LauncherMode.BEARS) {
            this.getConfig().setNextBuildId(arguments.getInt("nextBuild"));
        }
        this.getConfig().setZ3solverPath(new File(arguments.getString("z3")).getPath());
        this.getConfig().setWorkspacePath(arguments.getString("workspace"));
        this.getConfig().setGithubUserEmail(LauncherUtils.getArgGithubUserEmail(arguments));
        this.getConfig().setGithubUserName(LauncherUtils.getArgGithubUserName(arguments));
        this.getConfig().setListenerMode(arguments.getString("listenermode"));
        this.getConfig().setActiveMQUrl(arguments.getString("activemqurl"));
        this.getConfig().setActiveMQListenQueueName(arguments.getString("activemqlistenqueuename"));

        if (arguments.getFile("projectsToIgnore") != null) {
            this.getConfig().setProjectsToIgnoreFilePath(arguments.getFile("projectsToIgnore").getPath());
        }

        this.getConfig().setRepairTools(new HashSet<>(Arrays.asList(arguments.getStringArray("repairTools"))));
        if (this.getConfig().getLauncherMode() == LauncherMode.REPAIR) {
            LOGGER.info("The following repair tools will be used: " + StringUtils.join(this.getConfig().getRepairTools(), ", "));
        }

        // Make sure that it is a multiple of three in the list
        if((arguments.getStringArray("experimentalPluginRepoList").length) % 3 == 0) {
            this.getConfig().setExperimentalPluginRepoList(arguments.getStringArray("experimentalPluginRepoList"));
        } else if (arguments.getStringArray("experimentalPluginRepoList").length != 0) {
            LOGGER.warn("The experimental plugin repo list is not correctly formed."
                    + " Please make sure you have provided id, name and url for all repos. "
                    + "Repairnator will continue without these repos.");
            this.getConfig().setExperimentalPluginRepoList(null);
        } else {
            this.getConfig().setExperimentalPluginRepoList(null);
        }
    }

    private void checkToolsLoaded(JSAP jsap) {
        URLClassLoader loader;

        try {
            loader = (URLClassLoader) ClassLoader.getSystemClassLoader();
            loader.loadClass("com.sun.jdi.AbsentInformationException");
        } catch (ClassNotFoundException e) {
            System.err.println("Tools.jar must be loaded. The classpath given for your app is: "+System.getProperty("java.class.path"));
        }
    }

    private void checkNopolSolverPath(JSAP jsap) {
        String solverPath = this.getConfig().getZ3solverPath();
        // by default Nopol run in Dynamoth mode
        // so no solver is mandatory
    }

    private void checkNextBuildId(JSAP jsap) {
        if (this.getConfig().getNextBuildId() == InputBuildId.NO_PATCH) {
            System.err.println("A pair of builds needs to be provided in BEARS mode.");
        }
    }

    private void initSerializerEngines() {
        this.engines = new ArrayList<>();

        List<SerializerEngine> fileSerializerEngines = LauncherUtils.initFileSerializerEngines(LOGGER);
        this.engines.addAll(fileSerializerEngines);

        SerializerEngine mongoDBSerializerEngine = LauncherUtils.initMongoDBSerializerEngine(LOGGER);
        if (mongoDBSerializerEngine != null) {
            this.engines.add(mongoDBSerializerEngine);
        }
    }

    private void initNotifiers() {
        List<NotifierEngine> notifierEngines = LauncherUtils.initNotifierEngines(LOGGER);
        ErrorNotifier.getInstance(notifierEngines);

        this.notifiers = new ArrayList<>();
        this.notifiers.add(new BugAndFixerBuildsNotifier(notifierEngines));

        this.patchNotifier = new PatchNotifierImpl(notifierEngines);
    }

    private List<String> getListOfProjectsToIgnore() {
        List<String> result = new ArrayList<>();
        if (this.getConfig().getProjectsToIgnoreFilePath() != null) {
            try {
                List<String> lines = Files.readAllLines(new File(this.getConfig().getProjectsToIgnoreFilePath()).toPath());
                for (String line : lines) {
                    result.add(line.trim().toLowerCase());
                }
            } catch (IOException e) {
                LOGGER.error("Error while reading projects to be ignored from file "+this.getConfig().getProjectsToIgnoreFilePath(), e);
            }
        }
        return result;
    }

    private boolean getBuildToBeInspected() {
        JTravis jTravis = this.getConfig().getJTravis();
        Optional<Build> optionalBuild = jTravis.build().fromId(this.getConfig().getBuildId());
        if (!optionalBuild.isPresent()) {
            LOGGER.error("Error while retrieving the buggy build. The process will exit now.");
            return false;
        }

        Build buggyBuild = optionalBuild.get();
        if (buggyBuild.getFinishedAt() == null) {
            LOGGER.error("Apparently the buggy build is not yet finished (maybe it has been restarted?). The process will exit now.");
            return false;
        }
        String runId = this.getConfig().getRunId();

        if (this.getConfig().getLauncherMode() == LauncherMode.BEARS) {
            Optional<Build> optionalBuildPatch = jTravis.build().fromId(this.getConfig().getNextBuildId());
            if (!optionalBuildPatch.isPresent()) {
                LOGGER.error("Error while getting patched build: null value was obtained. The process will exit now.");
                return false;
            }

            Build patchedBuild = optionalBuildPatch.get();
            LOGGER.info("The patched build (" + patchedBuild.getId() + ") was successfully retrieved from Travis.");

            if (buggyBuild.getState() == StateType.FAILED) {
                this.buildToBeInspected = new BuildToBeInspected(buggyBuild, patchedBuild, ScannedBuildStatus.FAILING_AND_PASSING, runId);
            } else {
                this.buildToBeInspected = new BuildToBeInspected(buggyBuild, patchedBuild, ScannedBuildStatus.PASSING_AND_PASSING_WITH_TEST_CHANGES, runId);
            }
        } else {
            Optional<Build> optionalNextPassing = jTravis.build().getAfter(buggyBuild, true, StateType.PASSED);
            if (optionalNextPassing.isPresent()) {
                this.buildToBeInspected = new BuildToBeInspected(buggyBuild, optionalNextPassing.get(), ScannedBuildStatus.FAILING_AND_PASSING, runId);
            } else {
                this.buildToBeInspected = new BuildToBeInspected(buggyBuild, null, ScannedBuildStatus.ONLY_FAIL, runId);
            }

            // switch off push mechanism in case of test project
            // and switch off serialization
            String project = buggyBuild.getRepository().getSlug().toLowerCase();
            if (this.getListOfProjectsToIgnore().contains(project)) {
                this.getConfig().setPush(false);
                this.getConfig().setFork(false);
                this.getConfig().setCreatePR(false);
                this.engines.clear();
                LOGGER.info("The build "+this.getConfig().getBuildId()+" is from a project to be ignored ("+project+"), thus the pipeline deactivated serialization for that build.");
            }
        }
        return true;
    }

    public boolean mainProcess() {
        LOGGER.info("Start by getting the build (buildId: "+this.getConfig().getBuildId()+") with the following config: "+this.getConfig());
        if (!this.getBuildToBeInspected()) {
            return false;
        }

        HardwareInfoSerializer hardwareInfoSerializer = new HardwareInfoSerializer(this.engines, this.getConfig().getRunId(), this.getConfig().getBuildId()+"");
        hardwareInfoSerializer.serialize();

        List<AbstractDataSerializer> serializers = new ArrayList<>();

        if (this.getConfig().getLauncherMode() == LauncherMode.BEARS) {
            inspector = new ProjectInspector4Bears(buildToBeInspected, this.getConfig().getWorkspacePath(), serializers, this.notifiers);
        } else if (this.getConfig().getLauncherMode() == LauncherMode.CHECKSTYLE) {
            inspector = new ProjectInspector4Checkstyle(buildToBeInspected, this.getConfig().getWorkspacePath(), serializers, this.notifiers);
        } else {
            inspector = new ProjectInspector(buildToBeInspected, this.getConfig().getWorkspacePath(), serializers, this.notifiers);
        }

        if (this.getConfig().getLauncherMode() == LauncherMode.BEARS) {
            serializers.add(new InspectorSerializer4Bears(this.engines, inspector));
        } else {
            serializers.add(new InspectorSerializer(this.engines, inspector));
        }

        serializers.add(new PropertiesSerializer(this.engines, inspector));
        serializers.add(new InspectorTimeSerializer(this.engines, inspector));
        serializers.add(new PipelineErrorSerializer(this.engines, inspector));
        serializers.add(new PatchesSerializer(this.engines, inspector));
        serializers.add(new ToolDiagnosticSerializer(this.engines, inspector));
        serializers.add(new PullRequestSerializer(this.engines, inspector));

        inspector.setPatchNotifier(this.patchNotifier);
        inspector.run();

        LOGGER.info("Inspector is finished. The process will exit now.");
        return true;
    }

    /**
     * if listener mode is NOOP it will run the usual pipeline, aka mainProcess in runListenerServer 
     * if KUBERNETES it will run as ActiveMQListener and run kubernetesProcess.
     *
     * @param launcher , launch depending on listenerMode 
     */
    private static void initProcess(Launcher launcher) {
        try {
            Constructor c = Class.forName(getConfig().getListenerMode().getKlass()).getConstructor(Launcher.class);
            listener = (Listener) c.newInstance(launcher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        listener.runListenerServer();
    }

    public static void main(String[] args) throws JSAPException {
        Launcher launcher = new Launcher(args);
        initProcess(launcher);
    }

    public ProjectInspector getInspector() {
        return inspector;
    }

    public void setPatchNotifier(PatchNotifier patchNotifier) {
        this.patchNotifier = patchNotifier;
    }
}
