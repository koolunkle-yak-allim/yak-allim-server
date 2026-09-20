package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.engine.OcrEngine
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.parser.PrescriptionParser
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class OcrJobProcessorErrorHandlingTest {

    @TempDir
    lateinit var uploadDir: Path

    private lateinit var ocrJobRepository: FakeOcrJobRepository
    private lateinit var ocrProgressManager: OcrProgressManager
    private lateinit var uploadedFile: Path

    @BeforeEach
    fun setUp() {
        ocrJobRepository = FakeOcrJobRepository()
        ocrProgressManager = OcrProgressManager(ocrJobRepository)
        uploadedFile = Files.createFile(uploadDir.resolve("prescription.jpg"))
    }

    private fun buildProcessor(ocrEngine: OcrEngine, prescriptionParser: PrescriptionParser) = OcrJobProcessor(
        ocrEngine = ocrEngine,
        ocrJobRepository = ocrJobRepository,
        prescriptionParser = prescriptionParser,
        notifier = NoOpPushNotificationClient(),
        ocrProgressManager = ocrProgressManager,
        uploadDirStr = uploadDir.toString()
    )

    @Test
    @DisplayName("취소가 아닌 IllegalStateException은 취소로 오인되지 않고 작업이 FAILED 처리된다")
    fun treatsUnrelatedIllegalStateExceptionAsFailureNotCancellation() {
        val engine = mock<OcrEngine>()
        whenever(engine.runOcr(any(), any())).thenThrow(IllegalStateException("라이브러리 내부 상태 오류"))
        val parser = mock<PrescriptionParser>()

        buildProcessor(engine, parser).executeTask("job-1", uploadedFile, "prescription.jpg", token = null)

        assertTrue(ocrJobRepository.failedCalled, "취소가 아닌 예외는 updateToFailed가 호출돼야 합니다.")
        assertFalse(ocrJobRepository.cancelledLoggedAsFailure)
    }

    @Test
    @DisplayName("엔진이 준비되지 않으면 작업이 FAILED 처리된다")
    fun marksJobAsFailedWhenEngineNotReady() {
        val engine = mock<OcrEngine>()
        whenever(engine.runOcr(any(), any())).thenThrow(OcrException.EngineNotReadyException())
        val parser = mock<PrescriptionParser>()

        buildProcessor(engine, parser).executeTask("job-2", uploadedFile, "prescription.jpg", token = null)

        assertTrue(ocrJobRepository.failedCalled)
        assertEquals("OCR 엔진이 준비되지 않았습니다.", ocrJobRepository.lastFailureReason)
    }

    @Test
    @DisplayName("실제로 취소된 작업은 updateToFailed를 호출하지 않고 취소로만 처리된다")
    fun doesNotCallUpdateToFailedWhenActuallyCancelled() {
        ocrJobRepository.cancelled = true
        val engine = mock<OcrEngine>()
        val parser = mock<PrescriptionParser>()

        buildProcessor(engine, parser).executeTask("job-3", uploadedFile, "prescription.jpg", token = null)

        assertFalse(ocrJobRepository.failedCalled, "취소된 작업은 실패 처리(updateToFailed)를 거치지 않아야 합니다.")
    }
}

private class FakeOcrJobRepository : OcrJobRepository {
    var cancelled = false
    var failedCalled = false
    var cancelledLoggedAsFailure = false
    var lastFailureReason: String? = null

    override fun registerJob(jobId: String): OcrJobResponse = throw NotImplementedError()
    override fun updateToProcessing(jobId: String) {}
    override fun updateToCompleted(jobId: String, result: OcrResponse): Boolean = true
    override fun updateToFailed(jobId: String, errorMessage: String) {
        failedCalled = true
        lastFailureReason = errorMessage
        if (errorMessage.contains("취소")) cancelledLoggedAsFailure = true
    }

    override fun updateToCancelled(jobId: String) {}
    override fun getJob(jobId: String): OcrJobResponse? = null
    override fun isCancelled(jobId: String): Boolean = cancelled
}

private class NoOpPushNotificationClient : PushNotificationClient {
    override fun notify(token: String, title: String, body: String, data: Map<String, String>) {}
}
