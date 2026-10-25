package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.dto.OcrProgressResponse
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.RedisTestSupport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RedisProgressEventBusTest {

    private val containers = mutableListOf<RedisMessageListenerContainer>()

    /** 서버 인스턴스 하나에 해당하는 이벤트 버스. 인스턴스마다 Redis 연결과 구독이 따로 있다. */
    private fun newBus(): RedisProgressEventBus {
        val factory = RedisTestSupport.connectionFactory()
        val container = RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            afterPropertiesSet()
        }
        containers += container
        return RedisProgressEventBus(RedisTestSupport.template(factory), container, jacksonObjectMapper())
    }

    private fun startAndAwaitSubscriptions() {
        containers.forEach { it.start() }
        val deadline = System.currentTimeMillis() + 5_000
        while (containers.any { !it.isListening } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(containers.all { it.isListening })
    }

    @AfterEach
    fun tearDown() {
        containers.forEach { it.stop(); it.destroy() }
    }

    @Test
    @DisplayName("한 인스턴스가 발행한 진행 이벤트를 모든 인스턴스가 받는다")
    fun deliversToEveryInstance() {
        val blue = newBus()
        val green = newBus()
        val receivedByBlue = LinkedBlockingQueue<ProgressEvent>()
        val receivedByGreen = LinkedBlockingQueue<ProgressEvent>()
        blue.subscribe { receivedByBlue.add(it) }
        green.subscribe { receivedByGreen.add(it) }
        startAndAwaitSubscriptions()

        val event = ProgressEvent(
            jobId = "job-1",
            progress = OcrProgressResponse(PipelineStep.COMPLETED, "분석이 완료되었습니다.", 100, isFinished = true)
        )
        green.publish(event)

        assertEquals(event, receivedByBlue.poll(5, TimeUnit.SECONDS))
        assertEquals(event, receivedByGreen.poll(5, TimeUnit.SECONDS))
    }
}
