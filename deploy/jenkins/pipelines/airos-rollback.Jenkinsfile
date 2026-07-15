// AirOS-owned rollback pipeline for the Keycloak deploy (P1).
//
// Standalone Pipeline job (declared in repos.json via the "pipeline_file"
// field). Manually triggered only — no polling, no automatic runs. It SSHes to
// the AirOS deploy host and runs deploy/rollback-keycloak.sh, which restores
// the commit recorded in .deploy/previous-sha by the last successful
// deploy-keycloak.sh run and redeploys it.
//
// Recreated on rebuild by init.groovy.d/create-jobs-from-json.groovy (reads
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
        DEPLOY_PATH = "${env.DEPLOY_PATH ?: '/home/ec2-user/AirOS'}"
        DEPLOY_HOST = "${env.DEPLOY_HOST ?: '172.31.30.135'}"
        DEPLOY_USER = "${env.DEPLOY_USER ?: 'ec2-user'}"
        DEPLOY_SSH_KEY = "${env.DEPLOY_SSH_KEY ?: '/var/jenkins_home/.ssh/product-dev.pem'}"
    }
    stages {
        stage('Checkout (for the rollback script)') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/main']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/OmKatiyarARF/AirOS.git',
                        credentialsId: 'github-creds']]])
                echo "Rolling back AirOS Keycloak using rollback-keycloak.sh @ ${env.GIT_COMMIT}"
            }
        }
        stage('Rollback Keycloak') {
            steps {
                // Piped over SSH stdin so the `git reset --hard` inside the
                // script cannot self-modify the script mid-execution.
                sh '''
                    set -e
                    echo "Rolling back ${DEPLOY_PATH} on ${DEPLOY_USER}@${DEPLOY_HOST} over SSH..."
                    ssh -i "${DEPLOY_SSH_KEY}" \
                        -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                        "${DEPLOY_USER}@${DEPLOY_HOST}" \
                        "DEPLOY_PATH=${DEPLOY_PATH} bash -s" \
                        < deploy/rollback-keycloak.sh
                    echo "✅ AirOS rollback complete on ${DEPLOY_HOST}"
                '''
            }
        }
    }
    post {
        success { echo "✅ AirOS Keycloak rollback succeeded" }
        failure { echo "❌ AirOS Keycloak rollback failed — check logs above" }
        always { cleanWs() }
    }
}
