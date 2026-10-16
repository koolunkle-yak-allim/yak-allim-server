package com.example.yakallim.ocr.model

/** 실패 FCM 알림의 errorCode. 클라이언트가 실패 원인을 구분하는 데 쓴다. */
enum class FailureCode {
    /** 서버가 n8n으로 이미지를 보내지 못함 */
    N8N_DISPATCH_FAILED,

    /** n8n 워크플로우가 분석 실패를 콜백으로 알림 */
    N8N_PROCESSING_FAILED,

    /** 처리 시간 안에 n8n 콜백이 오지 않음 */
    OCR_PROCESSING_TIMEOUT
}
