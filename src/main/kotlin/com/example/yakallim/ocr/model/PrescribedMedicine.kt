package com.example.yakallim.ocr.model

data class PrescribedMedicine(
    val medicineName: String,
    val dosagePerTake: String? = null,
    val dailyFrequency: Int? = null,
    val durationDays: Int? = null
)
