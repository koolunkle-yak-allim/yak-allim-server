package com.example.yakallim.ocr.dto

import com.example.yakallim.ocr.model.PrescribedMedicine

data class OcrResponse(
    val message: String,
    val medicines: List<PrescribedMedicine> = emptyList()
)
