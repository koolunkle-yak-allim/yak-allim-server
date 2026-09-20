package com.example.yakallim.ocr.controller

import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.dto.N8nCallbackRequest
import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.service.N8nOcrService
import com.example.yakallim.ocr.service.OcrProgressManager
import com.example.yakallim.ocr.service.OcrService
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.security.MessageDigest

@RestController
@RequestMapping("/api/v1/ocr")
class OcrController(
    private val ocrService: OcrService,
    private val ocrProgressManager: OcrProgressManager,
    private val n8nOcrServiceProvider: ObjectProvider<N8nOcrService>,
    private val ocrProperties: OcrProperties
) {
    private val log = LoggerFactory.getLogger(OcrController::class.java)

    @PostMapping("/enqueue", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun enqueueJob(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("fcmToken", required = false) fcmToken: String?,
        @RequestParam("delay", required = false) delay: Long?
    ): ResponseEntity<OcrJobResponse> {
        val job = ocrService.enqueueJob(file, fcmToken, delay)
        return ResponseEntity.accepted().body(job)
    }

    @GetMapping("/jobs/{jobId}")
    fun getJob(@PathVariable jobId: String): ResponseEntity<OcrJobResponse> {
        val job = ocrService.getJob(jobId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job)
    }

    @GetMapping("/jobs/{jobId}/progress", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun getJobProgress(
        @PathVariable jobId: String,
        response: HttpServletResponse
    ): SseEmitter {
        response.addHeader("X-Accel-Buffering", "no")
        response.addHeader("Cache-Control", "no-cache")
        return ocrProgressManager.registerEmitter(jobId)
    }

    @PostMapping("/jobs/{jobId}/cancel")
    fun cancelJob(@PathVariable jobId: String): ResponseEntity<Unit> {
        ocrService.cancelJob(jobId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/n8n/callback/{jobId}")
    fun callback(
        @PathVariable jobId: String,
        @Valid @RequestBody request: N8nCallbackRequest,
        @RequestHeader("X-N8N-Secret", required = false) webhookSecret: String?
    ): ResponseEntity<Unit> {
        // Validate webhook secret
        val configuredSecret = ocrProperties.n8n.webhookSecret.trim()
        val receivedSecret = webhookSecret?.trim() ?: ""
        val isSecretValid = MessageDigest.isEqual(
            configuredSecret.toByteArray(),
            receivedSecret.toByteArray()
        )
        if (configuredSecret.isNotBlank() && !isSecretValid) {
            log.warn("Webhook secret verification failed: unauthorized request")
            throw OcrException.UnauthorizedWebhookException()
        }

        // Validate jobId matches between path and body
        if (request.jobId != jobId) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        val n8nOcrService = (ocrService as? N8nOcrService) ?: n8nOcrServiceProvider.ifAvailable
            ?: throw OcrException.IllegalJobStateException("n8n OCR 서비스가 활성화되어 있지 않습니다. 'ocr.type' 속성을 확인해주세요.")
        n8nOcrService.handleCallback(jobId, request.data.medicines)
        return ResponseEntity.ok().build()
    }
}
