package com.example.yakallim.ocr.controller

import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.dto.N8nCallbackRequest
import com.example.yakallim.ocr.dto.N8nData
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.service.N8nOcrService
import com.example.yakallim.ocr.service.OcrProgressManager
import com.example.yakallim.ocr.service.OcrService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus

class OcrWebhookSecretTest {

    private lateinit var ocrService: OcrService
    private lateinit var ocrProgressManager: OcrProgressManager
    private lateinit var n8nOcrService: N8nOcrService
    private lateinit var n8nOcrServiceProvider: ObjectProvider<N8nOcrService>
    private lateinit var controller: OcrController

    @BeforeEach
    fun setUp() {
        ocrService = mock()
        ocrProgressManager = mock()
        n8nOcrService = mock()
        @Suppress("UNCHECKED_CAST")
        n8nOcrServiceProvider = mock<ObjectProvider<N8nOcrService>>()
        whenever(n8nOcrServiceProvider.ifAvailable).thenReturn(n8nOcrService)

        val ocrProperties = OcrProperties(
            type = "n8n",
            engine = OcrProperties.Engine(
                onnx = OcrProperties.Engine.Onnx(
                    detectionModelPath = "classpath:models/dummy_det.onnx",
                    recognitionModelPath = "classpath:models/dummy_rec.onnx",
                    recognitionDictionaryPath = "classpath:models/korean_dict.txt",
                    detectionThreshold = 0.3f
                )
            ),
            parser = OcrProperties.Parser(
                yOffsetRatio = 0.0104,
                yDeviationThresholdRatio = 0.0391,
                columnSeparatorXRatio = 0.3125,
                medicineMinXRatio = 0.0677,
                medicineMaxXRatio = 0.1354
            ),
            n8n = OcrProperties.N8n(webhookUrl = "http://localhost:5678/webhook", webhookSecret = "correct-secret")
        )

        controller = OcrController(ocrService, ocrProgressManager, n8nOcrServiceProvider, ocrProperties)
    }

    private fun request(jobId: String) = N8nCallbackRequest(jobId = jobId, status = "COMPLETED", data = N8nData())

    @Test
    @DisplayName("웹훅 시크릿이 없으면 401을 던진다")
    fun rejectsMissingSecretWith401() {
        val exception = assertThrows(OcrException.UnauthorizedWebhookException::class.java) {
            controller.callback("job-1", request("job-1"), webhookSecret = null)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    @DisplayName("웹훅 시크릿이 틀리면 401을 던진다")
    fun rejectsWrongSecretWith401() {
        val exception = assertThrows(OcrException.UnauthorizedWebhookException::class.java) {
            controller.callback("job-1", request("job-1"), webhookSecret = "wrong-secret")
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    @DisplayName("웹훅 시크릿이 일치하면 200과 함께 콜백 처리를 위임한다")
    fun acceptsCorrectSecretWith200() {
        val response = controller.callback("job-1", request("job-1"), webhookSecret = "correct-secret")

        assertEquals(HttpStatus.OK, response.statusCode)
        verify(n8nOcrService).handleCallback("job-1", emptyList())
    }
}
