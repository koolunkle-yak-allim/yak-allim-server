package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.InMemoryOcrJobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private const val PROCESSING_TIMEOUT_MESSAGE = "처리 시간이 초과되었습니다."

@Component
class OcrJobCleanupScheduler(
    private val ocrJobRepository: InMemoryOcrJobRepository,
    private val ocrProgressManager: OcrProgressManager,
    private val n8nOcrServiceProvider: ObjectProvider<N8nOcrService>,
    private val clock: Clock,
    @param:Value("\${ocr.job-retention-minutes:60}") private val retentionMinutes: Long,
    @param:Value("\${ocr.job-processing-timeout-minutes:15}") private val processingTimeoutMinutes: Long
) {
    private val log = LoggerFactory.getLogger(OcrJobCleanupScheduler::class.java)

    @Scheduled(fixedDelay = 60_000)
    fun cleanup() {
        expireFinishedJobs()
        failTimedOutJobs()
    }

    private fun expireFinishedJobs() {
        val cutoff = clock.instant().minus(Duration.ofMinutes(retentionMinutes))
        val expiredJobIds = ocrJobRepository.findJobIdsInStatusUpdatedBefore(
            setOf(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED), cutoff
        )
        expiredJobIds.forEach { jobId ->
            ocrJobRepository.deleteJob(jobId)
            log.info("Deleted expired OCR job past retention: jobId='{}'", jobId)
        }
    }

    private fun failTimedOutJobs() {
        val cutoff = clock.instant().minus(Duration.ofMinutes(processingTimeoutMinutes))
        val timedOutJobIds = ocrJobRepository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.PROCESSING), cutoff)

        timedOutJobIds.forEach { jobId ->
            ocrJobRepository.updateToFailed(jobId, PROCESSING_TIMEOUT_MESSAGE)
            ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, PROCESSING_TIMEOUT_MESSAGE)
            n8nOcrServiceProvider.ifAvailable?.handleTimeout(jobId, PROCESSING_TIMEOUT_MESSAGE)
            log.warn("OCR job marked FAILED due to processing timeout: jobId='{}'", jobId)
        }
    }
}
