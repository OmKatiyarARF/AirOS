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
                sh '''
                    set -e
                    echo "Checking Docker and required network..."
                    docker --version
                    docker compose --version
                    
                    # Ensure the airos-auth network exists before starting Keycloak
                    if ! docker network inspect airos-auth >/dev/null 2>&1; then
                        echo "Creating airos-auth network..."
                        docker network create airos-auth
                    else
                        echo "Network airos-auth already exists — OK"
                    fi
                '''
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
                    
                    echo "Starting Keycloak platform..."
                    cd deploy
                    docker compose -f keycloak.yaml pull
                    docker compose -f keycloak.yaml up -d
                    
                    # Wait briefly for services to settle, then show status
                    echo "Deployed. Waiting 5 seconds for services to stabilize..."
                    sleep 5
                    docker compose -f keycloak.yaml ps
                    
                    # Check Keycloak readiness. This pipeline runs INSIDE the Jenkins
                    # container, so "localhost" is Jenkins, not the host — and Keycloak
                    # serves /health/ready on its management port 9000 (KC_HEALTH_ENABLED),
                    # which is not published to the host. We probe it by running curl in a
                    # throwaway container that shares the Keycloak container's network
                    # namespace, so localhost:9000 resolves to Keycloak itself.
                    echo "Checking Keycloak health..."
                    MAX_RETRIES=24
                    RETRY_DELAY=5
                    HEALTHY=0
                    for i in $(seq 1 $MAX_RETRIES); do
                        if docker run --rm --network "container:airos-keycloak" \
                               curlimages/curl:8.11.0 \
                               -sf http://localhost:9000/health/ready >/dev/null 2>&1; then
                            echo "Keycloak is healthy."
                            HEALTHY=1
                            break
                        fi
                        echo "Attempt $i/$MAX_RETRIES — Keycloak not ready yet, retrying in ${RETRY_DELAY}s..."
                        sleep $RETRY_DELAY
                    done
                    
                    if [ "$HEALTHY" -ne 1 ]; then
                        echo "ERROR: Keycloak health check failed after $((MAX_RETRIES * RETRY_DELAY)) seconds."
                        exit 1
                    fi
                    
                    # Prune ONLY airos-* images to reclaim space safely
                    docker image prune -f --filter "label=airos-platform" >/dev/null 2>&1 || true
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