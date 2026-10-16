package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.model.FailureCode
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** 작업별 FCM 토큰을 보관하고, 작업이 끝나면 완료·실패 알림을 보낸 뒤 토큰을 정리한다. */
@Component
class OcrJobNotifier(
    private val pushNotificationClient: PushNotificationClient
) {
    private val fcmTokens = ConcurrentHashMap<String, String>()

    fun register(jobId: String, fcmToken: String?) {
        if (!fcmToken.isNullOrBlank()) {
            fcmTokens[jobId] = fcmToken
        }
    }

    /** 알림 없이 토큰만 정리한다. 취소됐거나 이미 종료된 작업에 쓴다. */
    fun discard(jobId: String) {
        fcmTokens.remove(jobId)
    }

    fun notifyCompleted(jobId: String, message: String) {
        val token = fcmTokens.remove(jobId) ?: return
        pushNotificationClient.notify(
            token = token,
            title = COMPLETED_TITLE,
            body = message,
            data = mapOf("jobId" to jobId, "status" to "COMPLETED", "message" to message)
        )
    }

    fun notifyFailed(jobId: String, message: String, failureCode: FailureCode) {
        val token = fcmTokens.remove(jobId) ?: return
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
