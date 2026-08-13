// AirOS-owned rollback pipeline for dss-backend-modular PRODUCTION (:3001).
//
// Standalone, MANUALLY-triggered Pipeline job (declared in repos.json via the
// "pipeline_file" field).
//
// Rollback TARGET = the SHA in .deploy/previous-image-sha under DEPLOY_PATH on
// the prod host, written by the deploy stage of the dss-backend-modular repo's
// own Jenkinsfile before it recreates the containers. The remote script then
// re-points the prod containers at that build's permanent SHA-tagged images.
// Rollback is READ-ONLY — it never writes the .deploy/*-image-sha records, so
// repeated rollbacks always resolve to the same target and a rollback can never
// become its own rollback target.
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
        DEPLOY_PATH = "${env.DEPLOY_PATH ?: '/home/ec2-user/dss-backend-modular'}"
        DEPLOY_HOST = "${env.DEPLOY_HOST ?: '13.205.88.131'}"
        DEPLOY_USER = "${env.DEPLOY_USER ?: 'ec2-user'}"
        DEPLOY_SSH_CREDENTIAL = "${env.DEPLOY_SSH_CREDENTIAL ?: 'ssh-air-quality'}"
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
                                    "cat '${DEPLOY_PATH}/.deploy/last-image-sha' 2>/dev/null; \
                                     echo; \
                                     cat '${DEPLOY_PATH}/.deploy/previous-image-sha' 2>/dev/null; \
                                     echo"
                            '''
                        ).trim().split('\n', -1).collect { it.trim() }

                        def live = records.size() > 0 ? records[0] : ''
                        def prev = records.size() > 1 ? records[1] : ''

                        if (!prev) {
                            error("No ${env.DEPLOY_PATH}/.deploy/previous-image-sha on ${env.DEPLOY_HOST} — nothing recorded to roll back to. A deploy must have run at least twice, on two different commits.")
                        }
                        // The deploy only advances previous-image-sha when the
                        // commit actually changes, so equal records mean every
                        // retained deploy was the same commit. Rolling back would
                        // silently redeploy what is already live.
                        if (prev == live) {
                            error("previous-image-sha (${prev}) matches the live deploy — every recorded deploy is the same commit, so there is nothing to roll back to.")
                        }

                        env.TARGET_SHA = prev
                        echo "Deploy records read from : ${env.DEPLOY_USER}@${env.DEPLOY_HOST}:${env.DEPLOY_PATH}/.deploy/"
                        echo "Currently-live image SHA : ${live}"
                        echo "Rolling PROD back to     : ${env.TARGET_SHA}"
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
        stage('Rollback dss-backend-modular (:3001)') {
            steps {
                // Piped over SSH stdin so the script cannot be self-modified
                // mid-execution on the remote. TARGET_SHA comes from the host's
                // own .deploy/previous-image-sha record read above.
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
