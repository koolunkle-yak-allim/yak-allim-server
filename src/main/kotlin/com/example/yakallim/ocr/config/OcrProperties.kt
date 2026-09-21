package com.example.yakallim.ocr.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ocr")
data class OcrProperties(
    val type: String = "local",
    val engine: Engine,
    val parser: Parser,
    val n8n: N8n = N8n()
) {
    data class Engine(
        val onnx: Onnx
    ) {
        data class Onnx(
            val detectionModelPath: String,
            val recognitionModelPath: String,
            val recognitionDictionaryPath: String,
            val detectionThreshold: Float
        )
    }

    data class Parser(
        val yOffsetRatio: Double,
        val yDeviationThresholdRatio: Double,
        val columnSeparatorXRatio: Double,
        val medicineMinXRatio: Double,
        val medicineMaxXRatio: Double
    )

    data class N8n(
        val webhookUrl: String = "",
        val webhookSecret: String = ""
    )
}