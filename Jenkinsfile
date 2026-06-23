pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
    }

    environment {
        REPO_NAME = "${env.REPO_NAME}"
        DEPLOY_PATH = "${env.DEPLOY_PATH}"
        DEPLOY_TYPE = "${env.DEPLOY_TYPE}"
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

        stage('Deploy Keycloak') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    set -e
                    echo "Deploying ${REPO_NAME} at ${DEPLOY_PATH}..."
                    cd ${DEPLOY_PATH}
                    git fetch origin main
                    git reset --hard origin/main
                    cd deploy
                    docker compose -f keycloak.yaml up -d --build
                    docker system prune -f
                '''
            }
        }
    }

    post {
        success {
            echo "✅ ${REPO_NAME} deployment succeeded"
        }
        failure {
            echo "❌ ${REPO_NAME} deployment failed — check logs above"
        }
        always {
            cleanWs()
        }
    }
}