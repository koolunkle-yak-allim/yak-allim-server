package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse
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

    override fun updateToProcessing(jobId: String) {
        updateJobStatus(jobId, JobStatus.PROCESSING)
    }

    override fun updateToCompleted(jobId: String, result: OcrResponse): Boolean {
        var transitionApplied = false
        jobRegistry.computeIfPresent(jobId) { _, existing ->
            if (existing.response.status == JobStatus.ACCEPTED || existing.response.status == JobStatus.PROCESSING) {
                transitionApplied = true
                JobRecord(existing.response.copy(status = JobStatus.COMPLETED, result = result), clock.instant())
            } else {
                transitionApplied = false
                existing
            }
        }
        return transitionApplied
    }

    override fun updateToFailed(jobId: String, errorMessage: String) {
        updateJobStatus(jobId, JobStatus.FAILED, error = errorMessage)
    }

    override fun updateToCancelled(jobId: String) {
        updateJobStatus(jobId, JobStatus.CANCELLED)
    }

    override fun getJob(jobId: String): OcrJobResponse? = jobRegistry[jobId]?.response

    override fun isCancelled(jobId: String): Boolean = jobRegistry[jobId]?.response?.status == JobStatus.CANCELLED

    /** 지정한 상태들 중 하나이면서 마지막 갱신 시각이 [cutoff]보다 오래된 작업의 ID 목록. */
    fun findJobIdsInStatusUpdatedBefore(statuses: Set<JobStatus>, cutoff: Instant): List<String> =
        jobRegistry.entries
            .filter { (_, record) -> record.response.status in statuses && record.updatedAt.isBefore(cutoff) }
            .map { (jobId, _) -> jobId }

    fun deleteJob(jobId: String) {
        jobRegistry.remove(jobId)
    }

    private fun updateJobStatus(
        jobId: String, status: JobStatus, result: OcrResponse? = null, error: String? = null
    ): Boolean {
        var transitionApplied = false
        jobRegistry.compute(jobId) { _, existing ->
            if (existing?.response?.status == JobStatus.CANCELLED && status != JobStatus.CANCELLED) {
                transitionApplied = false
                return@compute existing
            }
            transitionApplied = true
            val updatedResponse = existing?.response?.copy(
                status = status, result = result ?: existing.response.result, error = error
            ) ?: OcrJobResponse(jobId = jobId, status = status, result = result, error = error)
            JobRecord(updatedResponse, clock.instant())
        }
        return transitionApplied
    }
}
