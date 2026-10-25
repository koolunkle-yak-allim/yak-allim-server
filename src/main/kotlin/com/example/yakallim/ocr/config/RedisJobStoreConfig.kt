package com.example.yakallim.ocr.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/** `ocr.job-store=redis`일 때만 진행 이벤트 구독용 리스너 컨테이너를 만든다. 메모리 모드에서는 Redis에 연결하지 않는다. */
@Configuration
@ConditionalOnProperty(name = ["ocr.job-store"], havingValue = "redis")
class RedisJobStoreConfig {

    @Bean
    fun ocrProgressListenerContainer(connectionFactory: RedisConnectionFactory) =
        RedisMessageListenerContainer().apply { setConnectionFactory(connectionFactory) }
}
