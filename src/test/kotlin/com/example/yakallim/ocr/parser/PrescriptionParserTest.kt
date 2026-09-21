package com.example.yakallim.ocr.parser

import com.example.yakallim.ocr.model.Point
import com.example.yakallim.ocr.model.TextBlock
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class PrescriptionParserTest {

    @Autowired
    private lateinit var prescriptionParser: PrescriptionParser

    // 테스트 프로퍼티(비율) * imageWidth=1920 기준: column-separator-x=600, medicine-min-x=130, medicine-max-x=260
    private fun bounds(minX: Int, maxX: Int, minY: Int, maxY: Int): List<Point> = listOf(
        Point(minX, minY), Point(maxX, minY), Point(maxX, maxY), Point(minX, maxY)
    )

    @Test
    @DisplayName("사전 항목과 가까운 약품명은 표준명으로 교정되고 원문·교정여부·신뢰도가 채워진다")
    fun shouldAutoCorrectCloseMatchAndPopulateNewFields() {
        val textBlocks = listOf(
            TextBlock("이모튼캡", 0.87f, bounds(150, 250, 80, 120)),
            TextBlock("1정 3회 3일", 1.0f, bounds(700, 900, 80, 120))
        )

        val medicines = prescriptionParser.parse(textBlocks, imageWidth = 1920)

        val medicine = medicines.single()
        Assertions.assertEquals("이모튼캡슐", medicine.medicineName)
        Assertions.assertEquals("이모튼캡", medicine.rawName)
        Assertions.assertEquals(true, medicine.autoCorrected)
        Assertions.assertEquals(0.87f, medicine.confidence)
    }

    @Test
    @DisplayName("사전에 없는 약품명은 원문을 유지하고 autoCorrected가 false다")
    fun shouldKeepRawNameWhenNotInDictionary() {
        val textBlocks = listOf(
            TextBlock("테스트약품", 0.75f, bounds(150, 250, 80, 120)),
            TextBlock("1정 3회 3일", 1.0f, bounds(700, 900, 80, 120))
        )

        val medicines = prescriptionParser.parse(textBlocks, imageWidth = 1920)

        val medicine = medicines.single()
        Assertions.assertEquals("테스트약품", medicine.medicineName)
        Assertions.assertEquals("테스트약품", medicine.rawName)
        Assertions.assertEquals(false, medicine.autoCorrected)
        Assertions.assertEquals(0.75f, medicine.confidence)
    }
}
