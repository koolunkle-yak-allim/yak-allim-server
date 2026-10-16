package com.example.yakallim.ocr.model

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrProgressResponse
import com.example.yakallim.ocr.dto.OcrResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest

/**
 * docs/api-contract.md에 문서화된 계약(진행 단계 목록, JSON 필드 이름)이 실제 코드와
 * 어긋나지 않는지 확인한다. 이 테스트가 깨지면 문서와 안드로이드 클라이언트도 함께 갱신해야 한다.
 */
@JsonTest
class ApiContractTest {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun fieldNamesOf(value: Any): List<String> =
        objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(value).fieldNames().asSequence().toList()

    @Test
    @DisplayName("PipelineStep 값 목록이 API 계약 문서와 일치한다")
    fun pipelineStepMatchesDocumentedContract() {
        val documentedSteps = listOf(
            "ACCEPTED",
            "ANALYZING",
            "COMPLETED",
            "FAILED",
            "CANCELLED"
        )

        val actualSteps = PipelineStep.entries.map { it.name }

        assertEquals(documentedSteps, actualSteps)
    }

    @Test
    @DisplayName("SSE progress payload의 필드 이름이 계약과 일치한다 (isFinished는 is 접두사를 유지)")
    fun progressPayloadFieldNames() {
        val payload = OcrProgressResponse(step = PipelineStep.ANALYZING, message = "m", progress = 30, isFinished = false)

        assertEquals(listOf("step", "message", "progress", "isFinished"), fieldNamesOf(payload))
    }

    @Test
    @DisplayName("작업 조회 응답과 분석 결과의 필드 이름이 계약과 일치한다")
    fun jobResponseFieldNames() {
        val medicine = PrescribedMedicine(medicineName = "타이레놀정", dosagePerTake = "1정", dailyFrequency = 3, durationDays = 3)
        val job = OcrJobResponse(
            jobId = "job-1",
            status = JobStatus.COMPLETED,
            result = OcrResult(message = "m", medicines = listOf(medicine))
        )

        assertEquals(listOf("jobId", "status", "result", "error"), fieldNamesOf(job))
        assertEquals(listOf("message", "medicines"), fieldNamesOf(job.result!!))
        assertEquals(listOf("medicineName", "dosagePerTake", "dailyFrequency", "durationDays"), fieldNamesOf(medicine))
    }
}
