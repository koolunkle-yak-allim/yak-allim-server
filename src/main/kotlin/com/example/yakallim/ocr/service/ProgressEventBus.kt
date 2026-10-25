package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.dto.OcrProgressResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

data class ProgressEvent(val jobId: String, val progress: OcrProgressResponse)

/**
 * 진행 이벤트를 모든 서버 인스턴스에 전달한다. 각 인스턴스는 받은 이벤트를 자기에게 연결된 SSE에만 보낸다.
 * 블루-그린 전환 중에는 콜백을 받은 서버와 앱이 연결된 서버가 다를 수 있어서 필요하다.
 */
interface ProgressEventBus {
    fun publish(event: ProgressEvent)
    fun subscribe(listener: (ProgressEvent) -> Unit)
}

/** 서버가 하나일 때(`ocr.job-store=memory`)는 같은 프로세스 안에서 바로 전달한다. */
@Component
@ConditionalOnProperty(name = ["ocr.job-store"], havingValue = "memory", matchIfMissing = true)
class InMemoryProgressEventBus : ProgressEventBus {
    private val listeners = CopyOnWriteArrayList<(ProgressEvent) -> Unit>()

    override fun publish(event: ProgressEvent) {
        listeners.forEach { it(event) }
    }

    override fun subscribe(listener: (ProgressEvent) -> Unit) {
        listeners.add(listener)
    }
}
