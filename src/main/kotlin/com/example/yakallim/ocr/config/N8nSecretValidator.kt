package com.example.yakallim.ocr.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["ocr.type"], havingValue = "n8n")
class N8nSecretValidator(private val ocrProperties: OcrProperties) {

    @PostConstruct
    fun validate() = require(ocrProperties.n8n.webhookSecret.isNotBlank()) {
        "ocr.type=n8n 에서는 OCR_N8N_WEBHOOK_SECRET 환경 변수가 필요합니다."
    }
}
