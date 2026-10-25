package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResult
import com.example.yakallim.ocr.model.JobStatus
import java.time.Instant

/**
 * OCR 작업 상태 저장소. `ocr.job-store`로 구현을 고른다.
 * - `memory`(기본값): 서버 메모리. 로컬 실행과 테스트용으로, 서버를 다시 시작하면 작업이 사라진다.
 * - `redis`: 서버 컨테이너 밖의 Redis. 재배포·블루-그린 전환 중에도 작업이 남는다.
 */
interface OcrJobRepository {
    fun registerJob(jobId: String): OcrJobResponse
    // 모든 전이는 진행 중(ACCEPTED/PROCESSING)인 작업에만 적용되며, 실제로 적용됐는지를 반환한다.
    // 여러 서버 인스턴스가 같은 작업을 동시에 바꿔도 하나만 성공한다.
    fun updateToProcessing(jobId: String): Boolean
    fun updateToCompleted(jobId: String, result: OcrResult): Boolean
    fun updateToFailed(jobId: String, errorMessage: String): Boolean
    fun updateToCancelled(jobId: String): Boolean
    fun getJob(jobId: String): OcrJobResponse?
    fun isCancelled(jobId: String): Boolean
    /** 지정한 상태들 중 하나이면서 마지막 갱신 시각이 [cutoff]보다 오래된 작업의 ID 목록. */
    fun findJobIdsInStatusUpdatedBefore(statuses: Set<JobStatus>, cutoff: Instant): List<String>
    fun deleteJob(jobId: String)
    /** 작업이 끝나면 완료·실패 알림을 보낼 FCM 토큰을 작업에 붙여 둔다. 없는 작업이면 무시한다. */
    fun saveFcmToken(jobId: String, token: String)
    /** FCM 토큰을 꺼내면서 지운다. 같은 작업에 알림이 두 번 가지 않도록 꺼내는 쪽은 하나뿐이다. */
    fun takeFcmToken(jobId: String): String?
}
