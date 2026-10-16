package com.example.yakallim.ocr.controller

import com.example.yakallim.ocr.infrastructure.N8nOcrClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 클라이언트가 잘못 보낸 요청이 500이 아니라 원인에 맞는 4xx와 ErrorResponse 형식으로 응답하는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
class OcrControllerErrorResponseTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var n8nOcrClient: N8nOcrClient

    @Test
    @DisplayName("file 없이 업로드하면 400을 반환한다")
    fun enqueueWithoutFile_returns400() {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/ocr/enqueue").param("fcmToken", "token"))
            .andExpectError(400, "필수 요청 값이 없습니다: file")
    }

    @Test
    @DisplayName("업로드를 multipart가 아닌 JSON으로 보내면 415를 반환한다")
    fun enqueueWithJson_returns415() {
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/ocr/enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.status").value(415))
    }

    @Test
    @DisplayName("업로드 경로에 GET으로 요청하면 405를 반환한다")
    fun enqueueWithGet_returns405() {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/ocr/enqueue"))
            .andExpectError(405, "지원하지 않는 HTTP 메서드입니다: GET")
    }

    @Test
    @DisplayName("콜백 본문이 JSON 형식이 아니면 400을 반환한다")
    fun callbackWithMalformedJson_returns400() {
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/ocr/n8n/callback/job-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{bad")
        ).andExpectError(400, "요청 본문을 읽을 수 없습니다.")
    }

    @Test
    @DisplayName("콜백 jobId가 비어 있으면 검증 메시지와 함께 400을 반환한다")
    fun callbackWithBlankJobId_returns400() {
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/ocr/n8n/callback/job-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"jobId": ""}""")
        ).andExpectError(400, "jobId는 필수 항목입니다.")
    }

    private fun ResultActions.andExpectError(status: Int, message: String): ResultActions =
        andExpect(status().`is`(status))
            .andExpect(jsonPath("$.status").value(status))
            .andExpect(jsonPath("$.message").value(message))
}
