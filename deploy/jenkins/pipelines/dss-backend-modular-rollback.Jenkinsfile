// AirOS-owned rollback pipeline for dss-backend-modular PRODUCTION (:3001).
//
// Standalone, MANUALLY-triggered Pipeline job (declared in repos.json via the
// "pipeline_file" field, with "sandbox": false so it may read the deployment
// job's build history through Jenkins' object model).
//
// Rollback TARGET = the PREVIOUS successful build of the *deployment* pipeline
// (multibranch job "dss-backend-modular", branch "release/v1") — i.e. the build
// that was live before the current one. It is read live from Jenkins' build
// history, so it can never be influenced by a rollback run: the rollback is a
// separate job and does not appear in the deploy job's history. The remote
// script then re-points the prod containers at that build's permanent
// SHA-tagged images. Rollback is READ-ONLY — it never writes the
// .deploy/*-image-sha records, so repeated rollbacks always target the same
// previous *deployment* build (never a prior rollback execution).
//
// Recreated on restart by init.groovy.d/create-jobs-from-json.groovy (reads
// this file's content via the "pipeline_file" field in repos.json).

import jenkins.model.Jenkins
import hudson.model.Result
import hudson.plugins.git.util.BuildData
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject

// Git SHA of the Nth-from-latest DISTINCT successful build of a deploy job.
//   skip = 0 -> latest successful deploy (currently live)
//   skip = 1 -> the deploy before it (the rollback target)
// For a multibranch deploy job, `branchName` selects the branch child.
// @NonCPS because it walks Jenkins' run/build model — requires a trusted
// (non-sandboxed) pipeline, hence "sandbox": false in repos.json.
@NonCPS
String successfulDeploySha(String jobFullName, String branchName, int skip) {
    def item = Jenkins.get().getItemByFullName(jobFullName)
    if (item == null) { return null }
    def job = item
    if (item instanceof WorkflowMultiBranchProject) {
        def kids = item.getItems()
        job = kids.find { it.name == branchName } ?: (kids.size() == 1 ? kids[0] : null)
    }
    if (job == null) { return null }
    def shas = []
    for (b in job.getBuilds()) {                       // most-recent-first
        if (b.getResult() != Result.SUCCESS) { continue }
        def bd = b.getAction(BuildData)
        def sha = bd?.getLastBuiltRevision()?.getSha1String()
        if (sha && (shas.isEmpty() || shas[-1] != sha)) { shas << sha }   // dedupe consecutive
    }
    return (shas.size() > skip) ? shas[skip] : null
}

pipeline {
    agent any
    options {
        timestamps()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
    environment {
        DEPLOY_PATH = "${env.DEPLOY_PATH ?: '/home/ec2-user/dss-backend-modular'}"
        DEPLOY_HOST = "${env.DEPLOY_HOST ?: '13.205.88.131'}"
        DEPLOY_USER = "${env.DEPLOY_USER ?: 'ec2-user'}"
        DEPLOY_SSH_CREDENTIAL = "${env.DEPLOY_SSH_CREDENTIAL ?: 'ssh-air-quality'}"
        // The deployment pipeline this rollback shadows (branch release/v1).
        DEPLOY_JOB    = 'dss-backend-modular'
        DEPLOY_BRANCH = 'release/v1'
    }
    stages {
        stage('Resolve rollback target (previous successful deploy)') {
            steps {
                script {
                    def live = successfulDeploySha(env.DEPLOY_JOB, env.DEPLOY_BRANCH, 0)?.take(7)
                    def prev = successfulDeploySha(env.DEPLOY_JOB, env.DEPLOY_BRANCH, 1)
                    if (!prev) {
                        error("No previous successful build of ${env.DEPLOY_JOB} (${env.DEPLOY_BRANCH}) to roll back to. Need at least two distinct successful deploys.")
                    }
                    env.TARGET_SHA = prev.take(7)
                    echo "Deployment pipeline: ${env.DEPLOY_JOB} (${env.DEPLOY_BRANCH})"
                    echo "Currently-live deploy build : ${live}"
                    echo "Rolling PROD back to previous : ${env.TARGET_SHA}"
                }
            }
        }
        stage('Checkout (for the rollback script)') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/main']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/OmKatiyarARF/AirOS.git',
                        credentialsId: 'github-creds']]])
            }
        }
        stage('Rollback dss-backend-modular (:3001)') {
            steps {
                // Piped over SSH stdin so the script cannot be self-modified
                // mid-execution on the remote. TARGET_SHA comes from the deploy
                // job's build history above.
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    sh '''
                        set -e
                        echo "Rolling back ${DEPLOY_PATH} on ${DEPLOY_USER}@${DEPLOY_HOST} to ${TARGET_SHA} over SSH..."
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "DEPLOY_PATH=${DEPLOY_PATH} TARGET_SHA=${TARGET_SHA} bash -s" \
                            < deploy/rollback-dss-backend.sh
                        echo "✅ dss-backend-modular rolled back to ${TARGET_SHA} on ${DEPLOY_HOST}"
                    '''
                }
            }
        }
    }
    post {
        success { echo "✅ dss-backend-modular PROD rollback succeeded (target ${env.TARGET_SHA})" }
        failure { echo "❌ dss-backend-modular PROD rollback failed — check logs above" }
        always  { cleanWs() }
    }
}
