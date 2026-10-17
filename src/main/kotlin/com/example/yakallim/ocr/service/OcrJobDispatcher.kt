package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.infrastructure.N8nOcrClient
import com.example.yakallim.ocr.model.FailureCode
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.OcrJobRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/** 업로드된 이미지를 전용 스레드풀에서 n8n으로 보내고, 전송 결과를 작업 상태·SSE·FCM에 반영한다. */
@Component
class OcrJobDispatcher(
    private val ocrJobRepository: OcrJobRepository,
    private val ocrProgressManager: OcrProgressManager,
    private val n8nOcrClient: N8nOcrClient,
    private val ocrJobNotifier: OcrJobNotifier
) {
    private val log = LoggerFactory.getLogger(OcrJobDispatcher::class.java)

    @Async("ocrTaskExecutor")
    fun dispatch(jobId: String, image: Path) {
        try {
            // 콜백이 오지 않을 때 처리 시간 초과(OcrJobCleanupScheduler)가 동작하려면 PROCESSING으로 전이돼야 한다.
            // 대기열에 있는 동안 취소된 작업은 전이가 거부되므로, 외부 OCR·LLM 비용이 들지 않도록 전송하지 않는다.
            if (!ocrJobRepository.updateToProcessing(jobId)) {
                log.info("Skipping n8n dispatch for job that is no longer active: jobId='{}'", jobId)
                return
            }

            n8nOcrClient.send(jobId, image)

            // 전송하는 동안 취소되거나 콜백이 먼저 도착했으면 ANALYZING을 발행하지 않는다.
            // 종료 뒤에 발행하면 종료 이벤트보다 늦게 나가고, 진행 상태 캐시에도 지워지지 않는 항목이 남는다.
            if (ocrJobRepository.getJob(jobId)?.status != JobStatus.PROCESSING) {
                log.info("Job finished while sending to n8n, skipping ANALYZING: jobId='{}'", jobId)
                return
            }
            ocrProgressManager.publishProgress(jobId, PipelineStep.ANALYZING)
        } catch (e: Exception) {
            log.error("Failed to send image to n8n: jobId='{}'", jobId, e)
            if (!ocrJobRepository.updateToFailed(jobId, DISPATCH_FAILED_MESSAGE)) {
                ocrJobNotifier.discard(jobId)
                return
            }
            ocrProgressManager.publishProgress(jobId, PipelineStep.FAILED, DISPATCH_FAILED_MESSAGE)
            ocrJobNotifier.notifyFailed(jobId, DISPATCH_FAILED_MESSAGE, FailureCode.N8N_DISPATCH_FAILED)
        } finally {
            runCatching { Files.deleteIfExists(image) }
                .onFailure { log.warn("Failed to delete uploaded prescription image: {}", image, it) }
        }
    }

    companion object {
        private const val DISPATCH_FAILED_MESSAGE = "복약 안내서 분석 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
    }
}
