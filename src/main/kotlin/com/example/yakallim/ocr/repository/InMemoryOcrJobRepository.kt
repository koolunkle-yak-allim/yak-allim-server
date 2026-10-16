package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResult
import com.example.yakallim.ocr.model.JobStatus
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Repository
class InMemoryOcrJobRepository(
    private val clock: Clock
) : OcrJobRepository {

    private data class JobRecord(val response: OcrJobResponse, val updatedAt: Instant)

    private val jobRegistry = ConcurrentHashMap<String, JobRecord>()

    override fun registerJob(jobId: String): OcrJobResponse {
        val response = OcrJobResponse(jobId = jobId, status = JobStatus.ACCEPTED)
        jobRegistry[jobId] = JobRecord(response, clock.instant())
        return response
    }

    override fun updateToProcessing(jobId: String): Boolean =
        transitionIfActive(jobId) { it.copy(status = JobStatus.PROCESSING) }

    override fun updateToCompleted(jobId: String, result: OcrResult): Boolean =
        transitionIfActive(jobId) { it.copy(status = JobStatus.COMPLETED, result = result) }

    override fun updateToFailed(jobId: String, errorMessage: String): Boolean =
        transitionIfActive(jobId) { it.copy(status = JobStatus.FAILED, error = errorMessage) }

    override fun updateToCancelled(jobId: String): Boolean =
        transitionIfActive(jobId) { it.copy(status = JobStatus.CANCELLED) }

    override fun getJob(jobId: String): OcrJobResponse? = jobRegistry[jobId]?.response

    override fun isCancelled(jobId: String): Boolean = jobRegistry[jobId]?.response?.status == JobStatus.CANCELLED

    override fun findJobIdsInStatusUpdatedBefore(statuses: Set<JobStatus>, cutoff: Instant): List<String> =
        jobRegistry.entries
            .filter { (_, record) -> record.response.status in statuses && record.updatedAt.isBefore(cutoff) }
            .map { (jobId, _) -> jobId }

    override fun deleteJob(jobId: String) {
        jobRegistry.remove(jobId)
    }

    /** 진행 중인 작업에만 [transform]을 적용한다. 종료된 작업(COMPLETED/FAILED/CANCELLED)은 그대로 둔다. */
    private fun transitionIfActive(jobId: String, transform: (OcrJobResponse) -> OcrJobResponse): Boolean {
        var transitionApplied = false
        jobRegistry.computeIfPresent(jobId) { _, existing ->
            if (existing.response.status in ACTIVE_STATUSES) {
                transitionApplied = true
                JobRecord(transform(existing.response), clock.instant())
            } else {
                existing
            }
        }
        return transitionApplied
    }

    companion object {
        private val ACTIVE_STATUSES = setOf(JobStatus.ACCEPTED, JobStatus.PROCESSING)
    }
}
