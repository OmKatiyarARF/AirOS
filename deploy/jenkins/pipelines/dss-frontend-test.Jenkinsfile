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
    environment {
        // Secret text credential holding the Teams "Deployment - Jenkins_GA"
        // channel webhook URL. Only office365ConnectorSend's webhookUrl param
        // should ever reference it.
        TEAMS_WEBHOOK_URL = credentials('teams-jenkins-ga-webhook')
    }
    triggers {
        pollSCM('H/5 * * * *')
    }
    stages {
        stage('Checkout (change detection)') {
            steps {
                // Workspace persists across builds (no cleanWs() here) so a
                // leftover log from a PAST build could otherwise be quoted by
                // a future unrelated failure notification below.
                sh 'rm -f deploy.log'
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
                sshagent(credentials: ['ssh-air-quality']) {
                    sh '''#!/bin/bash
                        set -eo pipefail
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            ec2-user@13.205.88.131 \
                            'bash /home/ec2-user/dss-frontend-test/deploy.sh dev-fe' 2>&1 | tee deploy.log
                    '''
                }
            }
        }
    }
    post {
        success {
            echo "✅ dss-frontend-test deployed -> http://13.205.88.131:4001/dss/"
            office365ConnectorSend(
                webhookUrl: env.TEAMS_WEBHOOK_URL,
                status: 'Success',
                color: '00FF00',
                message: "✅ **${env.JOB_NAME}** build #${env.BUILD_NUMBER} succeeded — deployed to http://13.205.88.131:4001/dss/ ([view build](${env.BUILD_URL}))"
            )
        }
        failure {
            script {
                def logTail = sh(
                    script: '''
                        RAW=$(cat deploy.log 2>/dev/null || echo "No captured log for this stage — check the Jenkins console.")
                        printf '%s\\n' "$RAW" | sed -r "s/\\x1B\\[[0-9;]*[a-zA-Z]//g" | tail -n 25
                    ''',
                    returnStdout: true
                ).trim()
                echo "❌ dss-frontend-test deploy failed — check logs above"
                office365ConnectorSend(
                    webhookUrl: env.TEAMS_WEBHOOK_URL,
                    status: 'Failure',
                    color: 'FF0000',
                    message: "❌ **${env.JOB_NAME}** build #${env.BUILD_NUMBER} failed ([view build](${env.BUILD_URL}))\n\nLast lines of log:\n```\n${logTail}\n```"
                )
            }
        }
    }
}
