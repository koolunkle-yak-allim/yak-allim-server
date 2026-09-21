package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.repository.OcrJobRepository

internal class FakeOcrJobRepository : OcrJobRepository {
    var cancelled = false
    var failedCalled = false
    var cancelledLoggedAsFailure = false
    var lastFailureReason: String? = null

    override fun registerJob(jobId: String): OcrJobResponse = throw NotImplementedError()
    override fun updateToProcessing(jobId: String) {}
    override fun updateToCompleted(jobId: String, result: OcrResponse): Boolean = true
    override fun updateToFailed(jobId: String, errorMessage: String) {
        failedCalled = true
        lastFailureReason = errorMessage
        if (errorMessage.contains("취소")) cancelledLoggedAsFailure = true
    }

    override fun updateToCancelled(jobId: String) {}
    override fun getJob(jobId: String): OcrJobResponse? = null
    override fun isCancelled(jobId: String): Boolean = cancelled
}

internal class NoOpPushNotificationClient : PushNotificationClient {
    override fun notify(token: String, title: String, body: String, data: Map<String, String>) {}
}
