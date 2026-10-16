package com.example.yakallim.ocr.infrastructure

import com.example.yakallim.ocr.config.OcrProperties
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

/** n8n webhook 요청 형식(주소, 인증 헤더, multipart)과 거부 응답 처리를 검증한다. */
class N8nOcrClientTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: MockRestServiceServer
    private lateinit var client: N8nOcrClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = N8nOcrClient(
            n8nRestClient = builder.build(),
            ocrProperties = OcrProperties(
                n8n = OcrProperties.N8n(webhookUrl = "http://n8n.test/webhook/ocr", webhookSecret = "test-secret")
            )
        )
    }

    private fun image(): Path = Files.write(tempDir.resolve("prescription.jpg"), byteArrayOf(1, 2, 3))

    @Test
    @DisplayName("jobId 쿼리와 인증 헤더를 붙여 이미지를 multipart file 필드로 보낸다")
    fun sendsImageAsMultipartWithJobIdAndSecret() {
        server.expect(requestTo("http://n8n.test/webhook/ocr?jobId=job-1"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-N8N-WEBHOOK-SECRET", "test-secret"))
            .andExpect(header("Content-Type", containsString(MediaType.MULTIPART_FORM_DATA_VALUE)))
            .andExpect(content().string(containsString("name=\"file\"; filename=\"prescription.jpg\"")))
            .andRespond(withSuccess())

        client.send("job-1", image())

        server.verify()
    }

    @Test
    @DisplayName("n8n이 2xx가 아닌 상태로 거부하면 성공으로 넘기지 않고 예외를 던진다")
    fun throws_whenN8nRejectsRequest() {
        server.expect(requestTo("http://n8n.test/webhook/ocr?jobId=job-1"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("Authorization data is wrong!"))

        assertThrows(HttpClientErrorException.Forbidden::class.java) {
            client.send("job-1", image())
        }
    }
}
