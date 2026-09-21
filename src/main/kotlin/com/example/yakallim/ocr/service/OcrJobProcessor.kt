package com.example.yakallim.ocr.service

import com.example.yakallim.notification.service.PushNotificationClient
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.engine.OcrEngine
import com.example.yakallim.ocr.exception.OcrErrorMessageResolver
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.parser.PrescriptionParser
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.util.StopWatch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Component
class OcrJobProcessor(
    private val ocrEngine: OcrEngine,
    private val ocrJobRepository: OcrJobRepository,
    private val prescriptionParser: PrescriptionParser,
    @param:Qualifier("FCM_CLIENT") private val notifier: PushNotificationClient,
    private val ocrProgressManager: OcrProgressManager,
    @param:Value("\${ocr.upload-dir:outputs/api-images}") private val uploadDirStr: String
) {
    private val log = LoggerFactory.getLogger(OcrJobProcessor::class.java)
    private val baseDir = Paths.get(uploadDirStr).toAbsolutePath().normalize()

    @Async
    fun executeTask(
        jobId: String,
        path: Path,
        fileName: String,
        token: String?,
        delay: Long? = null
    ) {
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!normalizedPath.startsWith(baseDir)) {
            throw SecurityException("Access denied: Invalid file path.")
        }

        try {
            delay?.takeIf { it > 0 }?.let {
                try {
                    Thread.sleep(it)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            if (ocrJobRepository.isCancelled(jobId)) {
                log.info("OCR job cancelled before processing: jobId='{}'", jobId)
                ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, "작업이 취소되었습니다.")
                return
            }

            ocrJobRepository.updateToProcessing(jobId)
            ocrProgressManager.publishProgress(jobId, PipelineStep.IMAGE_PROCESSING)

            val stopwatch = StopWatch(jobId)

            check(!ocrJobRepository.isCancelled(jobId)) { "ONNX 추론 전 취소됨" }

            stopwatch.start("ONNX 추론")
            val textBlocks = Files.newInputStream(path).use { ocrEngine.runOcr(it, jobId) }
            stopwatch.stop()

            check(!ocrJobRepository.isCancelled(jobId)) { "구조화 파싱 전 취소됨" }

            ocrProgressManager.publishProgress(jobId, PipelineStep.PARSING)

            stopwatch.start("구조화 파싱")
            val medicines = prescriptionParser.parse(textBlocks)
            stopwatch.stop()

            log.info("\n${stopwatch.prettyPrint()}")

            val response = OcrResponse(
                fileName = fileName,
                message = "복약 안내서 분석이 완료되었습니다.\n복약 지침을 확인해 보세요.",
                textBlocks = textBlocks,
                medicines = medicines
            )

            ocrProgressManager.publishProgress(jobId, PipelineStep.COMPLETED, response.message)
            ocrJobRepository.updateToCompleted(jobId, response)

            log.info("Prescription OCR processing completed: {}", fileName)
            medicines.forEachIndexed { idx, item ->
                log.info(
                    "  [{}] 약품명: '{}', 복용량: '{}', 하루 횟수: '{}회', 복용 기간: '{}일'",
                    idx, item.medicineName, item.dosagePerTake, item.dailyFrequency, item.durationDays
                )
            }

            check(!ocrJobRepository.isCancelled(jobId)) { "알림 전송 전 취소됨" }

            notifier.notify(
                token = token ?: "",
                title = "복약 안내서 분석 완료",
                body = response.message,
                data = mapOf("jobId" to jobId, "status" to "COMPLETED", "message" to response.message)
            )
        } catch (e: IllegalStateException) {
            log.info("OCR job cancelled: jobId='{}', reason='{}'", jobId, e.message)
            ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, "작업이 취소되었습니다.")
        } catch (e: Exception) {
            val rawErrorMessage = e.message ?: "알 수 없는 오류가 발생했습니다."
            val userFacingMessage = OcrErrorMessageResolver.resolve(e)
            log.error("Async OCR processing failed for file: {}", fileName, e)
            ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, userFacingMessage)
            ocrJobRepository.updateToFailed(jobId, rawErrorMessage)
            notifier.notify(
                token = token ?: "",
                title = "복약 안내서 분석 실패",
                body = userFacingMessage,
                data = mapOf(
                    "jobId" to jobId,
                    "status" to "FAILED",
                    "errorCode" to "OCR_PROCESSING_FAILED",
                    "message" to userFacingMessage
                )
            )
        } finally {
            runCatching { Files.deleteIfExists(normalizedPath) }
                .onFailure { log.warn("Failed to delete uploaded prescription image: {}", normalizedPath, it) }
        }
    }
}
