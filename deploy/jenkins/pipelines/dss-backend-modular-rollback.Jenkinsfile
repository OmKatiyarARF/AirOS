// AirOS-owned rollback pipeline for dss-backend-modular PROD (Fix 3).
//
// Standalone Pipeline job (declared in repos.json via the "pipeline_file"
// field). Manually triggered only — no polling, no automatic runs. It SSHes
// to the AirQuality server and runs deploy/rollback-dss-backend.sh, which
// restarts the app/worker/sidecar containers at the image SHA recorded in
// .deploy/previous-image-sha by the last successful SHA-tagged deploy.
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
        DEPLOY_PATH = "${env.DEPLOY_PATH ?: '/home/ec2-user/dss-backend-modular'}"
        DEPLOY_HOST = "${env.DEPLOY_HOST ?: '13.205.88.131'}"
        DEPLOY_USER = "${env.DEPLOY_USER ?: 'ec2-user'}"
        DEPLOY_SSH_CREDENTIAL = "${env.DEPLOY_SSH_CREDENTIAL ?: 'ssh-air-quality'}"
    }
    stages {
        stage('Checkout (for the rollback script)') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/main']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/OmKatiyarARF/AirOS.git',
                        credentialsId: 'github-creds']]])
                echo "Rolling back dss-backend-modular using rollback-dss-backend.sh @ ${env.GIT_COMMIT}"
            }
        }
        stage('Rollback dss-backend-modular') {
            steps {
                // Piped over SSH stdin so the script cannot be self-modified
                // mid-execution on the remote.
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    sh '''
                        set -e
                        echo "Rolling back ${DEPLOY_PATH} on ${DEPLOY_USER}@${DEPLOY_HOST} over SSH..."
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "DEPLOY_PATH=${DEPLOY_PATH} bash -s" \
                            < deploy/rollback-dss-backend.sh
                        echo "✅ dss-backend-modular rollback complete on ${DEPLOY_HOST}"
                    '''
                }
            }
        }
    }
    post {
        success { echo "✅ dss-backend-modular rollback succeeded" }
        failure { echo "❌ dss-backend-modular rollback failed — check logs above" }
        always { cleanWs() }
    }
}
