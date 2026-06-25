import jenkins.model.Jenkins
import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// This init script reads /var/jenkins_home/repos.json and creates (or updates)
// one Multibranch Pipeline job per repository.
//
// Each job is configured with a FolderProperty that injects the repository's
// REPO_NAME / DEPLOY_PATH / DEPLOY_TYPE as environment variables so the
// Jenkinsfile macros (e.g. ${DEPLOY_PATH}) never resolve to null.
// ---------------------------------------------------------------------------

def jsonFile = '/var/jenkins_home/repos.json'
def file = new File(jsonFile)

if (!file.exists()) {
    println "repos.json not found at ${jsonFile}. No jobs created."
    return
}

def repos = new JsonSlurper().parseText(file.text)
Jenkins jenkins = Jenkins.getInstanceOrNull()

// Helper closures -----------------------------------------------------------

/**
 * Build the per-job folder-property XML that injects REPO_NAME, DEPLOY_PATH
 * and DEPLOY_TYPE into every branch build for this repository.
 */
def buildFolderPropertiesXml(repoName, deployPath, deployType) {
    return """\
  <properties>
    <org.jenkinsci.plugins.workflow.multibranch.job.BranchJobProperty>
      <healthReportsDisabled>false</healthReportsDisabled>
      <disableTriggerPlugin>false</disableTriggerPlugin>
    </org.jenkinsci.plugins.workflow.multibranch.job.BranchJobProperty>
    <org.jenkinsci.plugins.envinject.EnvInjectFolderProperty plugin="envinject">
      <propertiesFilePath></propertiesFilePath>
      <propertiesContent>REPO_NAME=${repoName}
DEPLOY_PATH=${deployPath}
DEPLOY_TYPE=${deployType}</propertiesContent>
      <scriptFilePath></scriptFilePath>
      <scriptContent></scriptContent>
      <loadFilesFromMaster>false</loadFilesFromMaster>
    </org.jenkinsci.plugins.envinject.EnvInjectFolderProperty>
  </properties>"""
}

/**
 * Build the complete multibranch job XML.
 */
def buildJobXml(jobName, gitUrl, repoPropertiesXml) {
    return """<?xml version='1.1' encoding='UTF-8'?>
<org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject plugin="workflow-multibranch">
  <actions/>
  <description>CI/CD for ${jobName}</description>
${repoPropertiesXml}
  <folderViews class="jenkins.branch.MultiBranchProjectViewHolder" plugin="branch-api">
    <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
  </folderViews>
  <healthMetrics>
    <com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric/>
  </healthMetrics>
  <icon class="jenkins.branch.MetadataActionFolderIcon" plugin="branch-api">
    <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
  </icon>
  <orphanedItemStrategy class="com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy" plugin="cloudbees-folder">
    <pruneDeadBranches>true</pruneDeadBranches>
    <daysToKeep>7</daysToKeep>
    <numToKeep>20</numToKeep>
  </orphanedItemStrategy>
  <triggers>
    <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
      <spec>H/5 * * * *</spec>
      <interval>300000</interval>
    </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
  </triggers>
  <remoteTriggers>
    <org.jenkinsci.plugins.github__branch__source.GitHubBranchTrigger>
      <credentialsId>github-creds</credentialsId>
    </org.jenkinsci.plugins.github__branch__source.GitHubBranchTrigger>
  </remoteTriggers>
  <sources class="jenkins.branch.MultiBranchProject\$BranchSourceList" plugin="branch-api">
    <data>
      <jenkins.branch.BranchSource>
        <source class="org.jenkinsci.plugins.github__branch__source.GitHubSCMSource" plugin="github-branch-source">
          <id>${jobName}-source</id>
          <credentialsId>github-creds</credentialsId>
          <repoOwner>OmKatiyarARF</repoOwner>
          <repository>${jobName}</repository>
          <traits>
            <org.jenkinsci.plugins.github__branch__source.BranchDiscoveryTrait>
              <strategyId>3</strategyId>
            </org.jenkinsci.plugins.github__branch__source.BranchDiscoveryTrait>
          </traits>
        </source>
        <strategy class="jenkins.branch.DefaultBranchPropertyStrategy">
          <properties class="empty-list"/>
        </strategy>
      </jenkins.branch.BranchSource>
    </data>
    <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
  </sources>
  <factory class="org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory">
    <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
    <scriptPath>Jenkinsfile</scriptPath>
  </factory>
</org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>"""
}

// Main loop -----------------------------------------------------------------

repos.each { repo ->
    if (repo.disabled == true) {
        println "Skipping disabled repo: ${repo.name}"
        return
    }

    def jobName = repo.name
    def gitUrl = repo.git_url
    def deployPath = repo.deploy?.path ?: "/home/ec2-user/${repo.name}"
    def deployType = repo.deploy?.type ?: "docker"

    def existing = jenkins.getItem(jobName)

    if (existing == null) {
        println "Creating Multibranch Pipeline job: ${jobName}"
        
        def propsXml = buildFolderPropertiesXml(jobName, deployPath, deployType)
        def xml = buildJobXml(jobName, gitUrl, propsXml)

        def is = new ByteArrayInputStream(xml.getBytes("UTF-8"))
        jenkins.createProjectFromXML(jobName, is)
        is.close()
        
        println "Created job: ${jobName}  (REPO_NAME=${jobName}, DEPLOY_PATH=${deployPath}, DEPLOY_TYPE=${deployType})"
    } else {
        println "Job already exists: ${jobName} — checking EnvInject property..."

        // Update the EnvInjectFolderProperty if it is missing or stale
        def envPropCls = jenkins.pluginManager.uberClassLoader.loadClass(
            'org.jenkinsci.plugins.envinject.EnvInjectFolderProperty')
        def currentProp = existing.getProperty(envPropCls)

        def expectedContent = "REPO_NAME=${jobName}\nDEPLOY_PATH=${deployPath}\nDEPLOY_TYPE=${deployType}"

        if (currentProp == null || currentProp.propertiesContent != expectedContent) {
            existing.removeProperty(envPropCls)
            def ctor = envPropCls.getConstructor()
            def newProp = ctor.newInstance()
            newProp.setPropertiesFilePath('')
            newProp.setPropertiesContent(expectedContent)
            newProp.setScriptFilePath('')
            newProp.setScriptContent('')
            newProp.setLoadFilesFromMaster(false)
            existing.addProperty(newProp)
            existing.save()
            println "Updated env inject for ${jobName}"
        } else {
            println "Env inject already correct for ${jobName}"
        }
    }
}

jenkins.save()
println "Job creation complete."