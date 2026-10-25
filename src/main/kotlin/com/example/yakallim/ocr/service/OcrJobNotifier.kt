package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.model.FailureCode
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.springframework.stereotype.Component

/**
 * 작업별 FCM 토큰을 작업 저장소에 붙여 두고, 작업이 끝나면 완료·실패 알림을 보낸 뒤 토큰을 정리한다.
 * 토큰을 저장소에 두므로, 작업을 받은 서버와 끝낸 서버가 달라도(블루-그린 전환) 알림이 간다.
 */
@Component
class OcrJobNotifier(
    private val pushNotificationClient: PushNotificationClient,
    private val ocrJobRepository: OcrJobRepository
) {
    fun register(jobId: String, fcmToken: String?) {
        if (!fcmToken.isNullOrBlank()) {
            ocrJobRepository.saveFcmToken(jobId, fcmToken)
        }
    }

    /** 알림 없이 토큰만 정리한다. 취소됐거나 이미 종료된 작업에 쓴다. */
    fun discard(jobId: String) {
        ocrJobRepository.takeFcmToken(jobId)
    }

    fun notifyCompleted(jobId: String, message: String) {
        val token = ocrJobRepository.takeFcmToken(jobId) ?: return
        pushNotificationClient.notify(
            token = token,
            title = COMPLETED_TITLE,
            body = message,
            data = mapOf("jobId" to jobId, "status" to "COMPLETED", "message" to message)
        )
    }

    fun notifyFailed(jobId: String, message: String, failureCode: FailureCode) {
        val token = ocrJobRepository.takeFcmToken(jobId) ?: return
        pushNotificationClient.notify(
            token = token,
            title = FAILED_TITLE,
            body = message,
            data = mapOf(
                "jobId" to jobId,
                "status" to "FAILED",
                "errorCode" to failureCode.name,
                "message" to message
            )
        )
    }

    companion object {
        private const val COMPLETED_TITLE = "복약 안내서 분석 완료"
        private const val FAILED_TITLE = "복약 안내서 분석 실패"
    }
}
