package com.example.yakallim.ocr.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "ocr")
data class OcrProperties(
    @field:Valid
    val n8n: N8n = N8n()
) {
    data class N8n(
        val webhookUrl: String = "",
        @field:NotBlank(message = "OCR_N8N_WEBHOOK_SECRET 환경 변수가 필요합니다.")
        val webhookSecret: String = ""
    )
}
