package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.remoting.Callable;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.lang.StringUtils.isEmpty;

/**
 * Decorate Launcher so that every command executed by a build step is actually ran inside docker container.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerBuildWrapper extends BuildWrapper {

    private static final Logger LOGGER = Logger.getLogger(DockerBuildWrapper.class.getName());
    private static Callable<String, IOException> GetTmpdir = new MasterToSlaveCallable<String, IOException>() {
        @Override
        public String call() {
            return System.getProperty("java.io.tmpdir");
        }
    };
    private final DockerImageSelector selector;
    private final String dockerInstallation;
    private final DockerServerEndpoint dockerHost;
    private final boolean verbose;
    private final boolean privileged;
    private final boolean forcePull;
    private final String blockingStartupCommand;
    private List<Volume> volumes;
    private String group;
    private String command;
    private String net;
    private String memory;
    private String cpu;
    private String dockerSlaveJenkinsRoot;
    private String dockerSlaveTmpDir;
    private String additionalArguments;
    private transient boolean exposeDocker;

    @DataBoundConstructor
    public DockerBuildWrapper(DockerImageSelector selector,
                              String dockerInstallation,
                              DockerServerEndpoint dockerHost,
                              boolean verbose,
                              boolean privileged,
                              List<Volume> volumes,
                              String group,
                              String command,
                              boolean forcePull,
                              String net,
                              String memory,
                              String cpu,
                              String dockerSlaveJenkinsRoot,
                              String dockerSlaveTmpDir,
                              String additionalArguments,
                              String blockingStartupCommand) {
        this.selector = selector;
        this.dockerInstallation = dockerInstallation;
        this.dockerHost = dockerHost;
        this.verbose = verbose;
        this.privileged = privileged;
        this.volumes = volumes != null ? volumes : Collections.<Volume>emptyList();
        this.group = group;
        this.command = command;
        this.forcePull = forcePull;
        this.net = net;
        this.memory = memory;
        this.cpu = cpu;
        this.dockerSlaveJenkinsRoot = dockerSlaveJenkinsRoot;
        this.dockerSlaveTmpDir = dockerSlaveTmpDir;
        this.additionalArguments = additionalArguments;
        this.blockingStartupCommand = blockingStartupCommand;
    }

    public String getAdditionalArguments() {
        return additionalArguments;
    }

    public String getBlockingStartupCommand() {
        return blockingStartupCommand;
    }

    public String getCommand() {
        return command;
    }

    public String getCpu() {
        return cpu;
    }

    public DockerServerEndpoint getDockerHost() {
        return dockerHost;
    }

    public String getDockerInstallation() {
        return dockerInstallation;
    }

    public String getDockerSlaveJenkinsRoot() {
        return dockerSlaveJenkinsRoot;
    }

    public String getDockerSlaveTmpDir() {
        return dockerSlaveTmpDir;
    }

    public String getGroup() {
        return group;
    }

    public String getMemory() {
        return memory;
    }

    public String getNet() {
        return net;
    }

    public DockerImageSelector getSelector() {
        return selector;
    }

    public List<Volume> getVolumes() {
        return volumes;
    }

    public boolean isForcePull() {
        return forcePull;
    }

    public boolean isPrivileged() {
        return privileged;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public Environment setUp(AbstractBuild build,
                             final Launcher launcher,
                             BuildListener listener) throws IOException, InterruptedException {

        // setUp is executed after checkout, so hook here to prepare and run Docker image to host the build

        BuiltInContainer runInContainer = build.getAction(BuiltInContainer.class);

        try {
            // mount slave root in Docker container so build process can access project workspace, tools, as well as
            // jars copied by maven plugin.
            final String root = Computer.currentComputer().getNode().getRootPath().getRemote();
            if (isEmpty(dockerSlaveJenkinsRoot)) {
                runInContainer.bindMount(root);
            } else {
                runInContainer.bindMount(TokenMacro.expand(build, listener, dockerSlaveJenkinsRoot), root);
            }

            // mount tmpdir so we can access temporary file created to run shell build steps (and few others)
            String tmp = build.getWorkspace().act(GetTmpdir);
            if (isEmpty(dockerSlaveTmpDir)) {
                runInContainer.bindMount(tmp);
            } else {
                runInContainer.bindMount(TokenMacro.expand(build, listener, dockerSlaveTmpDir), tmp);
            }
        } catch (MacroEvaluationException mee) {
            throw new RuntimeException("Macro evaluation failed", mee);
        }

        // mount ToolIntallers installation directory so installed tools are available inside container

        for (Volume volume : volumes) {
            runInContainer.bindMount(volume.getHostPath(), volume.getPath());
        }


        if (runInContainer.container == null) {
            if (runInContainer.image == null) {
                try {
                    synchronized (DockerRegistryToken.class) { // When multiple builds try to pull an image they may be overriding credentials
                        runInContainer.getDocker().setupCredentials(build);
                        runInContainer.image = selector.prepareDockerImage(
                                runInContainer.getDocker(),
                                build,
                                listener,
                                forcePull
                        );
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted");
                }
            }

            runInContainer.container = startBuildContainer(runInContainer, build, listener);
            listener.getLogger().println("Docker container " + runInContainer.container + " started to host the build");
        }

        // We are all set, DockerDecoratedLauncher now can wrap launcher commands with docker-exec
        runInContainer.enable();

        int result = launcher.launch()
                             .cmds(blockingStartupCommand.split(" "))
                             .envs(build.getEnvironment(listener))
                             .stdout(listener)
                             .pwd(build.getWorkspace())
                             .start()
                             .join();
        if (result != 0) {
            BuiltInContainer builtIn = build.getAction(BuiltInContainer.class);
            if (verbose) {
                try {
                    builtIn.getDocker().logs(builtIn.container);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Error getting container logs", ex);
                }
            }
            builtIn.tearDown();
            return null;
        }

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build,
                                    BuildListener listener) throws IOException, InterruptedException {
                BuiltInContainer builtIn = build.getAction(BuiltInContainer.class);
                if (verbose) {
                    try {
                        builtIn.getDocker().logs(builtIn.container);
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Error getting container logs", ex);
                    }
                }
                return builtIn.tearDown();
            }
        };
    }

    @Override
    public Launcher decorateLauncher(final AbstractBuild build,
                                     final Launcher launcher,
                                     final BuildListener listener) throws IOException, InterruptedException, Run
            .RunnerAbortedException {
        final Docker docker = new Docker(
                dockerHost,
                dockerInstallation,
                selector,
                build,
                launcher,
                listener,
                verbose,
                privileged
        );

        final BuiltInContainer runInContainer = new BuiltInContainer(docker);
        build.addAction(runInContainer);

        DockerDecoratedLauncher decorated = new DockerDecoratedLauncher(
                selector,
                launcher,
                runInContainer,
                build,
                whoAmI(launcher)
        );
        return decorated;
    }

    /**
     * Create the container environment.
     * We can't just pass result of {@link AbstractBuild#getEnvironment(TaskListener)}, as this one do include slave
     * host
     * environment, that may not make any sense inside container (consider <code>PATH</code> for sample).
     */
    private EnvVars buildContainerEnvironment(AbstractBuild build,
                                              BuildListener listener) throws IOException, InterruptedException {
        EnvVars env = build.getEnvironment(listener);
        for (String key : Computer.currentComputer().getEnvironment().keySet()) {
            env.remove(key);
        }
        LOGGER.log(Level.FINE, "reduced environment: {0}", env);
        EnvVars.resolve(env);
        return env;
    }

    private Object readResolve() {
        if (volumes == null) volumes = new ArrayList<Volume>();
        if (exposeDocker) {
            this.volumes.add(new Volume("/var/run/docker.sock", "/var/run/docker.sock"));
        }
        if (command == null) command = "/bin/cat";
        return this;
    }

    private String startBuildContainer(BuiltInContainer runInContainer,
                                       AbstractBuild build,
                                       BuildListener listener) throws IOException {
        try {
            EnvVars environment = buildContainerEnvironment(build, listener);

            String workdir = build.getWorkspace().getRemote();

            Map<String, String> links = new HashMap<String, String>();

            String[] command = this.command.length() > 0 ? this.command.split(" ") : new String[0];

            return runInContainer.getDocker()
                                 .runDetached(
                                         runInContainer.image,
                                         workdir,
                                         runInContainer.getVolumes(build),
                                         runInContainer.getPortsMap(),
                                         links,
                                         environment,
                                         build.getSensitiveBuildVariables(),
                                         net,
                                         memory,
                                         cpu,
                                         additionalArguments,
                                         command
                                 ); // Command expected to hung until killed
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted");
        }
    }

    // --- backward compatibility

    private String whoAmI(Launcher launcher) throws IOException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        launcher.launch().cmds("id", "-u").stdout(bos).quiet(true).join();
        String uid = bos.toString().trim();

        String gid = group;
        if (isEmpty(group)) {
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

        public Collection<Descriptor<DockerImageSelector>> selectors() {
            return Jenkins.getInstance().getDescriptorList(DockerImageSelector.class);
        }
    }
}
