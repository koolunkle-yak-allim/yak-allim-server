package com.example.yakallim.ocr.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

/** n8n 전송은 응답을 기다리는 동안 스레드를 붙잡으므로, Spring Boot 기본 실행기 대신 동시 실행 수와 대기열을 제한한 전용 스레드풀을 쓴다. */
@Configuration
class AsyncConfig {

    @Bean("ocrTaskExecutor")
    fun ocrTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        queueCapacity = 20
        setThreadNamePrefix("ocr-")
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(60)
    }
}
