package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.dto.N8nCallbackRequest
import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResult
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.model.FailureCode
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.model.PrescribedMedicine
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 업로드된 처방전 이미지를 n8n 워크플로우로 넘기고, 콜백으로 받은 결과를 작업 상태·SSE·FCM으로 전달한다. */
@Service
class OcrService(
    private val ocrJobRepository: OcrJobRepository,
    private val ocrProgressManager: OcrProgressManager,
    private val ocrJobDispatcher: OcrJobDispatcher,
    private val ocrJobNotifier: OcrJobNotifier,
    @Value("\${ocr.upload-dir:outputs/api-images}") uploadDirStr: String
) {
    private val log = LoggerFactory.getLogger(OcrService::class.java)
    private val uploadDir: Path = Paths.get(uploadDirStr).toAbsolutePath().normalize()

    init {
        try {
            Files.createDirectories(uploadDir)
        } catch (e: IOException) {
            throw OcrException.FileSaveException("OCR 이미지 디렉터리를 생성할 수 없습니다.", e)
        }
    }

    fun enqueueJob(file: MultipartFile, fcmToken: String?): OcrJobResponse {
        if (file.isEmpty) {
            throw OcrException.EmptyFileException()
        }

        val originalFilename = file.originalFilename ?: ""
        val extension = originalFilename.substringAfterLast('.', "")
            .replace(EXTENSION_REGEX, "")
            .lowercase()

        // n8n의 Upstage OCR이 지원하는 이미지 형식만 받는다. gif는 Upstage가 415로 거부하고,
        // webp는 공식 지원 형식이 아니어서, 받아 두면 분석 단계에서야 실패로 끝난다.
        val safeExtension = when (extension) {
            "jpg", "jpeg" -> "jpg"
            "png" -> "png"
            else -> throw OcrException.InvalidFileExtensionException("허용되지 않는 파일 확장자입니다: $extension")
        }
        val uniqueFileName = "${UUID.randomUUID()}.$safeExtension"
        val targetPath = uploadDir.resolve(uniqueFileName).normalize()
        require(targetPath.startsWith(uploadDir)) { "Invalid file path: path traversal detected." }

        try {
            file.inputStream.use { inputStream ->
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw OcrException.FileSaveException("이미지 파일 저장 중 입출력 오류가 발생했습니다.", e)
        }

        val jobId = UUID.randomUUID().toString()
        val job = ocrJobRepository.registerJob(jobId)

        ocrProgressManager.publishProgress(jobId, PipelineStep.ACCEPTED)
        ocrJobNotifier.register(jobId, fcmToken)

        try {
            ocrJobDispatcher.dispatch(jobId, targetPath)
        } catch (e: TaskRejectedException) {
            ocrJobNotifier.discard(jobId)
            runCatching { Files.deleteIfExists(targetPath) }
                .onFailure { log.warn("Failed to delete uploaded image after task rejection: {}", targetPath, it) }
            val rejectionMessage = "서버가 바빠 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."
            ocrJobRepository.updateToFailed(jobId, rejectionMessage)
            ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, rejectionMessage)
            log.warn("OCR task rejected by executor (queue full): jobId='{}'", jobId, e)
            throw OcrException.ServiceBusyException(rejectionMessage)
        }

        return job
    }

    fun getJob(jobId: String): OcrJobResponse? = ocrJobRepository.getJob(jobId)

    fun cancelJob(jobId: String) {
        val job = ocrJobRepository.getJob(jobId)
            ?: throw OcrException.JobNotFoundException("존재하지 않는 작업ID: $jobId")

        if (!ocrJobRepository.updateToCancelled(jobId)) {
            throw OcrException.IllegalJobStateException("이미 종료된 작업은 취소할 수 없습니다. (작업ID: $jobId, 상태: ${job.status})")
        }

        ocrJobNotifier.discard(jobId)
        ocrProgressManager.publishProgress(jobId, PipelineStep.CANCELLED)
        log.info("OCR job cancelled: jobId='{}' (previous status: {})", jobId, job.status)
    }

    fun handleCallback(request: N8nCallbackRequest) {
        val jobId = requireNotNull(request.jobId)
        if (request.isFailure) {
            handleFailure(jobId, request.error)
        } else {
            handleSuccess(jobId, request.data.medicines)
        }
    }

    private fun handleSuccess(jobId: String, medicines: List<PrescribedMedicine>) {
        val result = OcrResult(
            message = COMPLETED_MESSAGE,
            medicines = medicines
                .map { it.copy(medicineName = it.medicineName.trim()) }
                .filter { it.medicineName.isNotEmpty() }
                // 복약안내문 옆 영수증 목록처럼 같은 줄이 반복되면 LLM이 그대로 옮길 수 있다.
                // 이름·1회 양·횟수·일수가 모두 같은 항목만 합치고, 용법이 다른 항목은 남긴다.
                .distinct()
        )

        if (!ocrJobRepository.updateToCompleted(jobId, result)) {
            ocrJobNotifier.discard(jobId)
            log.info("Ignoring n8n callback for job that is no longer active: jobId='{}'", jobId)
            return
        }

        ocrProgressManager.publishProgress(jobId, PipelineStep.COMPLETED, result.message)
        ocrJobNotifier.notifyCompleted(jobId, result.message)
    }

    /** n8n 워크플로우가 분석 실패를 콜백으로 알린 경우. 원인은 로그에만 남기고 사용자에게는 일반 메시지를 보낸다. */
    private fun handleFailure(jobId: String, reason: String?) {
        // 취소 뒤에 늦게 도착한 실패는 정상 흐름이므로, 성공 콜백과 같이 INFO로만 남긴다.
        if (!ocrJobRepository.updateToFailed(jobId, ANALYSIS_FAILED_MESSAGE)) {
            ocrJobNotifier.discard(jobId)
            log.info("Ignoring n8n failure callback for job that is no longer active: jobId='{}', reason='{}'", jobId, reason)
            return
        }

        log.warn("n8n reported analysis failure: jobId='{}', reason='{}'", jobId, reason)

        ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, ANALYSIS_FAILED_MESSAGE)
        ocrJobNotifier.notifyFailed(jobId, ANALYSIS_FAILED_MESSAGE, FailureCode.N8N_PROCESSING_FAILED)
    }

    companion object {
        private val EXTENSION_REGEX = Regex("[^a-zA-Z0-9]")
        private const val COMPLETED_MESSAGE = "복약 안내서 분석이 완료되었습니다.\n복약 지침을 확인해 보세요."
        private const val ANALYSIS_FAILED_MESSAGE = "복약 안내서를 분석하지 못했습니다. 사진을 다시 찍어 올려 주세요."
    }
}
