package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.model.FailureCode
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private const val PROCESSING_TIMEOUT_MESSAGE = "처리 시간이 초과되었습니다."

@Component
class OcrJobCleanupScheduler(
    private val ocrJobRepository: OcrJobRepository,
    private val ocrProgressManager: OcrProgressManager,
    private val ocrJobNotifier: OcrJobNotifier,
    private val clock: Clock,
    @param:Value("\${ocr.job-retention-minutes:60}") private val retentionMinutes: Long,
    @param:Value("\${ocr.job-processing-timeout-minutes:15}") private val processingTimeoutMinutes: Long,
    @param:Value("\${ocr.job-accepted-timeout-minutes:5}") private val acceptedTimeoutMinutes: Long
) {
    private val log = LoggerFactory.getLogger(OcrJobCleanupScheduler::class.java)

    @Scheduled(fixedDelay = 60_000)
    fun cleanup() {
        expireFinishedJobs()
        // n8n 콜백이 오지 않은 작업
        failTimedOutJobs(JobStatus.PROCESSING, processingTimeoutMinutes)
        // n8n으로 보내기 전에 서버가 강제로 내려가 대기열에서 사라진 작업
        failTimedOutJobs(JobStatus.ACCEPTED, acceptedTimeoutMinutes)
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

    private fun failTimedOutJobs(status: JobStatus, timeoutMinutes: Long) {
        val cutoff = clock.instant().minus(Duration.ofMinutes(timeoutMinutes))
        val timedOutJobIds = ocrJobRepository.findJobIdsInStatusUpdatedBefore(setOf(status), cutoff)

        timedOutJobIds.forEach { jobId ->
            // 조회와 전이 사이에 콜백이 도착했을 수 있으므로, 실제로 FAILED로 전이된 경우에만 알린다.
            if (!ocrJobRepository.updateToFailed(jobId, PROCESSING_TIMEOUT_MESSAGE)) return@forEach
            ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, PROCESSING_TIMEOUT_MESSAGE)
            ocrJobNotifier.notifyFailed(jobId, PROCESSING_TIMEOUT_MESSAGE, FailureCode.OCR_PROCESSING_TIMEOUT)
            log.warn("OCR job marked FAILED due to timeout in {}: jobId='{}'", status, jobId)
        }
    }
}
