package com.example.yakallim.ocr.model

/**
 * SSE로 전달하는 진행 단계. 서버는 n8n 내부의 세부 진행 상황을 알 수 없으므로,
 * 서버가 실제로 관측할 수 있는 시점(접수, n8n 전달 완료, 종료)만 단계로 둔다.
 */
enum class PipelineStep(val defaultMessage: String, val defaultProgress: Int) {
    ACCEPTED("작업이 대기열에 등록되었습니다.", 5),
    ANALYZING("복약 안내서를 분석하고 있습니다.", 30),
    COMPLETED("분석이 완료되었습니다.", 100),
    FAILED("분석에 실패하였습니다.", 100),
    CANCELLED("작업이 취소되었습니다.", 100);

    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}
