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
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import hudson.plugins.git.GitSCM
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.UserRemoteConfig

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
//   pipeline_file  - path, relative to the ROOT OF THE AirOS REPO, of an
//                    AirOS-owned Jenkinsfile (e.g.
//                    "deploy/jenkins/pipelines/dss-frontend-test.Jenkinsfile").
//                    When set, creates a STANDALONE Pipeline job whose
//                    definition is "Pipeline script from SCM": Jenkins fetches
//                    that file from the AirOS repo on every build, instead of
//                    a multibranch job reading the target repo's own
//                    Jenkinsfile. Use this for jobs that must not touch the app
//                    repo (e.g. isolated test deploys).
//   sandbox        - OBSOLETE, ignored. Kept only so older repos.json entries
//                    still parse. A "Pipeline script from SCM" definition has
//                    no sandbox flag: Jenkins ALWAYS runs SCM-sourced pipelines
//                    inside the Groovy sandbox. No pipeline needs an escape
//                    hatch any more — the rollback jobs read the deploy host's
//                    .deploy/*-sha records rather than Jenkins' build history.
//   disabled       - true to skip creating a job for this repo
//
// Multibranch job sources are built with the Jenkins Java API (not
// hand-written XML): a GitHubSCMSource with a BranchDiscoveryTrait plus a
// RegexSCMHeadFilterTrait that restricts discovery to exactly `branch`.
// (Hand-written XML silently drops the source when the filter trait is
// present, so the API is used.)
//
// Standalone (pipeline_file) jobs point at the AirOS repo itself — its
// git_url / branch / credentials_id are read from the "AirOS" entry in
// repos.json, so there is one source of truth for them. Earlier revisions
// inlined the script text (CpsFlowDefinition) read from the ./pipelines bind
// mount; that snapshot only refreshed on a Jenkins rebuild, so editing a
// pipeline in git left the running job on the stale copy and a `git revert`
// of a bad pipeline had no effect until the next restart. Fetching from SCM
// per build removes that second copy entirely.
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
 * Create/update a standalone Pipeline job that reads an AirOS-owned
 * Jenkinsfile (repo.pipeline_file) straight from the AirOS repo on every
 * build. Used for jobs that must not modify the target app repo — the script
 * is self-contained (its own checkout + triggers), so the only SCM configured
 * here is AirOS itself, purely to fetch the pipeline definition.
 */
def createStandaloneJob = { jenkinsRef, repo, pipelineRepo ->
    def jobName = repo.name
    def scriptPath = repo.pipeline_file

    // Soft pre-flight check against the ./pipelines bind mount. Git is the
    // authoritative source now, so a miss is only a warning — but it catches
    // a fat-fingered scriptPath here at boot instead of at the next build.
    def mounted = new File("/var/jenkins_home/${scriptPath.replaceFirst('^deploy/jenkins/', '')}")
    if (!mounted.exists()) {
        println "WARNING ${jobName}: '${scriptPath}' has no match under the ./pipelines mount " +
                "(looked for ${mounted.path}). Check the path if builds fail to find the script."
    }

    def job = jenkinsRef.getItem(jobName)
    if (job != null && !(job instanceof WorkflowJob)) {
        println "Skipping ${jobName}: exists but is not a standalone Pipeline job"
        return
    }
    if (job == null) {
        println "Creating standalone Pipeline job: ${jobName} (SCM ${pipelineRepo.url} @ ${pipelineRepo.branch}, script '${scriptPath}')"
        job = jenkinsRef.createProject(WorkflowJob, jobName)
    } else {
        println "Updating standalone Pipeline job: ${jobName} (SCM ${pipelineRepo.url} @ ${pipelineRepo.branch}, script '${scriptPath}')"
    }

    job.setDescription("CI/CD for ${jobName} (standalone, target branch '${repo.branch ?: 'n/a'}', pipeline from SCM: ${scriptPath})")

    def scm = new GitSCM(
        [ new UserRemoteConfig(pipelineRepo.url, null, null, pipelineRepo.credentialsId) ],
        [ new BranchSpec("*/${pipelineRepo.branch}") ],
        null,   // browser
        null,   // gitTool
        []      // extensions
    )

    // Lightweight: fetch just this one file via the Git provider instead of
    // cloning AirOS into a workspace. Also keeps the AirOS repo out of the
    // job's polled SCMs, so the pollSCM trigger declared inside the test
    // pipelines still watches only the app repo it checks out.
    def definition = new CpsScmFlowDefinition(scm, scriptPath)
    definition.setLightweight(true)

    // Note there is no per-job sandbox switch to set here: CpsScmFlowDefinition
    // always executes sandboxed, so the old "sandbox": false escape hatch (and
    // the whole-script ScriptApproval.preapprove that went with it) no longer
    // apply. What the @NonCPS rollback helpers need instead is the signature
    // allow-list applied once at the bottom of this script.
    job.setDefinition(definition)

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

// Where standalone jobs fetch their pipeline scripts from: the AirOS repo,
// taken from its own repos.json entry so the URL/branch/credential are
// declared exactly once. Falls back to literals if that entry is ever removed.
def airosEntry = repos.find { it.name == 'AirOS' }
def pipelineRepo = [
    url          : airosEntry?.git_url ?: 'https://github.com/OmKatiyarARF/AirOS.git',
    branch       : airosEntry?.branch ?: 'main',
    credentialsId: airosEntry?.credentials_id ?: 'github-creds'
]
println "Standalone pipeline scripts source: ${pipelineRepo.url} @ ${pipelineRepo.branch} (creds '${pipelineRepo.credentialsId}')"

repos.each { repo ->
    if (repo.disabled == true) {
        println "Skipping disabled repo: ${repo.name}"
        return
    }

    if (repo.pipeline_file) {
        createStandaloneJob(jenkins, repo, pipelineRepo)
    } else {
        createMultibranchJob(jenkins, repo)
    }
}

// No ScriptApproval work is needed here. Every "Pipeline script from SCM" job
// runs inside the Groovy sandbox with no per-job opt-out, and the rollback
// pipelines no longer reach into Jenkins' object model: they read the deploy
// host's own .deploy/*-sha records over SSH instead of walking build history
// with an @NonCPS helper. An earlier revision of this script had to allow-list
// ~14 signatures (Jenkins.get, Job.getBuilds, HistoricalBuild.getResult, ...)
// globally to keep those helpers running; removing the helpers removed the
// need. If a pipeline ever needs a privileged call again, prefer moving that
// logic to the deploy host over widening the sandbox for every pipeline.

jenkins.save()
println "Job creation complete."
