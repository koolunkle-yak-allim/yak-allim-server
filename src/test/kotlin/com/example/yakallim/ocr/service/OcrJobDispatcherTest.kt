package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.infrastructure.N8nOcrClient
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.InMemoryOcrJobRepository
import com.example.yakallim.ocr.repository.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** n8n 전송의 성공·실패·취소가 작업 상태·SSE·FCM에 올바르게 반영되는지 검증한다. */
class OcrJobDispatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var ocrJobRepository: InMemoryOcrJobRepository
    private lateinit var ocrProgressManager: OcrProgressManager
    private lateinit var n8nOcrClient: N8nOcrClient
    private lateinit var pushNotificationClient: PushNotificationClient
    private lateinit var dispatcher: OcrJobDispatcher

    @BeforeEach
    fun setUp() {
        ocrJobRepository = InMemoryOcrJobRepository(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))
        ocrProgressManager = mock()
        n8nOcrClient = mock()
        pushNotificationClient = mock()
        val notifier = OcrJobNotifier(pushNotificationClient).apply { register("job-1", "token-1") }
        dispatcher = OcrJobDispatcher(ocrJobRepository, ocrProgressManager, n8nOcrClient, notifier)
        ocrJobRepository.registerJob("job-1")
    }

    private fun image(): Path = Files.write(tempDir.resolve("upload.jpg"), byteArrayOf(1, 2, 3))

    @Test
    @DisplayName("n8n이 요청을 받으면 PROCESSING으로 전이하고 ANALYZING을 발행하며 업로드 파일을 지운다")
    fun proceedsToAnalyzing_whenN8nAccepts() {
        val image = image()

        dispatcher.dispatch("job-1", image)

        verify(n8nOcrClient).send("job-1", image)
        assertEquals(JobStatus.PROCESSING, ocrJobRepository.getJob("job-1")?.status)
        verify(ocrProgressManager).publishProgress(eq("job-1"), eq(PipelineStep.ANALYZING), anyOrNull(), anyOrNull())
        verifyNoInteractions(pushNotificationClient)
        assertFalse(Files.exists(image))
    }

    @Test
    @DisplayName("n8n 전송이 실패하면 사용자용 메시지로 FAILED 처리하고 N8N_DISPATCH_FAILED 알림을 보낸다")
    fun marksJobFailed_whenDispatchFails() {
        val image = image()
        doThrow(HttpClientErrorException(HttpStatus.FORBIDDEN))
            .whenever(n8nOcrClient).send(any(), any())

        dispatcher.dispatch("job-1", image)

        // 작업에는 내부 원인(HTTP 403 등)이 아니라 사용자에게 보여줄 메시지가 저장돼야 한다.
        val job = ocrJobRepository.getJob("job-1")
        assertEquals(JobStatus.FAILED, job?.status)
        assertFalse(job?.error.orEmpty().contains("403"))
        verify(ocrProgressManager).publishProgress(eq("job-1"), eq(PipelineStep.FAILED), any(), anyOrNull())
        verify(ocrProgressManager, never())
            .publishProgress(eq("job-1"), eq(PipelineStep.ANALYZING), anyOrNull(), anyOrNull())

        val dataCaptor = argumentCaptor<Map<String, String>>()
        verify(pushNotificationClient).notify(eq("token-1"), any(), any(), dataCaptor.capture())
        assertEquals("N8N_DISPATCH_FAILED", dataCaptor.firstValue["errorCode"])
        assertFalse(Files.exists(image))
    }

    @Test
    @DisplayName("대기 중에 취소된 작업은 n8n으로 전송하지 않고 업로드 파일만 지운다")
    fun skipsDispatch_whenJobCancelledBeforeSending() {
        val image = image()
        ocrJobRepository.updateToCancelled("job-1")

        dispatcher.dispatch("job-1", image)

        verifyNoInteractions(n8nOcrClient, ocrProgressManager, pushNotificationClient)
        assertEquals(JobStatus.CANCELLED, ocrJobRepository.getJob("job-1")?.status)
        assertFalse(Files.exists(image))
    }
}
