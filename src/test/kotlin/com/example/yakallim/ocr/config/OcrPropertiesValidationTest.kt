package com.example.yakallim.ocr.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/** n8n webhook secret이 없으면 콜백 인증이 불가능하므로 애플리케이션이 시작되지 않아야 한다. */
class OcrPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(OcrProperties::class)
    class TestConfig

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
        .withUserConfiguration(TestConfig::class.java)

    @Test
    @DisplayName("webhook secret이 비어 있으면 시작에 실패한다")
    fun failsToStart_whenWebhookSecretIsBlank() {
        contextRunner
            .withPropertyValues("ocr.n8n.webhook-secret=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("OCR_N8N_WEBHOOK_SECRET 환경 변수가 필요합니다.")
            }
    }

    @Test
    @DisplayName("webhook secret이 있으면 정상적으로 시작한다")
    fun starts_whenWebhookSecretIsPresent() {
        contextRunner
            .withPropertyValues("ocr.n8n.webhook-secret=secret")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(OcrProperties::class.java).n8n.webhookSecret).isEqualTo("secret")
            }
    }
}
