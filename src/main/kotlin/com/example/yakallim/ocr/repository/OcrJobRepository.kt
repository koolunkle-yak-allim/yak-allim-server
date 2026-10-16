package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse
import com.example.yakallim.ocr.model.JobStatus
import java.time.Instant

interface OcrJobRepository {
    fun registerJob(jobId: String): OcrJobResponse
    // 모든 전이는 진행 중(ACCEPTED/PROCESSING)인 작업에만 적용되며, 실제로 적용됐는지를 반환한다.
    fun updateToProcessing(jobId: String): Boolean
    fun updateToCompleted(jobId: String, result: OcrResponse): Boolean
    fun updateToFailed(jobId: String, errorMessage: String): Boolean
    fun updateToCancelled(jobId: String): Boolean
    fun getJob(jobId: String): OcrJobResponse?
    fun isCancelled(jobId: String): Boolean
    /** 지정한 상태들 중 하나이면서 마지막 갱신 시각이 [cutoff]보다 오래된 작업의 ID 목록. */
    fun findJobIdsInStatusUpdatedBefore(statuses: Set<JobStatus>, cutoff: Instant): List<String>
    fun deleteJob(jobId: String)
}