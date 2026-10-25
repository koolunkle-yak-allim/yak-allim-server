package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrResult
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PrescribedMedicine
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RedisOcrJobRepositoryTest {

    private lateinit var clock: MutableClock
    private lateinit var redis: StringRedisTemplate
    private lateinit var repository: RedisOcrJobRepository

    @BeforeEach
    fun setUp() {
        clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        redis = RedisTestSupport.template()
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        repository = newRepository()
    }

    /** 블루-그린 전환 중의 다른 서버 인스턴스처럼, 같은 Redis를 쓰는 별도 저장소. */
    private fun newRepository() = RedisOcrJobRepository(redis, jacksonObjectMapper(), clock, retentionMinutes = 60)

    private fun ttlSeconds(jobId: String): Long = redis.getExpire("ocr:job:$jobId", TimeUnit.SECONDS)

    @Test
    @DisplayName("등록한 작업은 ACCEPTED로 조회되고 진행 중 안전망 TTL(2시간)이 걸린다")
    fun registersAcceptedJobWithActiveTtl() {
        repository.registerJob("job-1")

        assertEquals(JobStatus.ACCEPTED, repository.getJob("job-1")?.status)
        assertTrue(ttlSeconds("job-1") in 7000..7200)
    }

    @Test
    @DisplayName("결과는 JSON으로 저장했다가 그대로 되살린다")
    fun roundTripsResult() {
        val result = OcrResult(
            message = "완료",
            medicines = listOf(
                PrescribedMedicine("타이레놀정500mg", "1정", 3, 5),
                PrescribedMedicine("뮤코펙트정", null, 2, null)
            )
        )
        repository.registerJob("job-1")

        assertTrue(repository.updateToCompleted("job-1", result))

        val job = repository.getJob("job-1")
        assertEquals(JobStatus.COMPLETED, job?.status)
        assertEquals(result, job?.result)
    }

    @Test
    @DisplayName("종료된 작업은 다시 전이되지 않고, 보관 기간 TTL이 걸리며 진행 중 색인에서 빠진다")
    fun finishedJobsAreFinalAndExpire() {
        repository.registerJob("job-1")
        repository.updateToProcessing("job-1")

        assertTrue(repository.updateToFailed("job-1", "오류"))
        assertFalse(repository.updateToCompleted("job-1", OcrResult("완료")))
        assertFalse(repository.updateToCancelled("job-1"))

        assertEquals(JobStatus.FAILED, repository.getJob("job-1")?.status)
        assertEquals("오류", repository.getJob("job-1")?.error)
        assertTrue(ttlSeconds("job-1") in 3500..3600)
        assertNull(redis.opsForZSet().score(RedisOcrJobRepository.ACTIVE_KEY, "job-1"))
    }

    @Test
    @DisplayName("없는 작업은 조회되지 않고 전이도 되지 않는다")
    fun unknownJob() {
        assertNull(repository.getJob("missing"))
        assertFalse(repository.updateToProcessing("missing"))
        assertFalse(repository.isCancelled("missing"))
    }

    @Test
    @DisplayName("다른 인스턴스가 받은 작업도 조회·전이할 수 있다")
    fun anotherInstanceSeesTheSameJob() {
        repository.registerJob("job-1")

        val other = newRepository()
        assertTrue(other.updateToCancelled("job-1"))
        assertTrue(repository.isCancelled("job-1"))
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 종료시키면 하나만 성공한다")
    fun concurrentTransitionsHaveOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(50) { round ->
                val jobId = "job-$round"
                repository.registerJob(jobId)
                val start = CountDownLatch(1)
                val completed = pool.submit<Boolean> { start.await(); repository.updateToCompleted(jobId, OcrResult("완료")) }
                val failed = pool.submit<Boolean> { start.await(); newRepository().updateToFailed(jobId, "시간 초과") }
                start.countDown()

                assertEquals(1, listOf(completed.get(), failed.get()).count { it }, "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @DisplayName("갱신 시각이 cutoff 이전인 진행 중 작업만 상태별로 고른다")
    fun findsTimedOutActiveJobs() {
        repository.registerJob("accepted-old")
        repository.registerJob("processing-old")
        repository.updateToProcessing("processing-old")
        clock.advanceBy(Duration.ofMinutes(20))
        repository.registerJob("processing-new")
        repository.updateToProcessing("processing-new")

        val cutoff = clock.instant().minus(Duration.ofMinutes(15))

        assertEquals(listOf("processing-old"), repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.PROCESSING), cutoff))
        assertEquals(listOf("accepted-old"), repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.ACCEPTED), cutoff))
    }

    @Test
    @DisplayName("TTL로 사라진 작업은 진행 중 색인에서도 정리된다")
    fun cleansStaleIndexEntries() {
        repository.registerJob("job-1")
        redis.delete("ocr:job:job-1")
        clock.advanceBy(Duration.ofMinutes(20))

        val found = repository.findJobIdsInStatusUpdatedBefore(setOf(JobStatus.ACCEPTED), clock.instant())

        assertTrue(found.isEmpty())
        assertNull(redis.opsForZSet().score(RedisOcrJobRepository.ACTIVE_KEY, "job-1"))
    }

    @Test
    @DisplayName("FCM 토큰은 한 번만 꺼낼 수 있고, 없는 작업에는 저장되지 않는다")
    fun fcmTokenIsTakenOnce() {
        repository.registerJob("job-1")
        repository.saveFcmToken("job-1", "token-1")

        assertEquals("token-1", newRepository().takeFcmToken("job-1"))
        assertNull(repository.takeFcmToken("job-1"))

        repository.saveFcmToken("missing", "token-2")
        assertFalse(redis.hasKey("ocr:job:missing"))
    }

    @Test
    @DisplayName("삭제하면 작업과 색인이 함께 지워진다")
    fun deleteRemovesJobAndIndex() {
        repository.registerJob("job-1")

        repository.deleteJob("job-1")

        assertNull(repository.getJob("job-1"))
        assertNull(redis.opsForZSet().score(RedisOcrJobRepository.ACTIVE_KEY, "job-1"))
    }
}
