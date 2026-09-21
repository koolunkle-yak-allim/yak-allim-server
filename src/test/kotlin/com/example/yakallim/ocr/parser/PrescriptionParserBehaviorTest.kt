package com.example.yakallim.ocr.parser

import com.example.yakallim.ocr.model.Point
import com.example.yakallim.ocr.model.TextBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.math.cos
import kotlin.math.sin

/**
 * S7: PrescriptionParser의 개별 파싱 단계(복용 정보 추출, 오타 보정, 기울기 보정, 헤더 탐지,
 * 이름-복용안내 행 매칭)를 ONNX 엔진 없이 합성 TextBlock으로 검증한다.
 *
 * 테스트 프로퍼티(비율) * imageWidth=1920 기준: column-separator-x=600, y-offset=20,
 * y-deviation-threshold=75, medicine-min-x=130, medicine-max-x=260.
 */
@SpringBootTest
class PrescriptionParserBehaviorTest {

    @Autowired
    private lateinit var prescriptionParser: PrescriptionParser

    private fun bounds(minX: Int, maxX: Int, minY: Int, maxY: Int): List<Point> = listOf(
        Point(minX, minY), Point(maxX, minY), Point(maxX, maxY), Point(minX, maxY)
    )

    @Test
    @DisplayName("복용 안내 텍스트에서 복용량·복용 횟수·복용 기간을 각각 추출한다")
    fun extractsDosagePerTakeDailyFrequencyAndDurationDays() {
        val textBlocks = listOf(
            TextBlock("테스트약품", 0.9f, bounds(150, 250, 80, 120)),
            TextBlock("2정 3회 5일", 1.0f, bounds(700, 900, 80, 120))
        )

        val medicine = prescriptionParser.parse(textBlocks, imageWidth = 1920).single()

        assertEquals("2정", medicine.dosagePerTake)
        assertEquals(3, medicine.dailyFrequency)
        assertEquals(5, medicine.durationDays)
    }

    @Test
    @DisplayName("OCR이 숫자 1을 11로 잘못 인식해도 복용량·횟수·기간 모두 1로 보정한다")
    fun correctsTypo11ToOneAcrossAllDosingFields() {
        val textBlocks = listOf(
            TextBlock("테스트약품", 0.9f, bounds(150, 250, 80, 120)),
            TextBlock("11정 11회 11일", 1.0f, bounds(700, 900, 80, 120))
        )

        val medicine = prescriptionParser.parse(textBlocks, imageWidth = 1920).single()

        assertEquals("1정", medicine.dosagePerTake)
        assertEquals(1, medicine.dailyFrequency)
        assertEquals(1, medicine.durationDays)
    }

    @Test
    @DisplayName("문서가 약간 기울어진 상태로 촬영되어도 기울기를 보정해 이름-복용안내 행을 올바르게 매칭한다")
    fun deskewsSlightlyTiltedDocumentAndStillMatchesNameToGuideRow() {
        val angleRadians = Math.toRadians(3.0)
        val allPoints = listOf(bounds(150, 250, 80, 120), bounds(700, 900, 80, 120)).flatten()
        val centerX = allPoints.map { it.x }.average()
        val centerY = allPoints.map { it.y }.average()

        fun rotate(points: List<Point>) = points.map { point ->
            val dx = point.x - centerX
            val dy = point.y - centerY
            Point(
                (dx * cos(angleRadians) - dy * sin(angleRadians) + centerX).toInt(),
                (dx * sin(angleRadians) + dy * cos(angleRadians) + centerY).toInt()
            )
        }

        val textBlocks = listOf(
            TextBlock("테스트약품", 0.9f, rotate(bounds(150, 250, 80, 120))),
            TextBlock("1정 3회 3일", 1.0f, rotate(bounds(700, 900, 80, 120)))
        )

        val medicine = prescriptionParser.parse(textBlocks, imageWidth = 1920).single()

        assertEquals("테스트약품", medicine.medicineName)
        assertEquals("1정", medicine.dosagePerTake)
        assertEquals(3, medicine.dailyFrequency)
        assertEquals(3, medicine.durationDays)
    }

    @Test
    @DisplayName("'복약안내' 헤더 텍스트를 발견하면 그 위치를 기준으로 이름/복용안내 열을 나눈다")
    fun detectsHeaderTextAndUsesItsPositionAsColumnSeparator() {
        // 복용안내 블록(minX=550)은 기본 설정값(columnSeparatorXRatio 기준 600)보다 왼쪽이라
        // 헤더를 못 찾으면 이름 열로 잘못 분류되어 파싱 결과가 비어야 한다.
        val withoutHeader = listOf(
            TextBlock("테스트약품", 0.9f, bounds(150, 250, 80, 120)),
            TextBlock("1정 3회 3일", 1.0f, bounds(550, 750, 80, 120))
        )
        assertTrue(
            prescriptionParser.parse(withoutHeader, imageWidth = 1920).isEmpty(),
            "헤더가 없을 때는 기본 분리선(600) 기준으로 550이 이름 열로 분류되어 결과가 비어야 합니다."
        )

        val withHeader = listOf(
            TextBlock("복약안내", 0.95f, bounds(500, 600, 10, 30)),
            TextBlock("테스트약품", 0.9f, bounds(150, 250, 80, 120)),
            TextBlock("1정 3회 3일", 1.0f, bounds(550, 750, 80, 120))
        )
        val medicine = prescriptionParser.parse(withHeader, imageWidth = 1920).single()

        assertEquals("테스트약품", medicine.medicineName)
        assertEquals("1정", medicine.dosagePerTake)
    }

    @Test
    @DisplayName("이름 후보가 여러 개일 때 복용안내 행과 Y좌표가 가장 가까운 이름을 매칭한다")
    fun matchesClosestNameCandidateByYDistanceWhenMultipleCandidatesExist() {
        // 복용안내 행 중심 Y=100, yOffset=20 => 기대 이름 블록 중심 Y=120.
        // 두 후보 모두 yDeviationThreshold(75) 이내라 후보로는 잡히지만, 120에 더 가까운
        // "가까운약품"(중심 Y=105)이 "먼약품"(중심 Y=50)보다 우선해서 매칭되어야 한다.
        val textBlocks = listOf(
            TextBlock("먼약품", 0.9f, bounds(150, 250, 30, 70)),
            TextBlock("가까운약품", 0.9f, bounds(150, 250, 85, 125)),
            TextBlock("1정 3회 3일", 1.0f, bounds(700, 900, 80, 120))
        )

        val medicine = prescriptionParser.parse(textBlocks, imageWidth = 1920).single()

        assertEquals("가까운약품", medicine.medicineName)
    }
}
