package com.example.yakallim.notification.infrastructure

import com.example.yakallim.notification.service.PushNotificationClient
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component("FCM_CLIENT")
@Profile("!test")
class FcmPushNotificationClient(
    private val firebaseMessaging: FirebaseMessaging
) : PushNotificationClient {

    private val log = LoggerFactory.getLogger(FcmPushNotificationClient::class.java)

    override fun notify(token: String, title: String, body: String, data: Map<String, String>) {
        if (token.isBlank()) {
            log.warn("FCM notification skipped: token missing")
            return
        }

        runCatching {
            val notification = Notification.builder().setTitle(title).setBody(body).build()
            val message = Message.builder().apply {
                setToken(token)
                setNotification(notification)
                putAllData(data)
            }.build()

            firebaseMessaging.send(message)
        }.onSuccess {
            log.info("FCM notification sent successfully: [title] {}", title)
        }.onFailure { e ->
            log.error("Failed to send FCM notification: [title] {}, [token] {}...", title, token.take(6), e)
        }
    }
}
