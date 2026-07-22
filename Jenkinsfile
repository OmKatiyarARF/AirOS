pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
    }

    environment {
        REPO_NAME = "${env.REPO_NAME ?: 'AirOS'}"
        DEPLOY_PATH = "${env.DEPLOY_PATH ?: '/home/ec2-user/AirOS'}"
        DEPLOY_TYPE = "${env.DEPLOY_TYPE ?: 'docker'}"
        // J1: deploy over SSH to the host instead of driving the host Docker
        // socket from inside the Jenkins container. The host runs docker compose
        // itself; Jenkins only triggers it. Override per environment as needed.
        DEPLOY_HOST = "${env.DEPLOY_HOST ?: '172.31.30.135'}"
        DEPLOY_USER = "${env.DEPLOY_USER ?: 'ec2-user'}"
        // Credential id in Jenkins' credentials store (see casc/jenkins.yaml),
        // not a raw file path — sshagent injects the key for the ssh calls
        // below without ever writing it to disk or printing it in logs.
        DEPLOY_SSH_CREDENTIAL = "${env.DEPLOY_SSH_CREDENTIAL ?: 'ssh-product-dev'}"
    }

    stages {
        stage('Checkout') {
            steps {
                echo "Building repo: ${REPO_NAME}"
                echo "Branch: ${env.BRANCH_NAME}"
                echo "Commit: ${env.GIT_COMMIT}"
                checkout scm
            }
        }

        stage('Pre-deploy Checks') {
            steps {
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    sh '''
                        set -e
                        echo "Checking Docker on the deploy target (${DEPLOY_USER}@${DEPLOY_HOST}) over SSH..."
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            'set -e; docker --version && docker compose version && (docker network inspect airos-auth >/dev/null 2>&1 || docker network create airos-auth) && echo "pre-deploy OK"'
                    '''
                }
            }
        }

        stage('Deploy Keycloak') {
            // Deploy on any branch that gets built. AirOS's repos.json branch
            // filter guarantees only the designated branch is ever discovered,
            // so this deploys exactly that branch (and never a pull request).
            when {
                not { changeRequest() }
            }
            steps {
                // P1: deploy-keycloak.sh records the previously-deployed commit
                // SHA to .deploy/previous-sha before resetting, so the
                // airos-rollback job can restore it instantly if this deploy
                // breaks. The script is piped over SSH stdin (not run from the
                // host file) so the `git reset --hard` inside it cannot
                // self-modify the script mid-execution.
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    sh '''
                        set -e
                        echo "Deploying ${REPO_NAME} (${BRANCH_NAME}) to ${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_PATH} over SSH..."
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "DEPLOY_PATH=${DEPLOY_PATH} bash -s -- ${BRANCH_NAME}" \
                            < deploy/deploy-keycloak.sh
                        echo "✅ ${REPO_NAME} (${BRANCH_NAME}) deployed on ${DEPLOY_HOST}"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "✅ ${REPO_NAME} deployment succeeded"
        }
        failure {
            echo "❌ ${REPO_NAME} deployment failed — check logs above. Roll back via the airos-rollback job if needed."
        }
        always {
            cleanWs()
        }
    }
}
