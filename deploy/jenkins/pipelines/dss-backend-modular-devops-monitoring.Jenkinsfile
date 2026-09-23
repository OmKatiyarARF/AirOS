// AirOS-owned CI/CD pipeline for the dss-backend-modular DEVOPS-MONITORING
// PREVIEW environment (port 4004).
//
// TEMPORARY: exists to verify this branch's Prometheus /metrics work
// end-to-end via the central Grafana stack before merging. Unlike
// dss-backend-modular-test, this is manually triggered only (no pollSCM) —
// it is not meant to auto-redeploy on every push while short-lived. Once
// the branch owner confirms pass/fail, tear the environment down (see the
// teardown comment at the bottom of deploy-dss-backend-devops-monitoring.sh)
// and remove this job (delete this file + its repos.json entry, revert the
// AirOS commit that added them).
//
// Standalone Pipeline job (declared in repos.json via the "pipeline_file"
// field), fully isolated from prod (:3001) and dss-test (:4000): separate
// checkout, separate image name (dss-backend-modular-app-devops-monitoring),
// separate Postgres/Redis/ML sidecars, recreating only the
// dss-devops-monitoring project on :4004.
//
// Recreated on rebuild by init.groovy.d/create-jobs-from-json.groovy (reads
// this file's content via the "pipeline_file" field in repos.json).
pipeline {
    agent any
    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
    environment {
        TEAMS_WEBHOOK_URL = credentials('teams-jenkins-ga-webhook')
    }
    stages {
        stage('Checkout (change detection)') {
            steps {
                sh 'rm -f npm-ci.log npm-test.log deploy.log'
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/feature/devops-monitoring']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/airawatiitk/dss-backend-modular.git',
                        credentialsId: 'github-creds']]])
                echo "Building devops-monitoring preview for commit ${env.GIT_COMMIT}"
            }
        }
        stage('Test (gate)') {
            steps {
                sh '''#!/bin/bash
                    set -eo pipefail
                    npm ci 2>&1 | tee npm-ci.log
                    npm test 2>&1 | tee npm-test.log
                '''
            }
        }
        stage('Deploy devops-monitoring preview (:4004)') {
            steps {
                dir('.airos') {
                    checkout([$class: 'GitSCM',
                        branches: [[name: '*/main']],
                        userRemoteConfigs: [[
                            url: 'https://github.com/OmKatiyarARF/AirOS.git',
                            credentialsId: 'github-creds']]])
                }
                sshagent(credentials: ['ssh-air-quality']) {
                    sh '''#!/bin/bash
                        set -eo pipefail
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            ec2-user@13.205.88.131 \
                            'bash -s -- feature/devops-monitoring' \
                            < .airos/deploy/deploy-dss-backend-devops-monitoring.sh 2>&1 | tee deploy.log
                    '''
                }
            }
        }
    }
    post {
        success {
            echo "✅ dss-backend-modular-devops-monitoring deployed -> http://13.205.88.131:4004/"
            office365ConnectorSend(
                webhookUrl: env.TEAMS_WEBHOOK_URL,
                status: 'Success',
                color: '00FF00',
                message: "✅ **${env.JOB_NAME}** build #${env.BUILD_NUMBER} succeeded — deployed to http://13.205.88.131:4004/ (TEMPORARY preview — [view build](${env.BUILD_URL}))"
            )
        }
        failure {
            script {
                def logTail = sh(
                    script: '''
                        RAW=$(cat deploy.log 2>/dev/null || cat npm-test.log 2>/dev/null || cat npm-ci.log 2>/dev/null || echo "No captured log for this stage — check the Jenkins console.")
                        CLEAN=$(printf '%s\\n' "$RAW" | sed -r "s/\\x1B\\[[0-9;]*[a-zA-Z]//g")
                        MATCHES=$(printf '%s\\n' "$CLEAN" | grep -E "✗|Passed:")
                        if [ -n "$MATCHES" ]; then
                            printf '%s\\n' "$MATCHES" | head -n 40
                        else
                            printf '%s\\n' "$CLEAN" | tail -n 25
                        fi
                    ''',
                    returnStdout: true
                ).trim()
                echo "❌ dss-backend-modular-devops-monitoring deploy failed — check logs above"
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
