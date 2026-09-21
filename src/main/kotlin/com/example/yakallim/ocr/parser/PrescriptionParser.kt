package com.example.yakallim.ocr.parser

import com.example.yakallim.global.utils.HangulUtils
import com.example.yakallim.medicine.service.MedicineService
import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.model.BoundingBox
import com.example.yakallim.ocr.model.Point
import com.example.yakallim.ocr.model.Polygon
import com.example.yakallim.ocr.model.PrescribedMedicine
import com.example.yakallim.ocr.model.TextBlock
import org.springframework.stereotype.Component
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Component
class PrescriptionParser(
    private val ocrProperties: OcrProperties,
    private val medicineService: MedicineService,
) {

    companion object {
        private val DOSING_PATTERN = Regex("(\\d+(?:\\.\\d+)?)\\s*([^0-9\\s/]+)?")

        private const val TYPO_11_TO_1 = "11"
        private const val STANDARD_ONE = "1"

        private const val FREQUENCY_DIST_THRESHOLD = 1
        private const val DURATION_DIST_THRESHOLD = 2
        private const val DOSING_UNIT_DIST_THRESHOLD = 2
        private const val HEADER_DIST_THRESHOLD = 2
        private const val INSTRUCTION_NOISE_PENALTY_MULTIPLIER = 0.01

        private val COLUMN_HEADER_GUIDE_JAMO = HangulUtils.normalizeToJamo("복약안내")
        private val DOSING_TAKE_UNIT_JAMO = HangulUtils.normalizeToJamo("씩")
        private val DOSING_FREQUENCY_UNIT_JAMO = HangulUtils.normalizeToJamo("회")
        private val DOSING_DURATION_UNIT_JAMO = HangulUtils.normalizeToJamo("일분")
        private val DOSING_FORM_UNIT_JAMO_MAP = setOf("정", "캡슐").associateWith { HangulUtils.normalizeToJamo(it) }
    }

    fun parse(textBlocks: List<TextBlock>): List<PrescribedMedicine> {
        if (textBlocks.isEmpty()) return emptyList()

        val tiltAngle = calculateTiltAngle(textBlocks)
        val deskewed = if (abs(tiltAngle) > 1e-4) {
            val coordinates = textBlocks.flatMap { it.bounds }
            val boundingBox = BoundingBox.from(coordinates)
            val centerX = boundingBox.centerX.toDouble()
            val centerY = boundingBox.centerY.toDouble()

            textBlocks.map { result ->
                TextBlock(
                    text = result.text,
                    confidence = result.confidence,
                    bounds = rotate(result.bounds, -tiltAngle, centerX, centerY)
                )
            }
        } else {
            textBlocks
        }

        val blocks = deskewed.map { TextSegment(it.text, it.confidence, it.bounds) }

        val separatorX = findSeparatorX(blocks)
        val (nameBlocks, guideBlocks) = blocks.partition { it.box.minX < separatorX }

        val mergedNames = mergeBlocks(nameBlocks)
        val mergedGuides = mergeBlocks(guideBlocks)

        val sortedGuides = mergedGuides.filter { hasDosing(it.text) }.sortedBy { it.box.centerY }

        return sortedGuides.map { guideBlock ->
            val centerY = guideBlock.box.centerY
            val matchedName = findNameBlock(mergedNames, centerY)

            val extractedName = matchedName?.text ?: ""
            val standardName = medicineService.findStandardName(extractedName)
            val dosing = extractDosing(guideBlock.text)

            val bounds = listOfNotNull(
                matchedName?.bounds?.let { Polygon(it) },
                Polygon(guideBlock.bounds)
            )

            PrescribedMedicine(
                medicineName = standardName,
                dosagePerTake = dosing.dosagePerTake,
                dailyFrequency = dosing.dailyFrequency,
                durationDays = dosing.durationDays,
                rawName = extractedName,
                autoCorrected = standardName != extractedName,
                confidence = matchedName?.confidence ?: 0f,
                bounds = bounds
            )
        }
    }

    private fun findSeparatorX(blocks: List<TextSegment>): Int {
        return blocks.firstOrNull { block ->
            val normalizedJamo = HangulUtils.normalizeToJamo(block.text.replace(" ", ""))
            HangulUtils.levenshteinDistanceTo(normalizedJamo, COLUMN_HEADER_GUIDE_JAMO) <= HEADER_DIST_THRESHOLD
        }?.box?.minX ?: ocrProperties.parser.columnSeparatorX
    }

    private fun findNameBlock(mergedNames: List<TextSegment>, guideBlockCenterY: Int): TextSegment? {
        val expectedNameBlockCenterY = guideBlockCenterY + ocrProperties.parser.yOffset
        return mergedNames.filter { block ->
            block.box.minX in ocrProperties.parser.medicineMinX..ocrProperties.parser.medicineMaxX && abs(block.box.centerY - guideBlockCenterY) <= ocrProperties.parser.yDeviationThreshold
        }.maxByOrNull { candidate ->
            val deviation = abs(candidate.box.centerY - expectedNameBlockCenterY).toDouble()
            val alignmentScore =
                (1.0 - (deviation / ocrProperties.parser.yDeviationThreshold.toDouble())).coerceAtLeast(0.0)

            val normalizedName = candidate.normalizedName
            val containsInstructionNoise =
                normalizedName.contains(DOSING_TAKE_UNIT_JAMO) || normalizedName.contains(DOSING_FREQUENCY_UNIT_JAMO) || normalizedName.contains(
                    DOSING_DURATION_UNIT_JAMO
                )

            val noisePenaltyScore = if (containsInstructionNoise) INSTRUCTION_NOISE_PENALTY_MULTIPLIER else 1.0
            val ocrConfidenceScore = candidate.confidence.toDouble()

            alignmentScore * ocrConfidenceScore * noisePenaltyScore
        }
    }

    private fun extractDosing(text: String): DosingRegimen {
        if (text.isEmpty()) return DosingRegimen(null, null, null)

        val rawMetrics = DOSING_PATTERN.findAll(text).map {
            DosingToken(
                value = it.groupValues[1],
                unit = it.groupValues[2].ifEmpty { "" }
            )
        }.toList()

        if (rawMetrics.size >= 3) {
            val dosage = rawMetrics[0]
            val frequency = rawMetrics[1]
            val duration = rawMetrics[2]

            val parsedDosageAmount = formatDosing(null, sanitizeNumber(dosage.value), dosage.unit)
            val parsedDailyFrequency = sanitizeNumber(frequency.value).toIntOrNull()
            val parsedDurationDays = sanitizeNumber(duration.value).toIntOrNull()

            return DosingRegimen(parsedDosageAmount, parsedDailyFrequency, parsedDurationDays)
        }
        return parseRawDosing(rawMetrics)
    }

    private fun parseRawDosing(rawMetrics: List<DosingToken>): DosingRegimen {
        var dosageAmount: String? = null
        var dailyFrequency: Int? = null
        var durationDays: Int? = null

        for (metric in rawMetrics) {
            val metricType = getDosingField(metric.unit)
            val sanitizedValue = sanitizeNumber(metric.value)

            when (metricType) {
                DosingFieldType.DOSAGE_AMOUNT -> {
                    dosageAmount = formatDosing(dosageAmount, sanitizedValue, metric.unit)
                }

                DosingFieldType.DAILY_FREQUENCY -> {
                    if (dailyFrequency == null) dailyFrequency = sanitizedValue.toIntOrNull()
                }

                DosingFieldType.TOTAL_DURATION_DAYS -> {
                    if (durationDays == null) durationDays = sanitizedValue.toIntOrNull()
                }
            }
        }
        return DosingRegimen(dosageAmount, dailyFrequency, durationDays)
    }

    private fun sanitizeNumber(numericString: String): String =
        if (numericString == TYPO_11_TO_1) STANDARD_ONE else numericString

    private fun formatDosing(currentDosageAmount: String?, numericValue: String, unitSuffix: String): String {
        return if (currentDosageAmount == null || unitSuffix.isNotEmpty()) {
            val correctedUnit = correctUnit(unitSuffix) ?: ""
            "$numericValue$correctedUnit"
        } else {
            currentDosageAmount
        }
    }

    private fun hasDosing(text: String): Boolean {
        val detectedMetricTypes =
            DOSING_PATTERN.findAll(text).map { getDosingField(it.groupValues[2].ifEmpty { "" }) }.toSet()
        return detectedMetricTypes.size >= 2
    }

    private fun getDosingField(unitSuffix: String): DosingFieldType {
        val sanitizedSuffix = unitSuffix.trim()
        if (sanitizedSuffix.isEmpty() || (sanitizedSuffix.length == 1 && sanitizedSuffix[0] in '0'..'9')) {
            return DosingFieldType.DOSAGE_AMOUNT
        }

        val decomposedSuffix = HangulUtils.normalizeToJamo(sanitizedSuffix)

        if (decomposedSuffix.contains(DOSING_FREQUENCY_UNIT_JAMO) || HangulUtils.levenshteinDistanceTo(
                decomposedSuffix,
                DOSING_FREQUENCY_UNIT_JAMO
            ) <= FREQUENCY_DIST_THRESHOLD
        ) {
            return DosingFieldType.DAILY_FREQUENCY
        }

        if (decomposedSuffix.contains(DOSING_DURATION_UNIT_JAMO) || HangulUtils.levenshteinDistanceTo(
                decomposedSuffix, DOSING_DURATION_UNIT_JAMO
            ) <= DURATION_DIST_THRESHOLD
        ) {
            return DosingFieldType.TOTAL_DURATION_DAYS
        }

        return DosingFieldType.DOSAGE_AMOUNT
    }

    private fun correctUnit(unitSuffix: String): String? {
        val jamoSuffix = HangulUtils.normalizeToJamo(unitSuffix.trim())
        if (jamoSuffix.isEmpty()) return null

        val cleanedJamo = if (jamoSuffix.endsWith(DOSING_TAKE_UNIT_JAMO) || HangulUtils.levenshteinDistanceTo(
                jamoSuffix.takeLast(DOSING_TAKE_UNIT_JAMO.length), DOSING_TAKE_UNIT_JAMO
            ) <= 1
        ) {
            jamoSuffix.dropLast(DOSING_TAKE_UNIT_JAMO.length)
        } else {
            jamoSuffix
        }

        if (cleanedJamo.isEmpty()) return null

        val match = DOSING_FORM_UNIT_JAMO_MAP.map { (standardUnit, decomposedUnit) ->
            val distance = if (cleanedJamo.contains(decomposedUnit) || decomposedUnit.contains(cleanedJamo)) {
                0
            } else {
                HangulUtils.levenshteinDistanceTo(cleanedJamo, decomposedUnit)
            }
            standardUnit to distance
        }.minByOrNull { it.second }

        return if (match != null && match.second <= DOSING_UNIT_DIST_THRESHOLD) match.first else null
    }

    private fun calculateTiltAngle(textBlocks: List<TextBlock>): Double {
        val angles = textBlocks.mapNotNull { block ->
            val box = block.bounds
            if (box.size == 4) {
                val dx = (box[1].x - box[0].x).toDouble()
                val dy = (box[1].y - box[0].y).toDouble()
                if (dx > 50.0) atan2(dy, dx) else null
            } else null
        }
        return if (angles.isNotEmpty()) angles.sorted()[angles.size / 2] else 0.0
    }

    private fun rotate(
        coordinates: List<Point>, tiltAngleRadian: Double, centerX: Double, centerY: Double
    ): List<Point> {
        val cosValue = cos(tiltAngleRadian)
        val sinValue = sin(tiltAngleRadian)
        return coordinates.map { point ->
            val dx = point.x - centerX
            val dy = point.y - centerY
            val rotatedX = dx * cosValue - dy * sinValue + centerX
            val rotatedY = dx * sinValue + dy * cosValue + centerY
            Point(rotatedX.toInt(), rotatedY.toInt())
        }
    }

    private fun mergeBlocks(processedOcrBlocks: List<TextSegment>): List<TextSegment> {
        if (processedOcrBlocks.size < 2) return processedOcrBlocks

        val sortedBlocks = processedOcrBlocks.sortedWith(compareBy({ it.box.minY }, { it.box.minX }))
        val mergedBlocks = mutableListOf<TextSegment>()
        val isUsed = BooleanArray(sortedBlocks.size)

        for (i in sortedBlocks.indices) {
            if (isUsed[i]) continue
            var currentBlock = sortedBlocks[i]
            isUsed[i] = true

            var hasMerged: Boolean
            do {
                hasMerged = false
                val targetIndex = findMergeableIndex(currentBlock, sortedBlocks, isUsed)
                if (targetIndex != -1) {
                    currentBlock = merge(currentBlock, sortedBlocks[targetIndex])
                    isUsed[targetIndex] = true
                    hasMerged = true
                }
            } while (hasMerged)

            mergedBlocks.add(currentBlock)
        }

        return mergedBlocks
    }

    private fun findMergeableIndex(
        currentBlock: TextSegment, sortedBlocks: List<TextSegment>, isUsed: BooleanArray
    ): Int {
        val currentBox = currentBlock.box
        val currentHeight = currentBox.maxY - currentBox.minY

        for (j in sortedBlocks.indices) {
            if (isUsed[j]) continue
            val targetBlock = sortedBlocks[j]
            val targetBox = targetBlock.box

            val overlapStart = maxOf(currentBox.minY, targetBox.minY)
            val overlapEnd = minOf(currentBox.maxY, targetBox.maxY)
            val overlapHeight = overlapEnd - overlapStart
            val isSameRow = overlapHeight > 0 && overlapHeight >= (currentHeight * 0.4)

            if (isSameRow) {
                val isClose =
                    (targetBox.minX >= currentBox.maxX && (targetBox.minX - currentBox.maxX) <= 15) || (currentBox.minX >= targetBox.maxX && (currentBox.minX - targetBox.maxX) <= 15)
                if (isClose) {
                    return j
                }
            }
        }
        return -1
    }

    private fun merge(currentBlock: TextSegment, targetBlock: TextSegment): TextSegment {
        val currentBox = currentBlock.box
        val targetBox = targetBlock.box

        val mergedMinX = minOf(currentBox.minX, targetBox.minX)
        val mergedMaxX = maxOf(currentBox.maxX, targetBox.maxX)
        val mergedMinY = minOf(currentBox.minY, targetBox.minY)
        val mergedMaxY = maxOf(currentBox.maxY, targetBox.maxY)

        val mergedBoundingBox = listOf(
            Point(mergedMinX, mergedMinY),
            Point(mergedMaxX, mergedMinY),
            Point(mergedMaxX, mergedMaxY),
            Point(mergedMinX, mergedMaxY)
        )

        val mergedText = if (targetBox.minX >= currentBox.maxX) {
            "${currentBlock.text} ${targetBlock.text}"
        } else {
            "${targetBlock.text} ${currentBlock.text}"
        }.replace("\\s+".toRegex(), " ").trim()

        return TextSegment(
            text = mergedText,
            confidence = (currentBlock.confidence + targetBlock.confidence) / 2.0f,
            bounds = mergedBoundingBox
        )
    }
}

private data class TextSegment(
    val text: String,
    val confidence: Float,
    val bounds: List<Point>,
    val normalizedName: String = HangulUtils.normalizeToJamo(text)
) {
    val box: BoundingBox = BoundingBox.from(bounds)
}

private data class DosingToken(
    val value: String,
    val unit: String
)

private data class DosingRegimen(
    val dosagePerTake: String?,
    val dailyFrequency: Int?,
    val durationDays: Int?
)

private enum class DosingFieldType {
    DOSAGE_AMOUNT,
    DAILY_FREQUENCY,
    TOTAL_DURATION_DAYS
}