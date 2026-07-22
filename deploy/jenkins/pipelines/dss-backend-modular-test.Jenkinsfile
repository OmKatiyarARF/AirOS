// AirOS-owned CI/CD pipeline for the dss-backend-modular TEST environment (port 4000).
//
// Standalone Pipeline job (declared in repos.json via the "pipeline_file"
// field, not a repos.json multibranch job) so it never modifies the app repo
// or the prod pipeline. Polls the app repo's dev branch and, on a new commit,
// SSHes to the AirQuality server and runs the isolated test deploy script:
// separate checkout + separate app image tag (dss-backend-modular-app:test),
// recreating only the dss-test app/worker containers on :4000. Prod
// (dss-backend-modular on :3001, image :latest, branch release/v1) and the
// test DB / ML sidecars are left untouched.
//
// Recreated on rebuild by init.groovy.d/create-jobs-from-json.groovy (reads
// this file's content via the "pipeline_file" field in repos.json).
pipeline {
    agent any
    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
    triggers {
        pollSCM('H/5 * * * *')
    }
    stages {
        stage('Checkout (change detection)') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/dev']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/airawatiitk/dss-backend-modular.git',
                        credentialsId: 'github-creds']]])
                echo "Building test backend for commit ${env.GIT_COMMIT}"
            }
        }
        stage('Deploy test backend (:4000)') {
            steps {
                sshagent(credentials: ['ssh-air-quality']) {
                    sh '''
                        set -e
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            ec2-user@13.205.88.131 \
                            'bash /home/ec2-user/dss-backend-modular-test/deploy.sh dev'
                    '''
                }
            }
        }
    }
    post {
        success { echo "✅ dss-backend-modular-test deployed -> http://13.205.88.131:4000/" }
        failure { echo "❌ dss-backend-modular-test deploy failed — check logs above" }
    }
}
