package com.example.yakallim.ocr.infrastructure

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.OcrJobRepository
import com.example.yakallim.ocr.service.OcrProgressManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** n8n 웹훅 전송의 성공·거부·취소 처리를 검증한다. */
class N8nOcrClientTest {

    private val ocrProperties = OcrProperties(
        n8n = OcrProperties.N8n(webhookUrl = "http://n8n.test/webhook/ocr", webhookSecret = "test-secret")
    )

    private fun tempImageFile(): File = File.createTempFile("n8n-client-test", ".jpg").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        deleteOnExit()
    }

    @Test
    @DisplayName("n8n이 403으로 거부하면 성공으로 착각하지 않고 작업을 FAILED로 표시한다")
    fun marksJobFailed_whenN8nRejectsWithNonSuccessStatus() {
        val mockEngine = MockEngine { request ->
            respond(
                content = "Authorization data is wrong!",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val httpClient = HttpClient(mockEngine)

        val ocrJobRepository: OcrJobRepository = mock()
        whenever(ocrJobRepository.updateToFailed(any(), any())).thenReturn(true)
        val ocrProgressManager: OcrProgressManager = mock()
        val notifier: PushNotificationClient = mock()

        val client = N8nOcrClient(
            ocrJobRepository = ocrJobRepository,
            ocrProgressManager = ocrProgressManager,
            notifier = notifier,
            ocrProperties = ocrProperties,
            httpClient = httpClient
        )

        var dispatchFailureCalled = false
        client.sendToN8nAsync(
            jobId = "job-1",
            file = tempImageFile(),
            fcmToken = null,
            onDispatchFailure = { dispatchFailureCalled = true }
        )

        // 작업에는 내부 원인(HTTP 403 등)이 아니라 사용자에게 보여줄 메시지가 저장돼야 한다.
        val errorMessageCaptor = argumentCaptor<String>()
        verify(ocrJobRepository).updateToFailed(eq("job-1"), errorMessageCaptor.capture())
        assertFalse(errorMessageCaptor.firstValue.contains("403"))

        verify(ocrProgressManager).publishProgress(eq("job-1"), eq(PipelineStep.FAILED), any(), anyOrNull())
        assertTrue(dispatchFailureCalled)

        // 응답을 확인하지 않았다면 여기까지 도달했을 ANALYZING 진행률은 발행되지 않아야 한다.
        verify(ocrProgressManager, never())
            .publishProgress(eq("job-1"), eq(PipelineStep.ANALYZING), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("n8n이 200으로 응답하면 PROCESSING으로 전이하고 ANALYZING을 발행한다")
    fun proceedsToAnalyzing_whenN8nAccepts() {
        val mockEngine = MockEngine { request ->
            respond(content = "", status = HttpStatusCode.OK)
        }
        val httpClient = HttpClient(mockEngine)

        val ocrJobRepository: OcrJobRepository = mock()
        val ocrProgressManager: OcrProgressManager = mock()
        val notifier: PushNotificationClient = mock()

        val client = N8nOcrClient(
            ocrJobRepository = ocrJobRepository,
            ocrProgressManager = ocrProgressManager,
            notifier = notifier,
            ocrProperties = ocrProperties,
            httpClient = httpClient
        )

        client.sendToN8nAsync(
            jobId = "job-2",
            file = tempImageFile(),
            fcmToken = null,
            onDispatchFailure = {}
        )

        verify(ocrJobRepository).updateToProcessing("job-2")
        verify(ocrProgressManager).publishProgress(eq("job-2"), eq(PipelineStep.ANALYZING), anyOrNull(), anyOrNull())
        verifyNoInteractions(notifier)
    }

    @Test
    @DisplayName("대기 중에 취소된 작업은 n8n으로 전송하지 않고 업로드 파일만 지운다")
    fun skipsDispatch_whenJobCancelledBeforeSending() {
        var requestCount = 0
        val mockEngine = MockEngine { _ ->
            requestCount++
            respond(content = "", status = HttpStatusCode.OK)
        }

        val ocrJobRepository: OcrJobRepository = mock()
        whenever(ocrJobRepository.isCancelled("job-3")).thenReturn(true)
        val ocrProgressManager: OcrProgressManager = mock()

        val client = N8nOcrClient(
            ocrJobRepository = ocrJobRepository,
            ocrProgressManager = ocrProgressManager,
            notifier = mock(),
            ocrProperties = ocrProperties,
            httpClient = HttpClient(mockEngine)
        )
        val file = tempImageFile()

        client.sendToN8nAsync(jobId = "job-3", file = file, fcmToken = null, onDispatchFailure = {})

        assertEquals(0, requestCount)
        verify(ocrJobRepository, never()).updateToProcessing(any())
        verifyNoInteractions(ocrProgressManager)
        assertFalse(file.exists())
    }
}
