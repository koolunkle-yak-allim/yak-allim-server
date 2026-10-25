package com.example.yakallim.ocr.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component

/**
 * Redis Pub/Sub로 진행 이벤트를 모든 서버 인스턴스에 보낸다. 발행한 인스턴스도 구독 중이라 자기 SSE에도 전달된다.
 * 이벤트를 놓쳐도(연결이 잠깐 끊기는 등) 클라이언트가 다시 연결하면 작업 저장소의 상태로 현재 단계를 받는다.
 */
@Component
@ConditionalOnProperty(name = ["ocr.job-store"], havingValue = "redis")
class RedisProgressEventBus(
    private val redis: StringRedisTemplate,
    private val listenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper
) : ProgressEventBus {
    private val log = LoggerFactory.getLogger(RedisProgressEventBus::class.java)

    override fun publish(event: ProgressEvent) {
        redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event))
    }

    override fun subscribe(listener: (ProgressEvent) -> Unit) {
        val messageListener = MessageListener { message, _ ->
            runCatching { objectMapper.readValue(message.body, ProgressEvent::class.java) }
                .onSuccess(listener)
                .onFailure { log.warn("Ignoring malformed progress event", it) }
        }
        listenerContainer.addMessageListener(messageListener, ChannelTopic(CHANNEL))
    }

    companion object {
        const val CHANNEL = "ocr:progress"
    }
}
