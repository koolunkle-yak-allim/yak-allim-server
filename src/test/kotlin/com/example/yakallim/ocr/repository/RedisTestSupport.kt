package com.example.yakallim.ocr.repository

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 Redis. 운영(yak-allim-infra의 docker-compose.yml)과 같은 이미지·옵션으로 한 번만 띄워 여러 테스트가 함께 쓴다.
 * Docker가 필요하다.
 */
object RedisTestSupport {
    const val IMAGE = "redis:8.10.2-alpine3.23"

    val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory-policy", "noeviction")
            .apply { start() }
    }

    val host: String get() = container.host
    val port: Int get() = container.getMappedPort(6379)

    fun connectionFactory(): LettuceConnectionFactory =
        LettuceConnectionFactory(host, port).apply {
            afterPropertiesSet()
            start()
        }

    fun template(factory: LettuceConnectionFactory = connectionFactory()): StringRedisTemplate =
        StringRedisTemplate(factory).apply { afterPropertiesSet() }
}
