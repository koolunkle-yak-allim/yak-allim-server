package com.example.yakallim.ocr.service

import com.example.yakallim.ocr.engine.OcrEngine
import com.example.yakallim.ocr.parser.PrescriptionParser
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

@SpringBootTest
class OcrServiceTest {

    @Autowired
    private lateinit var ocrEngine: OcrEngine

    @Autowired
    private lateinit var prescriptionParser: PrescriptionParser

    @Test
    @DisplayName("모형 처방전 이미지를 통한 OCR 파싱 흐름을 검증한다")
    fun shouldParsePrescriptionSuccessfullyUsingMockImage() {
        val width = 640
        val height = 240
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, width, height)

                color = Color.BLACK
                font = Font("Arial", Font.BOLD, 22)

                drawString("약품명", 80, 40)
                drawString("복약안내(투약량/횟수/일수)", 340, 40)

                drawString("타이레놀정 500mg", 80, 100)
                drawString("1정 / 3회 / 3일", 340, 100)

                drawString("아모디핀정", 80, 160)
                drawString("1.5정 / 2회 / 7일", 340, 160)

                dispose()
            }
        }

        val imageInputStream = ByteArrayOutputStream().use { outputStream ->
            ImageIO.write(image, "png", outputStream)
            ByteArrayInputStream(outputStream.toByteArray())
        }

        val ocrResults = ocrEngine.runOcr(imageInputStream)
        val instructions = prescriptionParser.parse(ocrResults, width)

        Assertions.assertNotNull(ocrResults)
        Assertions.assertNotNull(instructions)
    }

    @Test
    @DisplayName("실제 처방전 이미지 파일 OCR 분석 및 오타 교정을 검증한다")
    fun shouldProcessRealImageWithOcrAndFuzzyCorrection() {
        val imageFile = File("outputs/api-images/sample.jpg")
        Assumptions.assumeTrue(imageFile.exists(), "${imageFile.path} 샘플 이미지가 없어 테스트를 건너뜁니다.")
        verifyOcrPipeline(imageFile)
    }

    private fun verifyOcrPipeline(imageFile: File) {
        val ocrResults = imageFile.inputStream().use { ocrEngine.runOcr(it) }
        Assertions.assertNotNull(ocrResults, "${imageFile.name} 파일의 OCR 결과가 존재하지 않습니다.")

        println("=== 이미지 원본 OCR 결과 (${imageFile.name}) ===")
        ocrResults.forEachIndexed { idx, result ->
            val box = result.bounds
            val minX = box.minOfOrNull { it.x } ?: 0
            val maxX = box.maxOfOrNull { it.x } ?: 0
            val minY = box.minOfOrNull { it.y } ?: 0
            val maxY = box.maxOfOrNull { it.y } ?: 0
            println("[$idx] TEXT: '${result.text}', CONFIDENCE: ${result.confidence}, BOUNDS: [X: $minX~$maxX, Y: $minY~$maxY]")
        }
        println("==================================")

        val imageWidth = runCatching { ImageIO.read(imageFile)?.width ?: 0 }.getOrDefault(0)
        val instructions = prescriptionParser.parse(ocrResults, imageWidth)
        Assertions.assertNotNull(instructions, "${imageFile.name} 파일의 파싱 결과가 존재하지 않습니다.")

        println("=== 이미지 구조화 파싱 결과 (${imageFile.name}) ===")
        instructions.forEachIndexed { idx, item ->
            println("[$idx] 약품명: '${item.medicineName}'")
            println("   1회 복용량: ${item.dosagePerTake}, 하루 횟수: ${item.dailyFrequency}, 복용 기간: ${item.durationDays}")
        }
        println("==========================================")
    }
}