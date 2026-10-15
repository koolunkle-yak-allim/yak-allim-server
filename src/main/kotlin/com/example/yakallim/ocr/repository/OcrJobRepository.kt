package com.example.yakallim.ocr.repository

import com.example.yakallim.ocr.dto.OcrJobResponse
import com.example.yakallim.ocr.dto.OcrResponse

interface OcrJobRepository {
    fun registerJob(jobId: String): OcrJobResponse
    // 모든 전이는 진행 중(ACCEPTED/PROCESSING)인 작업에만 적용되며, 실제로 적용됐는지를 반환한다.
    fun updateToProcessing(jobId: String): Boolean
    fun updateToCompleted(jobId: String, result: OcrResponse): Boolean
    fun updateToFailed(jobId: String, errorMessage: String): Boolean
    fun updateToCancelled(jobId: String): Boolean
    fun getJob(jobId: String): OcrJobResponse?
    fun isCancelled(jobId: String): Boolean
}