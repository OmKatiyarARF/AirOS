// AirOS-owned rollback pipeline for hudd-dashboard TEST env (:8766).
//
// Standalone, MANUALLY-triggered Pipeline job (declared in repos.json via the
// "pipeline_file" field).
//
// Rollback TARGET = the full commit SHA in .deploy/previous-sha under
// DEPLOY_PATH on the test host, written by the Deploy stage of the
// hudd-dashboard repo's own Jenkinsfile before it resets HEAD.
//
// hudd-dashboard has no Docker image to re-tag — the remote script instead
// checks out that commit directly, rebuilds, and restarts the PM2 test
// process. CODE ONLY: no database/Prisma migration is reversed.
//
// This used to derive the target by walking Jenkins' own build history with an
// @NonCPS helper (Jenkins.get().getItemByFullName(...), job.getBuilds(), ...).
// That coupled rollback to Jenkins' retained build records — which expire under
// the 20-build logRotator and vanish entirely if jenkins_home is lost — and it
// only worked in a non-sandboxed pipeline, which "Pipeline script from SCM"
// cannot provide. Reading the deploy host's own record removes both problems:
// the source of truth now sits next to the thing being rolled back.
//
// Recreated on restart by init.groovy.d/create-jobs-from-json.groovy (reads
// this file's content via the "pipeline_file" field in repos.json).

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
        DEPLOY_HOST = "${env.DEPLOY_HOST ?: '13.203.18.97'}"
        DEPLOY_USER = "${env.DEPLOY_USER ?: 'ec2-user'}"
        DEPLOY_SSH_CREDENTIAL = "${env.DEPLOY_SSH_CREDENTIAL ?: 'ssh-product-dev'}"
        // Checkout the deploy resets and the rollback script re-checks-out.
        // Must match DEPLOY_PATH in the hudd-dashboard repo's Jenkinsfile.
        DEPLOY_PATH = "${env.DEPLOY_PATH ?: '/home/ec2-user/dev/hudd-dashboard'}"
    }
    stages {
        stage('Resolve rollback target (previous successful deploy)') {
            steps {
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    script {
                        // Read both records in one SSH round trip. Missing files
                        // print as empty lines rather than failing, so the checks
                        // below can explain what is actually wrong.
                        def records = sh(
                            returnStdout: true,
                            script: '''
                                ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                                    "${DEPLOY_USER}@${DEPLOY_HOST}" \
                                    "cat '${DEPLOY_PATH}/.deploy/last-sha' 2>/dev/null; \
                                     echo; \
                                     cat '${DEPLOY_PATH}/.deploy/previous-sha' 2>/dev/null; \
                                     echo"
                            '''
                        ).trim().split('\n', -1).collect { it.trim() }

                        def live = records.size() > 0 ? records[0] : ''
                        def prev = records.size() > 1 ? records[1] : ''

                        if (!prev) {
                            error("No ${env.DEPLOY_PATH}/.deploy/previous-sha on ${env.DEPLOY_HOST} — nothing recorded to roll back to. The hudd-dashboard deploy must have run at least twice, on two different commits, since SHA recording was added to its Jenkinsfile.")
                        }
                        // The deploy only advances previous-sha when the commit
                        // actually changes, so equal records mean every recorded
                        // deploy was the same commit. Rolling back would silently
                        // redeploy what is already live.
                        if (prev == live) {
                            error("previous-sha (${prev.take(7)}) matches the live deploy — every recorded deploy is the same commit, so there is nothing to roll back to.")
                        }

                        env.TARGET_SHA = prev
                        echo "Deploy records read from : ${env.DEPLOY_USER}@${env.DEPLOY_HOST}:${env.DEPLOY_PATH}/.deploy/"
                        echo "Currently-live commit    : ${live.take(7)}"
                        echo "Rolling TEST back to     : ${prev.take(7)}"
                    }
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
        stage('Rollback hudd-dashboard-test (:8766)') {
            steps {
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    sh '''
                        set -e
                        echo "Rolling back hudd-dashboard-test on ${DEPLOY_USER}@${DEPLOY_HOST} to ${TARGET_SHA} over SSH..."
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "TARGET_SHA=${TARGET_SHA} bash -s" \
                            < deploy/rollback-hudd-dashboard-test.sh
                        echo "hudd-dashboard-test rolled back to ${TARGET_SHA} on ${DEPLOY_HOST}"
                    '''
                }
            }
        }
    }
    post {
        success { echo "hudd-dashboard-test rollback succeeded (target ${env.TARGET_SHA})" }
        failure { echo "hudd-dashboard-test rollback failed - check logs above" }
        always  { cleanWs() }
    }
}
