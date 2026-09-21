package com.example.yakallim.ocr.parser

import com.example.yakallim.ocr.model.Point
import com.example.yakallim.ocr.model.TextBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * S6: 좌표 관련 설정값이 이미지 해상도에 대한 비율로 정규화되어, 입력 이미지 크기가
 * 달라져도(예: 다른 기종으로 촬영) 동일한 상대 위치라면 같은 파싱 결과가 나오는지 확인한다.
 */
@SpringBootTest
class PrescriptionParserResolutionTest {

    @Autowired
    private lateinit var prescriptionParser: PrescriptionParser

    private fun bounds(minX: Int, maxX: Int, minY: Int, maxY: Int): List<Point> = listOf(
        Point(minX, minY), Point(maxX, minY), Point(maxX, maxY), Point(minX, maxY)
    )

    // 테스트 프로퍼티 기준 비율(0.3125/0.0104/0.0391/0.0677/0.1354)이 1920px 기준
    // 기존 픽셀값(600/20/75/130/260)과 같아지도록 맞춰져 있다.
    private fun baseTextBlocks() = listOf(
        TextBlock("테스트약품", 0.9f, bounds(150, 250, 80, 120)),
        TextBlock("1정 3회 3일", 1.0f, bounds(700, 900, 80, 120))
    )

    private fun scale(textBlocks: List<TextBlock>, factor: Double): List<TextBlock> = textBlocks.map { block ->
        TextBlock(
            text = block.text,
            confidence = block.confidence,
            bounds = block.bounds.map { Point((it.x * factor).toInt(), (it.y * factor).toInt()) }
        )
    }

    @Test
    @DisplayName("2배 고해상도로 촬영해도 동일한 파싱 결과가 나온다")
    fun sameDocumentAtDoubleResolutionYieldsSameResult() {
        val baseWidth = 1920
        val scaledWidth = baseWidth * 2

        val baseResult = prescriptionParser.parse(baseTextBlocks(), baseWidth)
        val scaledResult = prescriptionParser.parse(scale(baseTextBlocks(), 2.0), scaledWidth)

        assertEquals(1, baseResult.size)
        assertEquals(baseResult.map { it.medicineName }, scaledResult.map { it.medicineName })
        assertEquals(baseResult.map { it.dosagePerTake }, scaledResult.map { it.dosagePerTake })
        assertEquals(baseResult.map { it.dailyFrequency }, scaledResult.map { it.dailyFrequency })
        assertEquals(baseResult.map { it.durationDays }, scaledResult.map { it.durationDays })
    }

    @Test
    @DisplayName("절반 해상도로 축소 촬영해도 동일한 파싱 결과가 나온다")
    fun sameDocumentAtHalfResolutionYieldsSameResult() {
        val baseWidth = 1920
        val smallerWidth = baseWidth / 2

        val baseResult = prescriptionParser.parse(baseTextBlocks(), baseWidth)
        val smallerResult = prescriptionParser.parse(scale(baseTextBlocks(), 0.5), smallerWidth)

        assertEquals(baseResult.map { it.medicineName }, smallerResult.map { it.medicineName })
        assertEquals(baseResult.map { it.dosagePerTake }, smallerResult.map { it.dosagePerTake })
    }

    @Test
    @DisplayName("imageWidth가 0 이하로 주어지면 기본 기준 해상도(1920)로 대체한다")
    fun fallsBackToDefaultReferenceWidthWhenImageWidthInvalid() {
        val fallbackResult = prescriptionParser.parse(baseTextBlocks(), imageWidth = 0)
        val explicit1920Result = prescriptionParser.parse(baseTextBlocks(), imageWidth = 1920)

        assertEquals(explicit1920Result.map { it.medicineName }, fallbackResult.map { it.medicineName })
    }
}
