package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.engine.OcrEngine
import com.example.yakallim.ocr.parser.PrescriptionParser
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class OcrJobProcessorTest {

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
    @DisplayName("정상 처리가 완료되면 업로드된 처방전 이미지를 삭제한다")
    fun deletesUploadedImageAfterSuccessfulProcessing() {
        // 언스텁 상태의 목은 List 반환 메서드에 대해 기본적으로 빈 리스트를 반환한다.
        val engine = mock<OcrEngine>()
        val parser = mock<PrescriptionParser>()

        buildProcessor(engine, parser).executeTask("job-1", uploadedFile, "prescription.jpg", token = null)

        assertFalse(Files.exists(uploadedFile), "성공 처리 후에도 업로드 파일이 남아있습니다.")
    }

    @Test
    @DisplayName("처리 중 예외가 발생해도 업로드된 처방전 이미지를 삭제한다")
    fun deletesUploadedImageWhenProcessingFails() {
        val engine = mock<OcrEngine>()
        whenever(engine.runOcr(any(), any())).thenThrow(RuntimeException("엔진 오류"))
        val parser = mock<PrescriptionParser>()

        buildProcessor(engine, parser).executeTask("job-2", uploadedFile, "prescription.jpg", token = null)

        assertFalse(Files.exists(uploadedFile), "실패 처리 후에도 업로드 파일이 남아있습니다.")
    }

    @Test
    @DisplayName("처리 전 취소된 작업도 업로드된 처방전 이미지를 삭제한다")
    fun deletesUploadedImageWhenCancelledBeforeProcessing() {
        ocrJobRepository.cancelled = true
        val engine = mock<OcrEngine>()
        val parser = mock<PrescriptionParser>()

        buildProcessor(engine, parser).executeTask("job-3", uploadedFile, "prescription.jpg", token = null)

        assertFalse(Files.exists(uploadedFile), "취소 처리 후에도 업로드 파일이 남아있습니다.")
    }
}

private class FakeOcrJobRepository : OcrJobRepository {
    var cancelled = false

    override fun registerJob(jobId: String): OcrJobResponse = throw NotImplementedError()
    override fun updateToProcessing(jobId: String) {}
    override fun updateToCompleted(jobId: String, result: OcrResponse): Boolean = true
    override fun updateToFailed(jobId: String, errorMessage: String) {}
    override fun updateToCancelled(jobId: String) {}
    override fun getJob(jobId: String): OcrJobResponse? = null
    override fun isCancelled(jobId: String): Boolean = cancelled
}

private class NoOpPushNotificationClient : PushNotificationClient {
    override fun notify(token: String, title: String, body: String, data: Map<String, String>) {}
}
