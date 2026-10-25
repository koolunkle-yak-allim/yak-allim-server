package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResult
import com.example.yakallim.ocr.model.JobStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Redis에 작업을 둔다. 서버 컨테이너가 바뀌어도(재배포·블루-그린 전환) 작업과 결과, FCM 토큰이 남는다.
 *
 * - `ocr:job:{jobId}` 해시: `status`, `updatedAt`(epoch ms), `result`(JSON), `error`, `fcmToken`
 * - `ocr:jobs:active` 정렬 집합: 진행 중(ACCEPTED/PROCESSING) 작업, 점수는 `updatedAt`. 처리 시간 초과를 찾을 때 쓴다.
 *
 * 종료된 작업은 키 TTL([retentionMinutes])로 지워지므로, [findJobIdsInStatusUpdatedBefore]는 진행 중 상태만 돌려준다.
 * 진행 중 작업에도 넉넉한 TTL을 걸어, 정리할 서버가 하나도 없을 때도 Redis에 남지 않게 한다.
 * 상태 전이와 토큰 꺼내기는 Lua 스크립트로 한 번에 처리해, 두 서버가 동시에 바꿔도 하나만 성공한다.
 */
@Repository
@ConditionalOnProperty(name = ["ocr.job-store"], havingValue = "redis")
class RedisOcrJobRepository(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @param:Value("\${ocr.job-retention-minutes:60}") private val retentionMinutes: Long
) : OcrJobRepository {

    init {
        LoggerFactory.getLogger(RedisOcrJobRepository::class.java).info("OCR job store: redis")
    }

    override fun registerJob(jobId: String): OcrJobResponse {
        redis.execute(REGISTER, listOf(jobKey(jobId), ACTIVE_KEY), jobId, now(), ACTIVE_TTL.seconds.toString())
        return OcrJobResponse(jobId = jobId, status = JobStatus.ACCEPTED)
    }

    override fun updateToProcessing(jobId: String): Boolean = transition(jobId, JobStatus.PROCESSING)

    override fun updateToCompleted(jobId: String, result: OcrResult): Boolean =
        transition(jobId, JobStatus.COMPLETED, FIELD_RESULT to objectMapper.writeValueAsString(result))

    override fun updateToFailed(jobId: String, errorMessage: String): Boolean =
        transition(jobId, JobStatus.FAILED, FIELD_ERROR to errorMessage)

    override fun updateToCancelled(jobId: String): Boolean = transition(jobId, JobStatus.CANCELLED)

    override fun getJob(jobId: String): OcrJobResponse? {
        val fields = redis.opsForHash<String, String>().entries(jobKey(jobId))
        val status = fields[FIELD_STATUS]?.let(JobStatus::valueOf) ?: return null
        return OcrJobResponse(
            jobId = jobId,
            status = status,
            result = fields[FIELD_RESULT]?.let { objectMapper.readValue(it, OcrResult::class.java) },
            error = fields[FIELD_ERROR]
        )
    }

    override fun isCancelled(jobId: String): Boolean = status(jobId) == JobStatus.CANCELLED

    override fun findJobIdsInStatusUpdatedBefore(statuses: Set<JobStatus>, cutoff: Instant): List<String> {
        // 기존 구현과 같이 cutoff "이전"(같은 시각 제외)만 고른다.
        val candidates = redis.opsForZSet()
            .rangeByScore(ACTIVE_KEY, Double.NEGATIVE_INFINITY, (cutoff.toEpochMilli() - 1).toDouble())
            .orEmpty()
        return candidates.filter { jobId ->
            val status = status(jobId)
            // 키가 TTL로 사라졌으면 색인에서도 뺀다.
            if (status == null) redis.opsForZSet().remove(ACTIVE_KEY, jobId)
            status in statuses
        }
    }

    override fun deleteJob(jobId: String) {
        redis.delete(jobKey(jobId))
        redis.opsForZSet().remove(ACTIVE_KEY, jobId)
    }

    override fun saveFcmToken(jobId: String, token: String) {
        redis.execute(SAVE_TOKEN, listOf(jobKey(jobId)), token)
    }

    override fun takeFcmToken(jobId: String): String? = redis.execute(TAKE_TOKEN, listOf(jobKey(jobId)))

    private fun transition(jobId: String, next: JobStatus, field: Pair<String, String>? = null): Boolean {
        val ttl = if (next == JobStatus.PROCESSING) ACTIVE_TTL else Duration.ofMinutes(retentionMinutes)
        val args = listOfNotNull(jobId, next.name, now(), ttl.seconds.toString(), field?.first, field?.second)
        return redis.execute(TRANSITION, listOf(jobKey(jobId), ACTIVE_KEY), *args.toTypedArray()) == 1L
    }

    private fun status(jobId: String): JobStatus? =
        redis.opsForHash<String, String>().get(jobKey(jobId), FIELD_STATUS)?.let(JobStatus::valueOf)

    private fun now(): String = clock.millis().toString()

    private fun jobKey(jobId: String) = "ocr:job:$jobId"

    companion object {
        const val ACTIVE_KEY = "ocr:jobs:active"
        private const val FIELD_STATUS = "status"
        private const val FIELD_RESULT = "result"
        private const val FIELD_ERROR = "error"

        /** 진행 중 작업의 안전망 TTL. 대기·처리 시간 초과(5분·15분)보다 충분히 길다. */
        private val ACTIVE_TTL: Duration = Duration.ofHours(2)

        // KEYS: 작업 해시, 진행 중 색인 / ARGV: jobId, updatedAt, ttl초
        private val REGISTER = RedisScript.of<Long>(
            """
            redis.call('HSET', KEYS[1], 'status', 'ACCEPTED', 'updatedAt', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java
        )

        // 진행 중인 작업만 바꾼다. KEYS: 작업 해시, 진행 중 색인 / ARGV: jobId, 새 상태, updatedAt, ttl초, [필드, 값]
        private val TRANSITION = RedisScript.of<Long>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if status ~= 'ACCEPTED' and status ~= 'PROCESSING' then return 0 end
            redis.call('HSET', KEYS[1], 'status', ARGV[2], 'updatedAt', ARGV[3])
            if ARGV[5] then redis.call('HSET', KEYS[1], ARGV[5], ARGV[6]) end
            if ARGV[2] == 'PROCESSING' then
                redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
            else
                redis.call('ZREM', KEYS[2], ARGV[1])
            end
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """.trimIndent(),
            Long::class.java
        )

        // 없는 작업(만료·삭제)에는 토큰만 담긴 해시가 생기지 않게 한다.
        private val SAVE_TOKEN = RedisScript.of<Long>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], 'fcmToken', ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java
        )

        private val TAKE_TOKEN = RedisScript.of<String>(
            """
            local token = redis.call('HGET', KEYS[1], 'fcmToken')
            if token then redis.call('HDEL', KEYS[1], 'fcmToken') end
            return token
            """.trimIndent(),
            String::class.java
        )
    }
}
