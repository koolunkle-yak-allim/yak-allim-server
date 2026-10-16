package com.example.yakallim.ocr.infrastructure

import com.example.yakallim.ocr.config.OcrProperties
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.nio.file.Path

/** 처방전 이미지를 n8n OCR 워크플로우 webhook으로 보낸다. 분석 결과는 n8n이 콜백으로 따로 보낸다. */
@Component
class N8nOcrClient(
    private val n8nRestClient: RestClient,
    private val ocrProperties: OcrProperties
) {

    /** n8n이 2xx가 아닌 상태로 응답하거나 연결에 실패하면 [org.springframework.web.client.RestClientException]을 던진다. */
    fun send(jobId: String, image: Path) {
        val uri = UriComponentsBuilder.fromUriString(ocrProperties.n8n.webhookUrl)
            .queryParam("jobId", jobId)
            .build()
            .toUri()

        val imagePart = HttpEntity(
            FileSystemResource(image),
            HttpHeaders().apply { contentType = MediaType.APPLICATION_OCTET_STREAM }
        )
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", imagePart) }

        n8nRestClient.post()
            .uri(uri)
            .header(WEBHOOK_SECRET_HEADER, ocrProperties.n8n.webhookSecret)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .toBodilessEntity()
    }

    companion object {
        private const val WEBHOOK_SECRET_HEADER = "X-N8N-WEBHOOK-SECRET"
    }
}
