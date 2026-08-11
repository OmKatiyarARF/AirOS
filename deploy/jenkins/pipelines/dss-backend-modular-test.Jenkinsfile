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
    environment {
        // Secret text credential holding the Teams "Deployment - Jenkins_GA"
        // channel webhook URL. Never log ${TEAMS_WEBHOOK_URL} directly —
        // Jenkins masks it in console output because it's bound via
        // credentials(), but only office365ConnectorSend's webhookUrl param
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
                // a future unrelated failure notification below. Clear them
                // every run before anything can fail.
                sh 'rm -f npm-ci.log npm-test.log deploy.log'
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/dev']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/airawatiitk/dss-backend-modular.git',
                        credentialsId: 'github-creds']]])
                echo "Building test backend for commit ${env.GIT_COMMIT}"
            }
        }
        stage('Test (gate)') {
            steps {
                // Fix 2 — tests must pass before the test-env deploy is allowed.
                // Same SQLite/in-memory suite as prod; exits non-zero on failure
                // and stops the pipeline before the SSH deploy below. Output is
                // also teed to a file (in addition to the normal live Jenkins
                // console) so the failure notification below can quote the
                // last few lines of the actual error in Teams. pipefail makes
                // `set -e` see npm's real exit code through the tee pipe.
                sh '''#!/bin/bash
                    set -eo pipefail
                    npm ci 2>&1 | tee npm-ci.log
                    npm test 2>&1 | tee npm-test.log
                '''
            }
        }
        stage('Deploy test backend (:4000)') {
            steps {
                sshagent(credentials: ['ssh-air-quality']) {
                    sh '''#!/bin/bash
                        set -eo pipefail
                        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 \
                            ec2-user@13.205.88.131 \
                            'bash /home/ec2-user/dss-backend-modular-test/deploy.sh dev' 2>&1 | tee deploy.log
                    '''
                }
            }
        }
    }
    post {
        success {
            echo "✅ dss-backend-modular-test deployed -> http://13.205.88.131:4000/"
            office365ConnectorSend(
                webhookUrl: env.TEAMS_WEBHOOK_URL,
                status: 'Success',
                color: '00FF00',
                message: "✅ **${env.JOB_NAME}** build #${env.BUILD_NUMBER} succeeded — deployed to http://13.205.88.131:4000/ ([view build](${env.BUILD_URL}))"
            )
        }
        failure {
            script {
                // Pick whichever log belongs to the stage that actually ran
                // last — deploy.log only exists if Test (gate) already
                // passed, so it wins over the (successful) test log; same
                // logic for npm-test.log vs npm-ci.log. Strip ANSI color
                // codes (Node/TypeORM logs are colored) so Teams shows plain
                // readable text instead of escape-sequence garbage.
                //
                // tests/run-all.js always prints the FULL pass/fail roll call
                // in a fixed suite order, so a passing test from a later
                // suite can easily end up as the very last line even though
                // an earlier suite failed — a blind `tail` then shows nothing
                // useful (confirmed: build #51 only showed trailing ✓ lines,
                // no sign of the actual Geography failure). Grep out just the
                // failed lines (✗) plus the "Passed: X, Failed: Y" summary
                // instead, and only fall back to a plain tail for logs that
                // aren't in this ✓/✗ format (e.g. raw npm ci or SSH errors).
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
                echo "❌ dss-backend-modular-test deploy failed — check logs above"
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
