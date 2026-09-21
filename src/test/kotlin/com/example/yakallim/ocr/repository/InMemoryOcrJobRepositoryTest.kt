package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.model.JobStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryOcrJobRepositoryTest {

    private lateinit var clock: MutableClock
    private lateinit var repository: InMemoryOcrJobRepository

    @BeforeEach
    fun setUp() {
        clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        repository = InMemoryOcrJobRepository(clock)
    }

    @Test
    @DisplayName("갱신 시각이 cutoff 이전인 지정 상태의 작업만 조회된다")
    fun findsJobsOlderThanCutoffInGivenStatuses() {
        repository.registerJob("job-1")
        repository.updateToFailed("job-1", "오류")

        clock.advanceBy(Duration.ofMinutes(70))
        val cutoff = clock.instant().minus(Duration.ofMinutes(60))

        val expired = repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.FAILED), cutoff)

        assertEquals(listOf("job-1"), expired)
    }

    @Test
    @DisplayName("갱신 시각이 cutoff 이후인 작업은 조회되지 않는다")
    fun doesNotFindJobsNewerThanCutoff() {
        repository.registerJob("job-1")
        repository.updateToFailed("job-1", "오류")

        val cutoff = clock.instant().minus(Duration.ofMinutes(60))

        val expired = repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.FAILED), cutoff)

        assertTrue(expired.isEmpty())
    }

    @Test
    @DisplayName("deleteJob으로 삭제된 작업은 더 이상 조회되지 않는다")
    fun deleteJobRemovesTheJob() {
        repository.registerJob("job-1")

        repository.deleteJob("job-1")

        assertNull(repository.getJob("job-1"))
    }

    @Test
    @DisplayName("상태가 갱신될 때마다 updatedAt 기준 조회 결과가 최신 상태를 반영한다")
    fun updatingStatusRefreshesUpdatedAt() {
        repository.registerJob("job-1")
        clock.advanceBy(Duration.ofMinutes(70))
        val cutoff = clock.instant().minus(Duration.ofMinutes(60))

        // ACCEPTED 상태로 60분 이상 지났지만, 지금 PROCESSING으로 갱신되면 cutoff 이전으로 잡히지 않아야 한다.
        repository.updateToProcessing("job-1")

        val staleAccepted = repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.ACCEPTED), cutoff)
        val staleProcessing = repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.PROCESSING), cutoff)

        assertTrue(staleAccepted.isEmpty())
        assertTrue(staleProcessing.isEmpty())
    }

    @Test
    @DisplayName("취소된 작업은 이후 처리중/실패로 전이되지 않는다 (취소 우선)")
    fun cancelledJobCannotTransitionToProcessingOrFailed() {
        repository.registerJob("job-1")
        repository.updateToCancelled("job-1")

        repository.updateToProcessing("job-1")
        assertEquals(JobStatus.CANCELLED, repository.getJob("job-1")?.status)

        repository.updateToFailed("job-1", "늦게 도착한 오류")
        assertEquals(JobStatus.CANCELLED, repository.getJob("job-1")?.status)
    }

    @Test
    @DisplayName("취소된 작업은 이후 완료 처리도 거부되고 결과가 반영되지 않는다")
    fun cancelledJobCannotTransitionToCompleted() {
        repository.registerJob("job-1")
        repository.updateToCancelled("job-1")

        val applied = repository.updateToCompleted("job-1", OcrResponse(fileName = "a.jpg", message = "ok"))

        assertFalse(applied, "취소된 작업에는 완료 처리가 적용되지 않아야 합니다.")
        assertEquals(JobStatus.CANCELLED, repository.getJob("job-1")?.status)
    }

    @Test
    @DisplayName("ACCEPTED나 PROCESSING이 아닌 작업은 완료 처리가 적용되지 않는다")
    fun completingAJobNotInAcceptedOrProcessingStateIsNoOp() {
        repository.registerJob("job-1")
        repository.updateToFailed("job-1", "이미 실패함")

        val applied = repository.updateToCompleted("job-1", OcrResponse(fileName = "a.jpg", message = "ok"))

        assertFalse(applied)
        assertEquals(JobStatus.FAILED, repository.getJob("job-1")?.status)
    }
}
