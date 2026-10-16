package com.example.yakallim.ocr.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class N8nClientConfig {

    /** n8n webhook은 요청을 받자마자 응답하지만, 연결이 막혔을 때 전송 스레드가 무한정 묶이지 않도록 타임아웃을 둔다. */
    @Bean
    fun n8nRestClient(builder: RestClient.Builder): RestClient {
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(60))
        }
        return builder.requestFactory(requestFactory).build()
    }
}
