package com.example.yakallim.notification.config

import com.example.yakallim.notification.service.PushNotificationClient
import com.google.firebase.messaging.FirebaseMessaging
import org.mockito.Mockito
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("test")
class MockFcmConfig {

    @Bean
    fun firebaseMessaging(): FirebaseMessaging {
        return Mockito.mock(FirebaseMessaging::class.java)
    }

    @Bean
    fun mockNotificationClient(): PushNotificationClient {
        return object : PushNotificationClient {
            override fun notify(token: String, title: String, body: String, data: Map<String, String>) {
            }
        }
    }
}