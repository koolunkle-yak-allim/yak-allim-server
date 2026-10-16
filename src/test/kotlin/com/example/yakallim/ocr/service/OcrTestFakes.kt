package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.repository.OcrJobRepository
import java.time.Instant

internal class FakeOcrJobRepository : OcrJobRepository {
    var failedCalled = false

    override fun registerJob(jobId: String): OcrJobResponse = OcrJobResponse(jobId = jobId, status = JobStatus.ACCEPTED)
    override fun updateToProcessing(jobId: String): Boolean = true
    override fun updateToCompleted(jobId: String, result: OcrResponse): Boolean = true
    override fun updateToFailed(jobId: String, errorMessage: String): Boolean {
        failedCalled = true
        return true
    }

    override fun updateToCancelled(jobId: String): Boolean = true
    override fun getJob(jobId: String): OcrJobResponse? = null
    override fun isCancelled(jobId: String): Boolean = false
    override fun findJobIdsInStatusUpdatedBefore(statuses: Set<JobStatus>, cutoff: Instant): List<String> = emptyList()
    override fun deleteJob(jobId: String) {}
}

internal class NoOpPushNotificationClient : PushNotificationClient {
    override fun notify(token: String, title: String, body: String, data: Map<String, String>) {}
}
