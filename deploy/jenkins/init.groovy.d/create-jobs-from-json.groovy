import jenkins.model.Jenkins
import hudson.model.TopLevelItem
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import groovy.json.JsonSlurper
import javax.xml.transform.stream.StreamSource

def jsonFile = '/var/jenkins_home/repos.json'
def file = new File(jsonFile)

if (!file.exists()) {
    println "repos.json not found at ${jsonFile}. No jobs created."
    return
}

def repos = new JsonSlurper().parseText(file.text)
Jenkins jenkins = Jenkins.getInstanceOrNull()

repos.each { repo ->
    if (repo.disabled == true) {
        println "Skipping disabled repo: ${repo.name}"
        return
    }
    
    def jobName = repo.name
    def gitUrl = repo.git_url
    def deployPath = repo.deploy?.path ?: "/home/ec2-user/${repo.name}"
    
    def existing = jenkins.getItem(jobName)
    
    if (existing == null) {
        println "Creating Multibranch Pipeline job: ${jobName} (git: ${gitUrl})"
        
        // Build the XML config for a Multibranch Pipeline job
        def xml = """<?xml version='1.1' encoding='UTF-8'?>
<org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject plugin="workflow-multibranch">
  <actions/>
  <description>CI/CD for ${jobName}</description>
  <properties>
    <org.jenkinsci.plugins.workflow.multibranch.job.BranchJobProperty>
      <healthReportsDisabled>false</healthReportsDisabled>
      <disableTriggerPlugin>false</disableTriggerPlugin>
    </org.jenkinsci.plugins.workflow.multibranch.job.BranchJobProperty>
  </properties>
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
  <sources class="jenkins.branch.MultiBranchProject$BranchSourceList" plugin="branch-api">
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
        
        // Create the job from XML
        def is = new ByteArrayInputStream(xml.getBytes("UTF-8"))
        jenkins.createProjectFromXML(jobName, is)
        is.close()
        
        println "Created job: ${jobName}"
    } else {
        println "Job already exists: ${jobName}"
    }
}

jenkins.save()
println "Job creation complete."