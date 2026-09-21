package com.example.yakallim.ocr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * docs/api-contract.md에 문서화된 진행 단계 목록이 실제 [PipelineStep] enum과
 * 어긋나지 않는지 확인한다. 이 테스트가 깨지면 문서도 함께 갱신해야 한다.
 */
class ApiContractTest {

    @Test
    @DisplayName("PipelineStep 값 목록이 API 계약 문서와 일치한다")
    fun pipelineStepMatchesDocumentedContract() {
        val documentedSteps = listOf(
            "ACCEPTED",
            "IMAGE_PROCESSING",
            "TEXT_DETECTION",
            "TEXT_RECOGNITION",
            "PARSING",
            "COMPLETED",
            "FAILED"
        )

        val actualSteps = PipelineStep.entries.map { it.name }

        assertEquals(documentedSteps, actualSteps)
    }
}
