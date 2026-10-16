package com.example.yakallim.ocr.dto

import com.example.yakallim.ocr.model.JobStatus

data class OcrJobResponse(
    val jobId: String,
    val status: JobStatus,
    val result: OcrResult? = null,
    val error: String? = null
)