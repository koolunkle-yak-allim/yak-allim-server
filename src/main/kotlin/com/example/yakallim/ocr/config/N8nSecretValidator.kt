package com.example.yakallim.ocr.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class N8nSecretValidator(private val ocrProperties: OcrProperties) {

    @PostConstruct
    fun validate() = require(ocrProperties.n8n.webhookSecret.isNotBlank()) {
        "OCR_N8N_WEBHOOK_SECRET 환경 변수가 필요합니다."
    }
}
