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
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

// ---------------------------------------------------------------------------
// Reads /var/jenkins_home/repos.json and creates/updates one Pipeline job per
// entry. This is the SINGLE source of truth for every Jenkins job — both the
// "real" multibranch jobs that build a repo's own Jenkinsfile, and standalone
// AirOS-owned jobs (e.g. isolated test-environment deploys) that must not
// modify the target app repo.
//
// Each repos.json entry supports:
//   name           - Jenkins job display name (required)
//   git_url        - repo URL; GitHub owner + repo are parsed from it (required
//                    for multibranch entries; informational for pipeline_file
//                    entries)
//   branch         - the ONLY branch to scan/build/deploy (default "main");
//                    for pipeline_file entries this is informational only —
//                    the standalone script itself decides what it checks out
//   credentials_id - Jenkins credential id for this repo's GitHub account
//                    (default "github-creds"); must match an id in casc/jenkins.yaml
//   pipeline_file  - path (relative to /var/jenkins_home/) to an AirOS-owned
//                    Jenkinsfile. When set, creates a STANDALONE Pipeline job
//                    running that script instead of a multibranch job reading
//                    the target repo's own Jenkinsfile. Use this for jobs that
//                    must not touch the app repo (e.g. isolated test deploys).
//   sandbox        - (pipeline_file entries only) run the standalone pipeline
//                    in the Groovy sandbox. Default true. Set false ONLY for
//                    trusted AirOS-owned pipelines that must read Jenkins
//                    internals (e.g. the rollback jobs, which walk the deploy
//                    job's build history to find the previous successful SHA).
//   disabled       - true to skip creating a job for this repo
//
// Multibranch job sources are built with the Jenkins Java API (not
// hand-written XML): a GitHubSCMSource with a BranchDiscoveryTrait plus a
// RegexSCMHeadFilterTrait that restricts discovery to exactly `branch`.
// (Hand-written XML silently drops the source when the filter trait is
// present, so the API is used.)
// ---------------------------------------------------------------------------

def jsonFile = '/var/jenkins_home/repos.json'
def file = new File(jsonFile)

if (!file.exists()) {
    println "repos.json not found at ${jsonFile}. No jobs created."
    return
}

def repos = new JsonSlurper().parseText(file.text)
Jenkins jenkins = Jenkins.get()

// NOTE: all helpers below are Closures (`def name = { ... }`), not script
// methods (`def name(...) { ... }`). Groovy compiles this script's own class
// with a hyphenated name (from the filename), and one script *method*
// calling another script *method* forces Groovy to generate a callsite class
// named "<script-class>$<method>" — an illegal JVM class name when the
// script class name contains hyphens (ClassFormatError at runtime). Closures
// are invoked via Closure.call() instead, which sidesteps that codegen path
// entirely, so keep every cross-helper call going through a closure.

/**
 * Parse the GitHub owner and repository name out of a git URL.
 * Handles HTTPS (https://github.com/owner/repo.git) and SSH
 * (git@github.com:owner/repo.git), with or without ".git".
 * Returns [owner, repository] or [null, null] if it can't be parsed.
 */
def parseOwnerRepo = { gitUrl ->
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
def buildSource = { repoOwner, repository, branch, credentialsId ->
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
def triggerScan = { job ->
    try {
        job.scheduleBuild2(0)
        println "Triggered branch scan for: ${job.name}"
    } catch (Exception e) {
        println "Failed to trigger scan for ${job.name}: ${e.message}"
    }
}

/**
 * Create/update a standalone Pipeline job whose definition is the literal
 * content of an AirOS-owned Jenkinsfile (repo.pipeline_file). Used for jobs
 * that must not modify the target app repo — the script itself is
 * self-contained (its own checkout + triggers), so unlike multibranch jobs
 * there is no SCM source to configure here.
 */
def createStandaloneJob = { jenkinsRef, repo ->
    def jobName = repo.name
    def scriptFile = new File("/var/jenkins_home/${repo.pipeline_file}")
    if (!scriptFile.exists()) {
        println "Skipping ${jobName}: pipeline_file not found at ${scriptFile.path}"
        return
    }

    def job = jenkinsRef.getItem(jobName)
    if (job != null && !(job instanceof WorkflowJob)) {
        println "Skipping ${jobName}: exists but is not a standalone Pipeline job"
        return
    }
    if (job == null) {
        println "Creating standalone Pipeline job: ${jobName} (pipeline_file '${repo.pipeline_file}')"
        job = jenkinsRef.createProject(WorkflowJob, jobName)
    } else {
        println "Updating standalone Pipeline job: ${jobName} (pipeline_file '${repo.pipeline_file}')"
    }

    // Standalone jobs run sandboxed by default. A repos.json entry may set
    // "sandbox": false to run the pipeline trusted — required by the rollback
    // jobs, which read the deploy job's build history via Jenkins' object model
    // (blocked in the sandbox).
    def useSandbox = (repo.sandbox == null) ? true : (repo.sandbox as boolean)
    job.setDescription("CI/CD for ${jobName} (standalone, branch '${repo.branch ?: 'n/a'}', sandbox=${useSandbox})")
    job.setDefinition(new CpsFlowDefinition(scriptFile.text, useSandbox))
    job.save()
    println "Job ready: ${jobName}"
}

/**
 * Create/update a Multibranch Pipeline job that reads the target repo's own
 * Jenkinsfile off exactly one branch.
 */
def createMultibranchJob = { jenkinsRef, repo ->
    def jobName = repo.name
    def branch = repo.branch ?: 'main'
    def credentialsId = repo.credentials_id ?: 'github-creds'

    def (repoOwner, repository) = parseOwnerRepo(repo.git_url)
    if (repoOwner == null) {
        println "Skipping ${jobName}: could not parse owner/repo from git_url '${repo.git_url}'"
        return
    }

    def job = jenkinsRef.getItem(jobName)
    if (job == null) {
        println "Creating Multibranch Pipeline job: ${jobName} (${repoOwner}/${repository}, branch '${branch}', creds '${credentialsId}')"
        job = jenkinsRef.createProject(WorkflowMultiBranchProject, jobName)
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
    job.addTrigger(new PeriodicFolderTrigger("2m"))   // periodic rescan

    job.setSourcesList([ new BranchSource(buildSource(repoOwner, repository, branch, credentialsId)) ])
    job.save()

    // Scan so the branch filter is applied and the branch is discovered now
    triggerScan(job)
    println "Job ready: ${jobName}"
}

// Main loop -----------------------------------------------------------------

repos.each { repo ->
    if (repo.disabled == true) {
        println "Skipping disabled repo: ${repo.name}"
        return
    }

    if (repo.pipeline_file) {
        createStandaloneJob(jenkins, repo)
    } else {
        createMultibranchJob(jenkins, repo)
    }
}

jenkins.save()
println "Job creation complete."
