package com.example.yakallim.ocr.controller

import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.dto.N8nCallbackRequest
import com.example.yakallim.ocr.dto.N8nCallbackData
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.service.OcrProgressManager
import com.example.yakallim.ocr.service.OcrService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.http.HttpStatus

class OcrWebhookSecretTest {

    private lateinit var ocrService: OcrService
    private lateinit var ocrProgressManager: OcrProgressManager
    private lateinit var controller: OcrController

    @BeforeEach
    fun setUp() {
        ocrService = mock()
        ocrProgressManager = mock()

        val ocrProperties = OcrProperties(
            n8n = OcrProperties.N8n(webhookUrl = "http://localhost:5678/webhook", webhookSecret = "correct-secret")
        )

        controller = OcrController(ocrService, ocrProgressManager, ocrProperties)
    }

    private fun request(jobId: String) = N8nCallbackRequest(jobId = jobId, status = "COMPLETED", data = N8nCallbackData())

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
        verify(ocrService).handleCallback(request("job-1"))
    }
}
