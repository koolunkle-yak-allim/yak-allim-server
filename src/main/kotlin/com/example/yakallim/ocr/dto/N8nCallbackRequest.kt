package com.example.yakallim.ocr.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

data class N8nCallbackRequest(
    @field:NotBlank(message = "jobId는 필수 항목입니다.")
    val jobId: String?,
    /** `FAILED`이면 n8n 워크플로우가 분석에 실패했다는 뜻이다. 그 외 값(또는 생략)은 성공으로 처리한다. */
    val status: String? = null,
    /** 실패 시 n8n이 남기는 원인. 로그에만 남기고 클라이언트에는 노출하지 않는다. */
    val error: String? = null,
    @field:Valid
    val data: N8nData = N8nData()
) {
    val isFailure: Boolean
        get() = status.equals(STATUS_FAILED, ignoreCase = true)

    companion object {
        const val STATUS_FAILED = "FAILED"
    }
}
