package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.dto.OcrProgressResponse
import com.example.yakallim.ocr.model.JobStatus
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.repository.OcrJobRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 작업별 SSE 연결을 관리한다. 진행 이벤트는 [ProgressEventBus]로 모든 서버 인스턴스에 보내고,
 * 각 인스턴스는 받은 이벤트를 자기에게 연결된 SSE에만 전달한다. SSE 연결은 인스턴스 메모리에 있지만,
 * 나중에 연결한 클라이언트에게 보낼 현재 단계는 작업 저장소의 상태에서 정한다.
 */
@Component
class OcrProgressManager(
    private val ocrJobRepository: OcrJobRepository,
    private val progressEventBus: ProgressEventBus
) {
    private val log = LoggerFactory.getLogger(OcrProgressManager::class.java)

    private val emittersMap = ConcurrentHashMap<String, MutableList<SseEmitter>>()

    init {
        progressEventBus.subscribe { event -> deliver(event.jobId, event.progress) }
    }

    private val keepAliveScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sse-keep-alive-scheduler").apply { isDaemon = true }
    }

    @PostConstruct
    fun startKeepAliveScheduler() {
        keepAliveScheduler.scheduleAtFixedRate({
            sendKeepAliveToAll()
        }, 15, 15, TimeUnit.SECONDS)
        log.info("SSE Keep-Alive Heartbeat scheduler started (interval: 15s)")
    }

    @PreDestroy
    fun stopKeepAliveScheduler() {
        keepAliveScheduler.shutdown()
        try {
            if (!keepAliveScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                keepAliveScheduler.shutdownNow()
            }
        } catch (_: InterruptedException) {
            keepAliveScheduler.shutdownNow()
        }
        log.info("SSE Keep-Alive Heartbeat scheduler stopped")
    }

    fun registerEmitter(jobId: String, timeoutMs: Long = 180000L): SseEmitter {
        val emitter = SseEmitter(timeoutMs)

        val job = ocrJobRepository.getJob(jobId)
        if (job != null && (job.status == JobStatus.COMPLETED ||
                    job.status == JobStatus.FAILED ||
                    job.status == JobStatus.CANCELLED)
        ) {
            val step = when (job.status) {
                JobStatus.COMPLETED -> PipelineStep.COMPLETED
                JobStatus.CANCELLED -> PipelineStep.CANCELLED
                else -> PipelineStep.FAILED
            }
            val message = when (job.status) {
                JobStatus.COMPLETED -> job.result?.message ?: step.defaultMessage
                JobStatus.FAILED -> job.error ?: step.defaultMessage
                else -> step.defaultMessage
            }
            val payload = OcrProgressResponse(
                step = step,
                message = message,
                progress = 100,
                isFinished = true
            )
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("connect")
                        .reconnectTime(3000L)
                        .data("Connected to progress stream for job: $jobId")
                )
                sendToEmitter(emitter, payload)
                emitter.complete()
            } catch (_: IOException) {
                emitter.complete()
            }
            return emitter
        }

        emittersMap.computeIfAbsent(jobId) { CopyOnWriteArrayList() }.add(emitter)

        emitter.onCompletion {
            removeEmitter(jobId, emitter)
        }
        emitter.onTimeout {
            emitter.complete()
            removeEmitter(jobId, emitter)
        }
        emitter.onError {
            emitter.complete()
            removeEmitter(jobId, emitter)
        }

        try {
            emitter.send(
                SseEmitter.event()
                    .name("connect")
                    .reconnectTime(3000L)
                    .data("Connected to progress stream for job: $jobId")
            )
        } catch (_: IOException) {
            emitter.complete()
            removeEmitter(jobId, emitter)
            return emitter
        }

        // 진행 중인 작업이면 지금 단계를 바로 한 번 보낸다. n8n으로 보내는 중(PROCESSING)부터 분석 중으로 본다.
        val current = when (job?.status) {
            JobStatus.ACCEPTED -> PipelineStep.ACCEPTED
            JobStatus.PROCESSING -> PipelineStep.ANALYZING
            else -> null
        }
        if (current != null) {
            try {
                sendToEmitter(emitter, progressOf(current))
            } catch (_: Exception) {
                emitter.completeSafely()
                removeEmitter(jobId, emitter)
            }
        }

        return emitter
    }

    fun publishProgress(jobId: String, step: PipelineStep, message: String? = null, progress: Int? = null) {
        val payload = progressOf(step, message, progress)
        log.info("Job [$jobId] Progress: step=$step, progress=${payload.progress}%, message='${payload.message}'")
        progressEventBus.publish(ProgressEvent(jobId, payload))
    }

    /** 이 인스턴스에 연결된 SSE에만 보낸다. [ProgressEventBus]가 이벤트를 받을 때 부른다. */
    private fun deliver(jobId: String, payload: OcrProgressResponse) {
        val isFinished = payload.isFinished
        val emitters = emittersMap[jobId] ?: return
        val failedEmitters = mutableListOf<SseEmitter>()

        for (emitter in emitters) {
            try {
                sendToEmitter(emitter, payload)
                if (isFinished) {
                    emitter.completeSafely()
                    failedEmitters.add(emitter)
                }
            } catch (_: Exception) {
                emitter.completeSafely()
                failedEmitters.add(emitter)
            }
        }

        if (failedEmitters.isNotEmpty()) {
            emitters.removeAll(failedEmitters)
            if (emitters.isEmpty()) {
                emittersMap.remove(jobId)
            }
        }

        if (isFinished) {
            emittersMap.remove(jobId)
        }
    }

    private fun sendKeepAliveToAll() {
        val activeJobs = emittersMap.keys.toList()
        for (jobId in activeJobs) {
            val emitters = emittersMap[jobId] ?: continue
            val failedEmitters = mutableListOf<SseEmitter>()

            for (emitter in emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"))
                } catch (_: Exception) {
                    emitter.completeSafely()
                    failedEmitters.add(emitter)
                }
            }

            if (failedEmitters.isNotEmpty()) {
                emitters.removeAll(failedEmitters)
                if (emitters.isEmpty()) {
                    emittersMap.remove(jobId)
                }
            }
        }
    }

    private fun progressOf(step: PipelineStep, message: String? = null, progress: Int? = null) = OcrProgressResponse(
        step = step,
        message = message ?: step.defaultMessage,
        progress = progress ?: step.defaultProgress,
        isFinished = step.isFinished
    )

    private fun sendToEmitter(emitter: SseEmitter, payload: OcrProgressResponse) {
        emitter.send(
            SseEmitter.event()
                .name("progress")
                .data(payload)
        )
    }

    private fun removeEmitter(jobId: String, emitter: SseEmitter) {
        val emitters = emittersMap[jobId] ?: return
        emitters.remove(emitter)
        if (emitters.isEmpty()) {
            emittersMap.remove(jobId)
        }
    }
}

private fun SseEmitter.completeSafely() {
    runCatching { this.complete() }
}
