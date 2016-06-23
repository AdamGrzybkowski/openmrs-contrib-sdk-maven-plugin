package org.openmrs.maven.plugins;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.openmrs.maven.plugins.model.Artifact;
import org.openmrs.maven.plugins.model.Server;
import org.openmrs.maven.plugins.utility.Project;
import org.openmrs.maven.plugins.utility.SDKConstants;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * @goal run
 * @requiresProject false
 */
public class Run extends AbstractTask {

    public Run(){};

    public Run(AbstractTask other){
        super(other);
    }

	/**
	 * @parameter expression="${serverId}"
	 */
	private String serverId;

	/**
	 * @parameter expression="${port}"
	 */
	private Integer port;

	/**
	 * @parameter expression="${debug}"
	 */
	private String debug;

	/**
	 * @parameter expression="${fork}"
	 */
	private Boolean fork;


	public void executeTask() throws MojoExecutionException, MojoFailureException {
		if (serverId == null) {
			File currentProperties = wizard.getCurrentServerPath();
			if (currentProperties != null) serverId = currentProperties.getName();
		}
		serverId = wizard.promptForExistingServerIdIfMissing(serverId);
		Server server = Server.loadServer(serverId);
		File serverPath = server.getServerDirectory();
		serverPath.mkdirs();
		File userDir = new File(System.getProperty("user.dir"));
		if (Project.hasProject(userDir)) {
			Project config = Project.loadProject(userDir);
			if (config.isOpenmrsModule()) {
				String artifactId = config.getArtifactId();
				String groupId = config.getGroupId();
				String version = config.getVersion();
				if ((artifactId != null) && (groupId != null) && version != null) {
					getLog().info("OpenMRS module detected, installing before run...");
					Deploy deployer = new Deploy(this);
					deployer.deployModule(serverPath.getName(), groupId, artifactId, version);
				}
			}
		}

		try {
			deployWatchedModules(server);
		} catch (MavenInvocationException e) {
			e.printStackTrace();
		}

		if (Boolean.FALSE.equals(fork)) {
			new RunTomcat(serverId, port, wizard).execute();
		} else {
			runInFork(server);
		}
	}

	private void deployWatchedModules(Server server) throws MojoFailureException, MojoExecutionException, MavenInvocationException {
		Set<Project> watchedProject = server.getWatchedProjects();
		for (Project module: watchedProject) {
			File pomFile = new File(module.getPath(), "pom.xml");
			MavenProject project = readPomFile(pomFile);
			cleanInstallModule(module.getPath());
			new Deploy(this).deployModule(project.getGroupId(), project.getArtifactId(), project.getVersion(), server);
		}
	}

	private MavenProject readPomFile(File pomFile){
		Model model = null;
		FileReader reader = null;
		MavenXpp3Reader mavenreader = new MavenXpp3Reader();
		try {
			reader = new FileReader(pomFile);
			model = mavenreader.read(reader);
			model.setPomFile(pomFile);
		}catch(Exception ex){}

		return new MavenProject(model);
	}

	private void cleanInstallModule(String directory) throws MojoFailureException {
		String maven = "mvn";
		if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")) {
			maven = "mvn.bat";
		}
		List<String> commands = new ArrayList<String>();
		commands.add(maven);
		commands.add("clean");
		commands.add("install");

		ProcessBuilder processBuilder = new ProcessBuilder(commands);
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
		processBuilder.directory(new File(directory));
		try {
			final Process process = processBuilder.start();
		} catch (IOException e) {
			throw new MojoFailureException("Failed to build module", e);
		}


	}

	private void runInFork(Server server) throws MojoExecutionException, MojoFailureException {
		String maven = "mvn";
		if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")) {
			maven = "mvn.bat";
		}

		if (server.hasWatchedProjects()) {
			File serversPath = Server.getServersPathFile();
			File springloadedJar = new File(serversPath, "springloaded.jar");
			if (!springloadedJar.exists()) {
				Artifact artifact = new Artifact("springloaded", "1.2.5.RELEASE", "org.springframework", "jar");
				artifact.setDestFileName("springloaded.jar");
				executeMojo(
						plugin(
								groupId(SDKConstants.PLUGIN_DEPENDENCIES_GROUP_ID),
								artifactId(SDKConstants.PLUGIN_DEPENDENCIES_ARTIFACT_ID),
								version(SDKConstants.PLUGIN_DEPENDENCIES_VERSION)
						),
						goal("copy"),
						configuration(
								element(name("artifactItems"), artifact.toElement(serversPath.getAbsolutePath()))
						),
						executionEnvironment(mavenProject, mavenSession, pluginManager)
				);
			}
		}

		String mavenOpts = System.getProperty("MAVEN_OPTS", "");
		if (!mavenOpts.contains("-Xmx")) {
			mavenOpts += " -Xmx1536m";
		}
		if (!mavenOpts.contains("-XX:MaxPermSize=")) {
			mavenOpts += " -XX:MaxPermSize=1024m";
		}
		if (server.hasWatchedProjects()) {
			mavenOpts += " -javaagent:" + new File(Server.getServersPath(), "springloaded.jar").getAbsolutePath() + " -noverify";
		}

		if (debug != null) {
			String address = "1044";
			if (StringUtils.isNumeric(debug)) {
				address = debug;
			}
			mavenOpts += " -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + address;

			System.out.println("\nConnect remote debugger with port " + address + "\n");
		}

		System.setProperty("MAVEN_OPTS", mavenOpts);

		System.out.println("Using JAVA_HOME: " + System.getenv("JAVA_HOME") + "\n");
		System.out.println("Using MAVEN_OPTS: " + mavenOpts + "\n");

		System.out.println("Forking a new process... (use -Dfork=false to prevent forking)\n");

		List<String> commands = new ArrayList<String>();
		commands.add(maven);
		commands.add(SDKConstants.getSDKInfo().getGroupId() + ":"
				+ SDKConstants.getSDKInfo().getArtifactId() + ":" + SDKConstants.getSDKInfo().getVersion() + ":run-tomcat");
		commands.add("-DserverId=" + server.getServerId());
		if (port != null) {
			commands.add("-Dport=" + port);
		}
		if (server.hasWatchedProjects()) {
			commands.add("-Dspringloaded=inclusions=org.openmrs..*");
		}

		ProcessBuilder processBuilder = new ProcessBuilder(commands);
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
		try {
			final Process process = processBuilder.start();

			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {
					process.destroy();
				}
			});

			process.waitFor();
		} catch (IOException e) {
			throw new MojoFailureException("Failed to start Tomcat process", e);
		} catch (InterruptedException e) {
			throw new MojoFailureException("Interrupted waiting for Tomcat process", e);
		}
	}
}
