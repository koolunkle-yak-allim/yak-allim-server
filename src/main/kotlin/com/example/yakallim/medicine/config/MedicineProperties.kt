package com.example.yakallim.medicine.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "medicine.standard")
data class MedicineProperties(
    val dataPath: String = "data/medicines.csv",
    val maxDistanceRatio: Double = 0.25,        // 편집거리 / 긴 쪽 자모 길이
    val minSubsequenceCoverage: Double = 0.6,   // 글자 누락(부분수열)일 때 최소 포함 비율
    val minMargin: Int = 2                      // 1위와 2위 후보 편집거리 차이
)
