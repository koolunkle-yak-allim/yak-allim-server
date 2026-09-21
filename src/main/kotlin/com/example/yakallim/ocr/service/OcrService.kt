package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

abstract class OcrService(
    protected val ocrJobRepository: OcrJobRepository,
    protected val ocrProgressManager: OcrProgressManager,
    uploadDirStr: String
) {
    protected val log: Logger = LoggerFactory.getLogger(javaClass)
    protected val uploadDir: Path = Paths.get(uploadDirStr).toAbsolutePath().normalize()

    init {
        try {
            Files.createDirectories(uploadDir)
        } catch (e: IOException) {
            throw OcrException.FileSaveException("OCR 이미지 디렉터리를 생성할 수 없습니다.", e)
        }
    }

    fun enqueueJob(
        file: MultipartFile,
        fcmToken: String?,
        delay: Long? = null
    ): OcrJobResponse {
        if (file.isEmpty) {
            throw OcrException.EmptyFileException()
        }

        val originalFilename = file.originalFilename ?: ""
        val extension = originalFilename.substringAfterLast('.', "")
            .replace(EXTENSION_REGEX, "")
            .lowercase()

        val safeExtension = when (extension) {
            "jpg", "jpeg" -> "jpg"
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
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

        try {
            processJob(jobId, targetPath, uniqueFileName, fcmToken, delay)
        } catch (e: TaskRejectedException) {
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

    abstract fun processJob(
        jobId: String,
        targetPath: Path,
        uniqueFileName: String,
        fcmToken: String?,
        delay: Long?
    )

    fun getJob(jobId: String): OcrJobResponse? = ocrJobRepository.getJob(jobId)

    fun cancelJob(jobId: String) {
        val job = ocrJobRepository.getJob(jobId)
            ?: throw OcrException.JobNotFoundException("존재하지 않는 작업ID: $jobId")

        if (job.status == com.example.yakallim.ocr.model.JobStatus.COMPLETED || job.status == com.example.yakallim.ocr.model.JobStatus.FAILED) {
            throw OcrException.IllegalJobStateException("이미 종료된 작업은 취소할 수 없습니다. (작업ID: $jobId, 상태: ${job.status})")
        }

        ocrJobRepository.updateToCancelled(jobId)
        log.info("OCR job cancellation requested: jobId='{}' (previous status: {})", jobId, job.status)
    }

    companion object {
        private val EXTENSION_REGEX = Regex("[^a-zA-Z0-9]")
    }
}
