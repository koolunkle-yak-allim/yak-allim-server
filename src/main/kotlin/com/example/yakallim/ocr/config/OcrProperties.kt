package com.example.yakallim.ocr.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ocr")
data class OcrProperties(
    val n8n: N8n = N8n()
) {
    data class N8n(
        val webhookUrl: String = "",
        val webhookSecret: String = ""
    )
}
