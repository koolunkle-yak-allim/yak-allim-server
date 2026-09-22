pipeline {
    agent any

    options {
        // 배포 파이프라인이 동시에 여러 개 겹쳐 돌면 deploy.sh의 블루-그린 타겟 색상
        // 판정이 같은 시점의 상태를 보고 충돌할 수 있어(무중단 배포 안전성 문제), 직렬화한다.
        disableConcurrentBuilds()
    }

    parameters {
        booleanParam(
            name: 'CLEAN_BUILD',
            defaultValue: false,
            description: '선택 시 기존 빌드 캐시를 삭제하고 클린 빌드를 수행합니다.'
        )
    }

    environment {
        IMAGE_REPOSITORY    = 'yak-allim-backend'
        IMAGE_NAME          = "yak-allim-backend:${env.BUILD_NUMBER}"
        IMAGE_RETENTION_COUNT = '5'
        JENKINS_NODE_COOKIE = 'dontKillMe'
        SLACK_CREDENTIAL_ID = 'slack-bot-token'
        SLACK_CHANNEL       = '#app-deploy-alerts'
        N8N_WEBHOOK_URL     = 'http://yak-allim-n8n:5678/webhook/ocr'
    }

    stages {
        stage('Build') {
            steps {
                script {
                    def cleanOption = params.CLEAN_BUILD ? 'clean' : ''
                    if (isUnix()) {
                        sh 'chmod +x gradlew'
                        sh """
                            docker run --rm \
                                --volumes-from yak-allim-jenkins \
                                -v yak-allim-server-gradle-cache:/root/.gradle \
                                -w "${env.WORKSPACE}" \
                                eclipse-temurin:17-jdk-jammy \
                                sh -c "./gradlew ${cleanOption} bootJar"
                        """
                    } else {
                        bat "gradlew.bat ${cleanOption} bootJar"
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([
                    file(credentialsId: 'firebase-messaging-key', variable: 'FIREBASE_KEY_FILE'),
                    string(credentialsId: 'ocr-n8n-webhook-secret', variable: 'OCR_N8N_WEBHOOK_SECRET')
                ]) {
                    script {
                        if (isUnix()) {
                            sh 'chmod +x scripts/deploy.sh'
                            sh './scripts/deploy.sh'
                        } else {
                            powershell './scripts/deploy.ps1'
                        }
                    }
                }
            }
        }
    }

    // 파이프라인 빌드 결과에 따른 Slack 봇 알림 전송
    post {
        // 빌드 성공 알림
        success {
            script {
                try {
                    def channel = env.SLACK_CHANNEL ?: '#app-deploy-alerts'
                    def credId = env.SLACK_CREDENTIAL_ID ?: 'slack-bot-token'
                    def successMessage = """
                        *:white_check_mark: [SUCCESS] Build & Deploy Completed*
                        • *Job:* `${env.JOB_NAME}`
                        • *Build Number:* #${env.BUILD_NUMBER}
                        • *Duration:* ${currentBuild.durationString}
                        • *Link:* <${env.BUILD_URL}|Open Build> | <${env.BUILD_URL}console|Console Log>
                    """.stripIndent().trim()
                    slackSend botUser: true, color: '#36a64f', channel: channel, tokenCredentialId: credId, message: successMessage
                } catch (Exception e) {
                    echo "Slack 알림 전송 건너뜀 (사유: ${e.message})"
                }
            }
        }

        // 빌드 실패 알림
        failure {
            script {
                try {
                    def channel = env.SLACK_CHANNEL ?: '#app-deploy-alerts'
                    def credId = env.SLACK_CREDENTIAL_ID ?: 'slack-bot-token'
                    def failureMessage = """
                        *:x: [FAILURE] Build & Deploy Failed*
                        • *Job:* `${env.JOB_NAME}`
                        • *Build Number:* #${env.BUILD_NUMBER}
                        • *Duration:* ${currentBuild.durationString}
                        • *Build Link:* <${env.BUILD_URL}|Open Build>
                        • *Failed Console Log:* <${env.BUILD_URL}console|View Logs>

                        *Check Logs:*
                        실패한 빌드의 상세 에러 원인은 위 Console Log 링크에서 확인하실 수 있습니다.
                    """.stripIndent().trim()
                    slackSend botUser: true, color: '#FF0000', channel: channel, tokenCredentialId: credId, message: failureMessage
                } catch (Exception e) {
                    echo "Slack 알림 전송 건너뜀 (사유: ${e.message})"
                }
            }
        }
    }
}