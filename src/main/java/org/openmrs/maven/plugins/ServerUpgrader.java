package org.openmrs.maven.plugins;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.openmrs.maven.plugins.model.*;
import org.openmrs.maven.plugins.utility.DistroHelper;
import org.openmrs.maven.plugins.utility.SDKConstants;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class ServerUpgrader {
    private AbstractTask parentTask;


	public ServerUpgrader(AbstractTask parentTask) {
        this.parentTask = parentTask;
    }

    /**
     * Upgrades platform of given server
     *
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void upgradePlatform(Server server, String version) throws MojoExecutionException, MojoFailureException {
        server.saveBackupProperties();
		confirmUpgrade(server.getPlatformVersion(), version);
		replaceWebapp(server, version);
	    server.deleteBackupProperties();
        parentTask.getLog().info(String.format("Server %s has been successfully upgraded to %s", server.getServerId(), version));
    }

	public void upgradeToDistro(Server server, DistroProperties distroProperties) throws MojoExecutionException, MojoFailureException {
        UpgradeDifferential upgradeDifferential = DistroHelper.calculateUpdateDifferential(server, distroProperties);
        boolean confirmed = parentTask.wizard.promptForConfirmDistroUpgrade(upgradeDifferential, server, distroProperties);
		if(confirmed){
			server.saveBackupProperties();
			List<Artifact> userModules = server.getUserModules();

			String modulesDir = server.getServerDirectory().getPath()+"/"+SDKConstants.OPENMRS_SERVER_MODULES;
			if(upgradeDifferential.getPlatformArtifact()!=null){
				replaceWebapp(server, upgradeDifferential.getPlatformArtifact().getVersion());
			}
			if(!upgradeDifferential.getModulesToAdd().isEmpty()){
				parentTask.moduleInstaller.installModules(upgradeDifferential.getModulesToAdd(), modulesDir);
			}
			if(!upgradeDifferential.getUpdateOldToNewMap().isEmpty()){
				for(Map.Entry<Artifact, Artifact> updateEntry : upgradeDifferential.getUpdateOldToNewMap().entrySet()){
					File oldModule = new File(modulesDir, updateEntry.getKey().getDestFileName());
					oldModule.delete();
					parentTask.moduleInstaller.installModule(updateEntry.getValue(), modulesDir);
					if(userModules.contains(updateEntry.getKey())){
						userModules.remove(updateEntry.getKey());
					}
				}
			}

			server.setUserModules(userModules);
			server.setVersion(distroProperties.getServerVersion());
			server.save();
			server.getDistroPropertiesFile().delete();
			distroProperties.saveTo(server.getServerDirectory());
			server.deleteBackupProperties();
			deleteDependencyPluginMarker();
			parentTask.getLog().info("Server upgraded successfully");
		} else {
			parentTask.wizard.showMessage("\nServer upgrade aborted");
		}

	}

	/**
	 * Maven dependency plugin leaves directory with marker in directory from which it was executed
	 * This method deletes it to clean up after upgrade
	 * @throws MojoExecutionException
	 */
	private void deleteDependencyPluginMarker() throws MojoExecutionException {
		File tmp = new File(System.getProperty("user.dir"), "${project.basedir}");
		if (tmp.exists()) try {
			FileUtils.deleteDirectory(tmp);
		} catch (IOException e) {
			throw new MojoExecutionException("Error during clean: " + e.getMessage());
		}
	}

	/**
	 * make sure that user is aware that he is downgrading server if target version is older
	 * @param prevVersion
	 * @param nextVersion
	 * @throws MojoExecutionException
	 */
    private void confirmUpgrade(String prevVersion, String nextVersion) throws MojoExecutionException {
        Version prev = new Version(prevVersion);
        Version next = new Version(nextVersion);
        if (prev.higher(next)){
            boolean confirmed =parentTask.wizard.promptYesNo("Note that downgrades are generally not supported by OpenMRS. " +
                    "Please consider setting up a new server with the given version instead.\n" +
                    "Are you sure you would like to downgrade?");
            if(!confirmed) throw new MojoExecutionException("Installation has been aborted");
        }
    }

    /**
     * @param server server to upgrade
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */
    private void replaceWebapp(Server server, String version) throws MojoFailureException, MojoExecutionException {
        File webapp = new File(server.getServerDirectory(), "openmrs-"+server.getPlatformVersion()+".war");
        webapp.delete();
        Artifact webappArtifact = new Artifact(SDKConstants.WEBAPP_ARTIFACT_ID, version, Artifact.GROUP_WEB, Artifact.TYPE_WAR);
        parentTask.moduleInstaller.installModule(webappArtifact, server.getServerDirectory().getPath());
        server.setPlatformVersion(version);
        server.save();
    }
}
