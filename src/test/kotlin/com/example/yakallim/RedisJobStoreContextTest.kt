package com.example.yakallim

import com.example.yakallim.ocr.repository.OcrJobRepository
import com.example.yakallim.ocr.repository.RedisOcrJobRepository
import com.example.yakallim.ocr.repository.RedisTestSupport
import com.example.yakallim.ocr.service.ProgressEventBus
import com.example.yakallim.ocr.service.RedisProgressEventBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** 운영 배포와 같은 설정(`ocr.job-store=redis`, Redis 헬스체크 켬)으로 앱이 뜨는지 확인한다. Docker가 필요하다. */
@SpringBootTest(properties = ["ocr.job-store=redis", "management.health.redis.enabled=true"])
class RedisJobStoreContextTest {

    @Autowired
    private lateinit var ocrJobRepository: OcrJobRepository

    @Autowired
    private lateinit var progressEventBus: ProgressEventBus

    @Autowired
    private lateinit var healthEndpoint: HealthEndpoint

    @Test
    @DisplayName("redis 모드에서는 Redis 저장소와 이벤트 버스를 쓰고, Redis가 헬스체크에 포함된다")
    fun usesRedisImplementations() {
        assertInstanceOf(RedisOcrJobRepository::class.java, ocrJobRepository)
        assertInstanceOf(RedisProgressEventBus::class.java, progressEventBus)
        assertEquals(Status.UP, healthEndpoint.healthForPath("redis")?.status)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { RedisTestSupport.host }
            registry.add("spring.data.redis.port") { RedisTestSupport.port }
        }
    }
}
