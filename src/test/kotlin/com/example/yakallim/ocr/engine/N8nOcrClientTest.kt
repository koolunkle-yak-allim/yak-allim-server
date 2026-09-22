package com.example.yakallim.ocr.engine

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

/** n8n 웹훅 호출이 4xx/5xx로 거부됐을 때, 이를 성공으로 착각하지 않고 작업을 FAILED로 표시하는지 검증한다. */
class N8nOcrClientTest {

    private val ocrProperties = OcrProperties(
        type = "n8n",
        engine = OcrProperties.Engine(
            onnx = OcrProperties.Engine.Onnx(
                detectionModelPath = "",
                recognitionModelPath = "",
                recognitionDictionaryPath = "",
                detectionThreshold = 0.5f
            )
        ),
        parser = OcrProperties.Parser(
            yOffsetRatio = 0.0,
            yDeviationThresholdRatio = 0.0,
            columnSeparatorXRatio = 0.0,
            medicineMinXRatio = 0.0,
            medicineMaxXRatio = 0.0
        ),
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

        val errorMessageCaptor = argumentCaptor<String>()
        verify(ocrJobRepository).updateToFailed(eq("job-1"), errorMessageCaptor.capture())
        assertTrue(errorMessageCaptor.firstValue.contains("403"))

        verify(ocrProgressManager).publishProgress(eq("job-1"), eq(PipelineStep.FAILED), any(), anyOrNull())
        assertTrue(dispatchFailureCalled)

        // 응답을 확인하지 않았다면 여기까지 도달했을 TEXT_RECOGNITION 진행률은 발행되지 않아야 한다.
        verify(ocrProgressManager, never())
            .publishProgress(eq("job-1"), eq(PipelineStep.TEXT_RECOGNITION), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("n8n이 200으로 응답하면 정상적으로 TEXT_RECOGNITION까지 진행한다")
    fun proceedsToTextRecognition_whenN8nAccepts() {
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

        verify(ocrProgressManager).publishProgress(eq("job-2"), eq(PipelineStep.TEXT_RECOGNITION), anyOrNull(), anyOrNull())
        verifyNoInteractions(notifier)
    }
}
