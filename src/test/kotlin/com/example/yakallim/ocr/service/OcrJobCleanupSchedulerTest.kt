package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.repository.InMemoryOcrJobRepository
import com.example.yakallim.ocr.repository.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.time.Duration
import java.time.Instant

class OcrJobCleanupSchedulerTest {

    private lateinit var clock: MutableClock
    private lateinit var ocrJobRepository: InMemoryOcrJobRepository
    private lateinit var ocrProgressManager: OcrProgressManager
    private lateinit var n8nOcrServiceProvider: ObjectProvider<N8nOcrService>

    @BeforeEach
    fun setUp() {
        clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        ocrJobRepository = InMemoryOcrJobRepository(clock)
        ocrProgressManager = OcrProgressManager(ocrJobRepository)
        n8nOcrServiceProvider = mock()
    }

    private fun buildScheduler(retentionMinutes: Long = 60, timeoutMinutes: Long = 15) = OcrJobCleanupScheduler(
        ocrJobRepository = ocrJobRepository,
        ocrProgressManager = ocrProgressManager,
        n8nOcrServiceProvider = n8nOcrServiceProvider,
        clock = clock,
        retentionMinutes = retentionMinutes,
        processingTimeoutMinutes = timeoutMinutes
    )

    @Test
    @DisplayName("보관 기간이 지난 종료 상태 작업은 삭제된다")
    fun deletesFinishedJobsPastRetention() {
        ocrJobRepository.registerJob("job-1")
        ocrJobRepository.updateToFailed("job-1", "오류")
        clock.advanceBy(Duration.ofMinutes(61))

        buildScheduler(retentionMinutes = 60).cleanup()

        assertNull(ocrJobRepository.getJob("job-1"))
    }

    @Test
    @DisplayName("보관 기간이 지나지 않은 종료 상태 작업은 삭제되지 않는다")
    fun keepsFinishedJobsWithinRetention() {
        ocrJobRepository.registerJob("job-1")
        ocrJobRepository.updateToFailed("job-1", "오류")
        clock.advanceBy(Duration.ofMinutes(59))

        buildScheduler(retentionMinutes = 60).cleanup()

        assertEquals(JobStatus.FAILED, ocrJobRepository.getJob("job-1")?.status)
    }

    @Test
    @DisplayName("타임아웃을 넘긴 PROCESSING 작업은 FAILED로 전이된다")
    fun failsTimedOutProcessingJobs() {
        ocrJobRepository.registerJob("job-1")
        ocrJobRepository.updateToProcessing("job-1")
        clock.advanceBy(Duration.ofMinutes(16))

        buildScheduler(timeoutMinutes = 15).cleanup()

        val job = ocrJobRepository.getJob("job-1")
        assertEquals(JobStatus.FAILED, job?.status)
        assertEquals("처리 시간이 초과되었습니다.", job?.error)
    }

    @Test
    @DisplayName("타임아웃을 넘기지 않은 PROCESSING 작업은 그대로 유지된다")
    fun keepsProcessingJobsWithinTimeout() {
        ocrJobRepository.registerJob("job-1")
        ocrJobRepository.updateToProcessing("job-1")
        clock.advanceBy(Duration.ofMinutes(14))

        buildScheduler(timeoutMinutes = 15).cleanup()

        assertEquals(JobStatus.PROCESSING, ocrJobRepository.getJob("job-1")?.status)
    }

    @Test
    @DisplayName("n8n 모드에서는 타임아웃 시 N8nOcrService에 정리를 위임한다")
    fun delegatesTimeoutCleanupToN8nServiceWhenAvailable() {
        val n8nOcrService = mock<N8nOcrService>()
        whenever(n8nOcrServiceProvider.ifAvailable).thenReturn(n8nOcrService)

        ocrJobRepository.registerJob("job-1")
        ocrJobRepository.updateToProcessing("job-1")
        clock.advanceBy(Duration.ofMinutes(16))

        buildScheduler(timeoutMinutes = 15).cleanup()

        verify(n8nOcrService).handleTimeout("job-1", "처리 시간이 초과되었습니다.")
    }
}
