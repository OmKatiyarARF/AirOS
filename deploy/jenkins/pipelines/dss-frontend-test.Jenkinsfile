// AirOS-owned CI/CD pipeline for the dss-frontend TEST environment (port 4001).
//
// This is a STANDALONE Pipeline job (declared in repos.json via the
// "pipeline_file" field, not a repos.json multibranch job) so it never
// modifies the app repo or the prod pipeline. It polls the app repo's dev-fe
// branch and, on a new commit, SSHes to the AirQuality server and runs the
// isolated test deploy script (separate checkout, separate image, :4001,
// backend API -> :4000). Prod (dss-frontend on :3002 -> :3001, branch master)
// is untouched.
//
// Recreated on rebuild by init.groovy.d/create-jobs-from-json.groovy (reads
// this file's content via the "pipeline_file" field in repos.json).
pipeline {
    agent any
    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
    triggers {
        pollSCM('H/5 * * * *')
    }
    stages {
        stage('Checkout (change detection)') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/dev-fe']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/airawatiitk/dss-frontend.git',
                        credentialsId: 'github-creds']]])
                echo "Building test frontend for commit ${env.GIT_COMMIT}"
            }
        }
        stage('Deploy test frontend (:4001 -> API :4000)') {
            steps {
                sh '''
                    set -e
                    ssh -i /var/jenkins_home/.ssh/air-quality.pem \
                        -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                        ec2-user@13.205.88.131 \
                        'bash /home/ec2-user/dss-frontend-test/deploy.sh dev-fe'
                '''
            }
        }
    }
    post {
        success { echo "✅ dss-frontend-test deployed -> http://13.205.88.131:4001/dss/" }
        failure { echo "❌ dss-frontend-test deploy failed — check logs above" }
    }
}
