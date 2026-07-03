import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

// ---------------------------------------------------------------------------
// Creates/updates STANDALONE Pipeline jobs from *.Jenkinsfile files mounted at
// /var/jenkins_home/pipelines/. Job name = filename without ".Jenkinsfile".
//
// Unlike repos.json (which creates multibranch jobs that run a repo's own
// Jenkinsfile), these jobs hold a self-contained AirOS-owned pipeline — used
// when we must NOT modify the target app repo. Example: dss-frontend-test,
// which polls airawatiitk/dss-frontend master and redeploys the isolated test
// container on :4001 (backend :4000), leaving prod (:3002 -> :3001) untouched.
// ---------------------------------------------------------------------------

def dir = new File('/var/jenkins_home/pipelines')
if (!dir.exists()) {
    println "No /var/jenkins_home/pipelines dir; no standalone jobs to create."
    return
}

Jenkins jenkins = Jenkins.get()

dir.listFiles({ f -> f.name.endsWith('.Jenkinsfile') } as FileFilter)?.each { f ->
    def name = f.name.replaceAll(/\.Jenkinsfile$/, '')
    def script = f.text
    def job = jenkins.getItem(name)

    if (job != null && !(job instanceof WorkflowJob)) {
        println "Skipping ${name}: exists but is not a standalone Pipeline job"
        return
    }
    if (job == null) {
        job = jenkins.createProject(WorkflowJob, name)
        println "Created standalone pipeline job: ${name}"
    } else {
        println "Updating standalone pipeline job: ${name}"
    }
    // Inline (sandboxed) pipeline definition from the mounted script file
    job.setDefinition(new CpsFlowDefinition(script, true))
    job.save()
}

println "Standalone job setup complete."
