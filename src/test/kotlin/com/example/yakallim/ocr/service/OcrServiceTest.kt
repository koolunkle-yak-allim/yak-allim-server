package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.N8nCallbackRequest
import com.example.yakallim.ocr.dto.N8nCallbackData
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.infrastructure.N8nOcrClient
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.model.PrescribedMedicine
import com.example.yakallim.ocr.repository.InMemoryOcrJobRepository
import com.example.yakallim.ocr.repository.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Path
import java.time.Instant

/** n8n 콜백(성공/실패)과 취소가 작업 상태·SSE·FCM에 올바르게 반영되는지 검증한다. */
class OcrServiceTest {

    @TempDir
    lateinit var uploadDir: Path

    private lateinit var ocrJobRepository: InMemoryOcrJobRepository
    private lateinit var ocrProgressManager: OcrProgressManager
    private lateinit var notifier: PushNotificationClient
    private lateinit var service: OcrService

    @BeforeEach
    fun setUp() {
        ocrJobRepository = InMemoryOcrJobRepository(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))
        ocrProgressManager = mock()
        notifier = mock()
        service = OcrService(
            ocrJobRepository = ocrJobRepository,
            ocrProgressManager = ocrProgressManager,
            n8nOcrClient = mock<N8nOcrClient>(),
            notifier = notifier,
            uploadDirStr = uploadDir.toString()
        )
    }

    private fun enqueue(fcmToken: String? = "token-1"): String {
        val file = MockMultipartFile("file", "test.jpg", "image/jpeg", "content".toByteArray())
        return service.enqueueJob(file, fcmToken).jobId
    }

    @Test
    @DisplayName("성공 콜백은 약품명 공백을 정리하고 빈 약품명을 제외한 뒤 COMPLETED로 전이하고 완료 알림을 보낸다")
    fun completesJobOnSuccessCallback() {
        val jobId = enqueue()
        val medicines = listOf(
            PrescribedMedicine(medicineName = "  타이레놀정 ", dosagePerTake = "1정", dailyFrequency = 3, durationDays = 3),
            PrescribedMedicine(medicineName = "   ")
        )

        service.handleCallback(N8nCallbackRequest(jobId = jobId, status = "COMPLETED", data = N8nCallbackData(medicines)))

        val job = ocrJobRepository.getJob(jobId)
        assertEquals(JobStatus.COMPLETED, job?.status)
        assertEquals(listOf("타이레놀정"), job?.result?.medicines?.map { it.medicineName })
        verify(ocrProgressManager).publishProgress(eq(jobId), eq(PipelineStep.COMPLETED), any(), anyOrNull())

        val dataCaptor = argumentCaptor<Map<String, String>>()
        verify(notifier).notify(eq("token-1"), any(), any(), dataCaptor.capture())
        assertEquals("COMPLETED", dataCaptor.firstValue["status"])
    }

    @Test
    @DisplayName("실패 콜백은 FAILED로 전이하고 N8N_PROCESSING_FAILED 알림을 보내며, n8n의 원인 메시지는 노출하지 않는다")
    fun failsJobOnFailureCallback() {
        val jobId = enqueue()

        service.handleCallback(N8nCallbackRequest(jobId = jobId, status = "failed", error = "LLM timeout"))

        val job = ocrJobRepository.getJob(jobId)
        assertEquals(JobStatus.FAILED, job?.status)
        assertFalse(job?.error.orEmpty().contains("LLM"))
        verify(ocrProgressManager).publishProgress(eq(jobId), eq(PipelineStep.FAILED), any(), anyOrNull())

        val dataCaptor = argumentCaptor<Map<String, String>>()
        verify(notifier).notify(eq("token-1"), any(), any(), dataCaptor.capture())
        assertEquals("N8N_PROCESSING_FAILED", dataCaptor.firstValue["errorCode"])
    }

    @Test
    @DisplayName("이미 취소된 작업에 콜백이 도착하면 상태를 바꾸지 않고 알림도 보내지 않는다")
    fun ignoresCallbackForCancelledJob() {
        val jobId = enqueue()
        service.cancelJob(jobId)

        service.handleCallback(N8nCallbackRequest(jobId = jobId, status = "COMPLETED"))

        assertEquals(JobStatus.CANCELLED, ocrJobRepository.getJob(jobId)?.status)
        verify(ocrProgressManager, never()).publishProgress(eq(jobId), eq(PipelineStep.COMPLETED), anyOrNull(), anyOrNull())
        verifyNoInteractions(notifier)
    }

    @Test
    @DisplayName("작업을 취소하면 CANCELLED 진행 이벤트를 발행한다")
    fun publishesCancelledEventOnCancel() {
        val jobId = enqueue()

        service.cancelJob(jobId)

        assertEquals(JobStatus.CANCELLED, ocrJobRepository.getJob(jobId)?.status)
        verify(ocrProgressManager).publishProgress(eq(jobId), eq(PipelineStep.CANCELLED), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("이미 종료된 작업은 취소할 수 없다")
    fun rejectsCancelOfFinishedJob() {
        val jobId = enqueue()
        service.handleCallback(N8nCallbackRequest(jobId = jobId, status = "COMPLETED"))

        assertThrows(OcrException.IllegalJobStateException::class.java) { service.cancelJob(jobId) }
    }
}
