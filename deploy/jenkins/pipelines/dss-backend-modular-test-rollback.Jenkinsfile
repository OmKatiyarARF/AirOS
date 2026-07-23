// AirOS-owned rollback pipeline for dss-backend-modular TEST env (Fix 3).
//
// Standalone Pipeline job (declared in repos.json via the "pipeline_file"
// field). Manually triggered only. SSHes to the AirQuality server and runs
// deploy/rollback-dss-backend-test.sh, which restarts the dss-test app/worker
// (port 4000) at the image SHA recorded in .deploy/previous-image-sha by the
// last SHA-tagged test deploy. Prod (:3001) is never touched.
//
// Recreated on rebuild by init.groovy.d/create-jobs-from-json.groovy.
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
                echo "Rolling back dss-test using rollback-dss-backend-test.sh @ ${env.GIT_COMMIT}"
            }
        }
        stage('Rollback dss-test (:4000)') {
            steps {
                sshagent(credentials: [DEPLOY_SSH_CREDENTIAL]) {
                    sh '''
                        set -e
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "bash -s" \
                            < deploy/rollback-dss-backend-test.sh
                        echo "✅ dss-test rollback complete on ${DEPLOY_HOST}"
                    '''
                }
            }
        }
    }
    post {
        success { echo "✅ dss-backend-modular-test rollback succeeded" }
        failure { echo "❌ dss-backend-modular-test rollback failed — check logs above" }
        always { cleanWs() }
    }
}
