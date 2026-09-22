package com.example.yakallim.ocr.engine

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.exception.OcrErrorMessageResolver
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.OcrJobRepository
import com.example.yakallim.ocr.service.OcrProgressManager
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.io.File

@Component
class N8nOcrClient(
    private val ocrJobRepository: OcrJobRepository,
    private val ocrProgressManager: OcrProgressManager,
    @param:Qualifier("FCM_CLIENT") private val notifier: PushNotificationClient,
    private val ocrProperties: OcrProperties,
    @param:Qualifier("n8nKtorClient") private val httpClient: HttpClient
) {
    private val log = LoggerFactory.getLogger(N8nOcrClient::class.java)

    @Async("ocrTaskExecutor")
    fun sendToN8nAsync(jobId: String, file: File, fcmToken: String?, onDispatchFailure: (String) -> Unit) {
        runBlocking {
            try {
                ocrProgressManager.publishProgress(jobId, PipelineStep.IMAGE_PROCESSING)

                val uri = UriComponentsBuilder.fromUriString(ocrProperties.n8n.webhookUrl)
                    .queryParam("jobId", jobId)
                    .build()
                    .toUriString()

                ocrProgressManager.publishProgress(jobId, PipelineStep.TEXT_DETECTION)

                val response = httpClient.post(uri) {
                    if (ocrProperties.n8n.webhookSecret.isNotBlank()) {
                        header("X-N8N-WEBHOOK-SECRET", ocrProperties.n8n.webhookSecret)
                    }
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = file.readBytes(),
                                    headers = Headers.build {
                                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                    }
                                )
                            }
                        )
                    )
                }

                // Ktor의 기본 HttpClient는 4xx/5xx 응답에도 예외를 던지지 않으므로,
                // 상태 코드를 직접 확인하지 않으면 n8n이 요청을 거부해도 성공한 것처럼 넘어간다.
                if (!response.status.isSuccess()) {
                    error("n8n webhook 요청이 거부되었습니다: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
                }

                ocrProgressManager.publishProgress(jobId, PipelineStep.TEXT_RECOGNITION)
            } catch (e: Exception) {
                log.error("Failed to send image to n8n", e)
                val rawErrorMessage = e.message ?: "Failed to connect to n8n"
                val userFacingMessage = OcrErrorMessageResolver.resolve(e)
                ocrJobRepository.updateToFailed(jobId, rawErrorMessage)
                ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, userFacingMessage)

                onDispatchFailure(jobId)

                if (!fcmToken.isNullOrEmpty()) {
                    notifier.notify(
                        token = fcmToken,
                        title = "복약 안내서 분석 실패",
                        body = userFacingMessage,
                        data = mapOf(
                            "jobId" to jobId,
                            "status" to "FAILED",
                            "errorCode" to "N8N_DISPATCH_FAILED",
                            "message" to userFacingMessage
                        )
                    )
                }
            } finally {
                runCatching { file.delete() }
                    .onFailure { log.warn("Failed to delete uploaded prescription image: {}", file.absolutePath, it) }
            }
        }
    }
}
