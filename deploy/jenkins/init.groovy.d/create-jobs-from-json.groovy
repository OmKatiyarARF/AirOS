import jenkins.model.Jenkins
import groovy.json.JsonSlurper
import jenkins.branch.BranchSource
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory
import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger
import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy

// ---------------------------------------------------------------------------
// Reads /var/jenkins_home/repos.json and creates/updates one Multibranch
// Pipeline job per repository.
//
// Each repos.json entry supports:
//   name           - Jenkins job display name (required)
//   git_url        - repo URL; GitHub owner + repo are parsed from it (required)
//   branch         - the ONLY branch to scan/build/deploy (default "main")
//   credentials_id - Jenkins credential id for this repo's GitHub account
//                    (default "github-creds"); must match an id in casc/jenkins.yaml
//   disabled       - true to skip creating a job for this repo
//
// The job source is built with the Jenkins Java API (not hand-written XML):
// a GitHubSCMSource with a BranchDiscoveryTrait plus a RegexSCMHeadFilterTrait
// that restricts discovery to exactly `branch`. (Hand-written XML silently
// drops the source when the filter trait is present, so the API is used.)
// ---------------------------------------------------------------------------

def jsonFile = '/var/jenkins_home/repos.json'
def file = new File(jsonFile)

if (!file.exists()) {
    println "repos.json not found at ${jsonFile}. No jobs created."
    return
}

def repos = new JsonSlurper().parseText(file.text)
Jenkins jenkins = Jenkins.get()

/**
 * Parse the GitHub owner and repository name out of a git URL.
 * Handles HTTPS (https://github.com/owner/repo.git) and SSH
 * (git@github.com:owner/repo.git), with or without ".git".
 * Returns [owner, repository] or [null, null] if it can't be parsed.
 */
def parseOwnerRepo(gitUrl) {
    def m = (gitUrl =~ /github\.com[\/:]([^\/]+)\/(.+?)(?:\.git)?$/)
    if (!m.find()) {
        return [null, null]
    }
    return [m.group(1), m.group(2)]
}

/**
 * Build a GitHubSCMSource restricted to a single branch.
 * strategyId 3 = discover all branches; the regex filter then narrows it to
 * exactly `branch` (a full-match regex, so "main" excludes feature branches
 * and pull requests).
 */
def buildSource(repoOwner, repository, branch, credentialsId) {
    def source = new GitHubSCMSource(repoOwner, repository)
    source.setCredentialsId(credentialsId)
    source.setTraits([
        new BranchDiscoveryTrait(3),
        new RegexSCMHeadFilterTrait(branch)
    ])
    return source
}

/**
 * Trigger an immediate branch scan for a multibranch job.
 */
def triggerScan(job) {
    try {
        job.scheduleBuild2(0)
        println "Triggered branch scan for: ${job.name}"
    } catch (Exception e) {
        println "Failed to trigger scan for ${job.name}: ${e.message}"
    }
}

// Main loop -----------------------------------------------------------------

repos.each { repo ->
    if (repo.disabled == true) {
        println "Skipping disabled repo: ${repo.name}"
        return
    }

    def jobName = repo.name
    def branch = repo.branch ?: 'main'
    def credentialsId = repo.credentials_id ?: 'github-creds'

    def (repoOwner, repository) = parseOwnerRepo(repo.git_url)
    if (repoOwner == null) {
        println "Skipping ${jobName}: could not parse owner/repo from git_url '${repo.git_url}'"
        return
    }

    def job = jenkins.getItem(jobName)
    def newlyCreated = false
    if (job == null) {
        println "Creating Multibranch Pipeline job: ${jobName} (${repoOwner}/${repository}, branch '${branch}', creds '${credentialsId}')"
        job = jenkins.createProject(WorkflowMultiBranchProject, jobName)
        newlyCreated = true
    } else {
        println "Updating existing job: ${jobName} (${repoOwner}/${repository}, branch '${branch}', creds '${credentialsId}')"
    }

    // Configure the job idempotently via the API. Re-applying the same config
    // is cheap and lets repos.json changes (branch, credential, URL) take
    // effect on existing jobs — without the updateByXml source-loss bug.
    job.setDescription("CI/CD for ${jobName}")

    def factory = new WorkflowBranchProjectFactory()
    factory.setScriptPath('Jenkinsfile')
    job.setProjectFactory(factory)

    job.setOrphanedItemStrategy(new DefaultOrphanedItemStrategy(true, "7", "20"))
    job.addTrigger(new PeriodicFolderTrigger("5m"))   // periodic rescan

    job.setSourcesList([ new BranchSource(buildSource(repoOwner, repository, branch, credentialsId)) ])
    job.save()

    // Scan so the branch filter is applied and the branch is discovered now
    triggerScan(job)
    println "Job ready: ${jobName}"
}

jenkins.save()
println "Job creation complete."
