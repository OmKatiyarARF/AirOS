import jenkins.model.Jenkins
import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// This init script reads /var/jenkins_home/repos.json and creates (or updates)
// one Multibranch Pipeline job per repository.
//
// Environment variables (REPO_NAME, DEPLOY_PATH, DEPLOY_TYPE) are injected
// via the Jenkinsfile `environment {}` block instead of the deprecated
// EnvInject plugin, so no extra plugins are required.
//
// After creating a job, an immediate branch scan is triggered so branches
// are discovered right away instead of waiting for the periodic trigger.
// ---------------------------------------------------------------------------

def jsonFile = '/var/jenkins_home/repos.json'
def file = new File(jsonFile)

if (!file.exists()) {
    println "repos.json not found at ${jsonFile}. No jobs created."
    return
}

def repos = new JsonSlurper().parseText(file.text)
Jenkins jenkins = Jenkins.getInstanceOrNull()

/**
 * Build the complete multibranch job XML.
 */
def buildJobXml(jobName, gitUrl) {
    return """<?xml version='1.1' encoding='UTF-8'?>
<org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject plugin="workflow-multibranch">
  <actions/>
  <description>CI/CD for ${jobName}</description>
  <properties/>
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
            <org.jenkinsci.plugins.github__branch__source.OriginPullRequestDiscoveryTrait>
              <strategyId>2</strategyId>
            </org.jenkinsci.plugins.github__branch__source.OriginPullRequestDiscoveryTrait>
            <org.jenkinsci.plugins.github__branch__source.ForkPullRequestDiscoveryTrait>
              <strategyId>2</strategyId>
              <trust class="org.jenkinsci.plugins.github__branch__source.ForkPullRequestDiscoveryTrait\$TrustPermission"/>
            </org.jenkinsci.plugins.github__branch__source.ForkPullRequestDiscoveryTrait>
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

/**
 * Trigger an immediate branch scan for a multibranch job.
 */
def triggerScan(job) {
    try {
        def folderCls = jenkins.pluginManager.uberClassLoader.loadClass(
            'com.cloudbees.hudson.plugins.folder.computed.ComputedFolder')
        if (folderCls.isInstance(job)) {
            job.scheduleBuild(0, null)
            println "Triggered initial branch scan for: ${job.name}"
            return true
        }
    } catch (Exception e) {
        println "ComputedFolder API unavailable for ${job.name}: ${e.message}"
    }
    try {
        job.scheduleBuild(0, null)
        println "Triggered initial branch scan (fallback) for: ${job.name}"
        return true
    } catch (Exception e) {
        println "Failed to trigger scan for ${job.name}: ${e.message}"
        return false
    }
}

// Main loop -----------------------------------------------------------------

repos.each { repo ->
    if (repo.disabled == true) {
        println "Skipping disabled repo: ${repo.name}"
        return
    }

    def jobName = repo.name
    def gitUrl = repo.git_url

    def existing = jenkins.getItem(jobName)
    def newlyCreated = false

    if (existing == null) {
        println "Creating Multibranch Pipeline job: ${jobName}"

        def xml = buildJobXml(jobName, gitUrl)
        def is = new ByteArrayInputStream(xml.getBytes("UTF-8"))
        existing = jenkins.createProjectFromXML(jobName, is)
        is.close()
        newlyCreated = true

        println "Created job: ${jobName}"
    } else {
        println "Job already exists: ${jobName}"
    }

    // Trigger an initial scan so branches are discovered immediately
    if (newlyCreated && existing != null) {
        triggerScan(existing)
    }
}

jenkins.save()
println "Job creation complete."
