package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path

/** S10: 비동기 실행기의 큐가 가득 차 작업 제출이 거절될 때 enqueueJob이 정리·응답을 올바르게 처리하는지 검증한다. */
class OcrServiceRejectionTest {

    @TempDir
    lateinit var uploadDir: Path

    @Test
    @DisplayName("실행기가 작업을 거절하면 업로드 파일을 지우고 작업을 실패 처리한 뒤 503을 던진다")
    fun cleansUpAndReturns503WhenExecutorRejectsTask() {
        val ocrJobRepository = FakeOcrJobRepository()
        val ocrProgressManager = OcrProgressManager(ocrJobRepository)
        val service = RejectingOcrService(ocrJobRepository, ocrProgressManager, uploadDir.toString())
        val file = MockMultipartFile("file", "test.jpg", "image/jpeg", "content".toByteArray())

        val exception = assertThrows(OcrException.ServiceBusyException::class.java) {
            service.enqueueJob(file, fcmToken = null)
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status)
        assertTrue(ocrJobRepository.failedCalled, "거절된 작업은 updateToFailed가 호출돼야 합니다.")
        Files.newDirectoryStream(uploadDir).use { stream ->
            assertTrue(stream.none(), "거절 시 업로드된 이미지 파일이 정리되지 않았습니다.")
        }
    }

    private class RejectingOcrService(
        ocrJobRepository: OcrJobRepository,
        ocrProgressManager: OcrProgressManager,
        uploadDirStr: String
    ) : OcrService(ocrJobRepository, ocrProgressManager, uploadDirStr) {
        override fun processJob(
            jobId: String,
            targetPath: Path,
            uniqueFileName: String,
            fcmToken: String?,
            delay: Long?
        ) {
            throw TaskRejectedException("executor queue is full")
        }
    }
}
