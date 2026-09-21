package com.example.yakallim.medicine.service

import com.example.yakallim.global.utils.HangulUtils
import com.example.yakallim.medicine.config.MedicineProperties
import com.example.yakallim.medicine.repository.MedicineRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MedicineService(
    private val medicineRepository: MedicineRepository,
    private val medicineProperties: MedicineProperties
) {

    companion object {
        private val nameCleanupRegex = Regex("[-_\\s]+$")
    }

    private data class Scored(val name: String, val normalizedName: String, val distance: Int)

    @Transactional(readOnly = true)
    fun findStandardName(rawName: String): String {
        val cleanedName = rawName.trim().replace(nameCleanupRegex, "").replace(" ", "")
        if (cleanedName.isEmpty()) return ""

        val normalizedName = HangulUtils.normalizeToJamo(cleanedName)
        val prefix = if (normalizedName.length >= 2) normalizedName.take(2) else normalizedName

        val candidates = if (prefix.isNotEmpty()) {
            val results = medicineRepository.findByNormalizedNameStartingWith(prefix)
            results.ifEmpty { medicineRepository.findAll() }
        } else {
            emptyList()
        }

        if (candidates.isEmpty()) return rawName

        val scored = candidates.map {
            Scored(it.name, it.normalizedName, HangulUtils.levenshteinDistanceTo(normalizedName, it.normalizedName))
        }.sortedBy { it.distance }

        val best = scored.first()
        val second = scored.getOrNull(1)

        val ratio = best.distance.toDouble() / maxOf(normalizedName.length, best.normalizedName.length)
        val coverage = normalizedName.length.toDouble() / best.normalizedName.length

        val closeEnough = ratio <= medicineProperties.maxDistanceRatio ||
            (HangulUtils.isSubsequence(normalizedName, best.normalizedName) &&
                coverage >= medicineProperties.minSubsequenceCoverage)
        val clearWinner = second == null || second.distance - best.distance >= medicineProperties.minMargin

        return if (closeEnough && clearWinner) best.name else rawName
    }
}
