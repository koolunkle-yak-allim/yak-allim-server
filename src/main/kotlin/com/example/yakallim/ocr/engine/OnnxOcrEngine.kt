package com.example.yakallim.ocr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.yakallim.ocr.config.OcrProperties
import com.example.yakallim.ocr.exception.OcrException
import com.example.yakallim.ocr.model.BoundingBox
import com.example.yakallim.ocr.model.PipelineStep
import com.example.yakallim.ocr.model.Point
import com.example.yakallim.ocr.model.TextBlock
import com.example.yakallim.ocr.service.OcrProgressManager
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.InputStream
import java.nio.FloatBuffer
import javax.imageio.ImageIO

@Component
@Profile("!test")
class OnnxOcrEngine(
    private val resourceLoader: ResourceLoader,
    private val ocrProperties: OcrProperties,
    private val ocrProgressManager: OcrProgressManager
) : OcrEngine, AutoCloseable {

    companion object {
        private const val DET_INPUT_SIZE = 640
        private const val REC_INPUT_WIDTH = 320
        private const val REC_INPUT_HEIGHT = 48

        private val IMAGENET_MEANS = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STDS = floatArrayOf(0.229f, 0.224f, 0.225f)

        private const val BOX_EXPAND_RATIO_X = 0.1
        private const val BOX_EXPAND_RATIO_Y = 0.15
        private const val BOX_MIN_PADDING = 2

        private const val MIN_CHAR_GAP_WIDTH = 8
        private const val MIN_PIXEL_COUNT = 15
        private const val MIN_DIMENSION_SIZE = 4
        private const val MIN_SEGMENT_WIDTH = 10
        private const val MIN_SEGMENTABLE_WIDTH = 100
        private const val MIN_SCAN_HEIGHT = 5

        private const val PROJECTION_PADDING_RATIO = 0.15
        private const val WHITESPACE_THRESHOLD_RATIO = 0.88
        private const val LUMINANCE_THRESHOLD = 220.0

        private const val CROP_PADDING_X = 4
        private const val CROP_PADDING_Y = 2

        private val NEIGHBOR_DX = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        private val NEIGHBOR_DY = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
    }

    private val log = LoggerFactory.getLogger(OnnxOcrEngine::class.java)

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var isReady = false

    override fun runOcr(imageStream: InputStream, jobId: String?): List<TextBlock> {
        if (!isReady) throw OcrException.EngineNotReadyException()
        return runCatching {
            val sourceImage = ImageIO.read(imageStream) ?: throw IllegalArgumentException("유효하지 않은 이미지 스트림입니다.")
            
            if (jobId != null) {
                ocrProgressManager.publishProgress(jobId, PipelineStep.TEXT_DETECTION)
            }
            
            val detectedTextRegions = detectRegions(sourceImage)
            val recognitionInputs = detectedTextRegions.flatMap { regionCoordinates ->
                val croppedRegionImage = cropRegion(sourceImage, regionCoordinates)
                val segmentedColumns = splitSegments(croppedRegionImage)
                val regionBoundingBox = BoundingBox.from(regionCoordinates)

                segmentedColumns.map { column ->
                    val segmentWidth = column.image.width
                    val segmentCoordinateBounds = listOf(
                        Point(regionBoundingBox.minX + column.offsetX, regionBoundingBox.minY),
                        Point(regionBoundingBox.minX + column.offsetX + segmentWidth, regionBoundingBox.minY),
                        Point(regionBoundingBox.minX + column.offsetX + segmentWidth, regionBoundingBox.maxY),
                        Point(regionBoundingBox.minX + column.offsetX, regionBoundingBox.maxY)
                    )
                    RecognitionInput(column.image, segmentCoordinateBounds)
                }
            }
            
            if (jobId != null) {
                ocrProgressManager.publishProgress(jobId, PipelineStep.TEXT_RECOGNITION)
            }

            runBlocking(Dispatchers.Default) {
                recognitionInputs.map { input ->
                    async {
                        val recognitionResult = recognize(input.image)
                        recognitionResult.text.takeIf { it.isNotBlank() }?.let {
                            TextBlock(it, recognitionResult.confidence, input.bounds)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }.onFailure { exception ->
            log.error("Failed to execute OCR inference", exception)
        }.getOrElse { exception ->
            throw RuntimeException("OCR 분석 처리 실패", exception)
        }
    }

    @PostConstruct
    fun initEngine() {
        runCatching {
            env = OrtEnvironment.getEnvironment()
            loadVocab()

            val detectionModelBytes = loadResourceAsBytes(ocrProperties.engine.onnx.detectionModelPath) ?: return
            val recognitionModelBytes = loadResourceAsBytes(ocrProperties.engine.onnx.recognitionModelPath) ?: return
            val availableThreadCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

            env?.let { environment ->
                val sessionConfiguration = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(availableThreadCount)
                }
                sessionConfiguration.use { options ->
                    detSession = environment.createSession(detectionModelBytes, options)
                    recSession = environment.createSession(recognitionModelBytes, options)
                }
                isReady = true
            }
        }.onFailure { exception ->
            log.error("Failed to initialize ONNX OCR engine", exception)
        }
    }

    private fun loadVocab() {
        runCatching {
            val dictionaryResource = resourceLoader.getResource(ocrProperties.engine.onnx.recognitionDictionaryPath)
            if (dictionaryResource.exists()) {
                dictionaryResource.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    vocab = buildList {
                        add("")
                        reader.lineSequence().forEach { line -> add(line.trim('\r', '\n')) }
                        add(" ")
                    }
                }
            }
        }.onFailure { exception ->
            log.error("Failed to load OCR character dictionary", exception)
        }
    }

    private fun loadResourceAsBytes(resourcePath: String): ByteArray? = runCatching {
        val targetResource = resourceLoader.getResource(resourcePath)
        if (targetResource.exists()) targetResource.inputStream.use { it.readBytes() } else null
    }.onFailure { exception ->
        log.error("Failed to access file resource", exception)
    }.getOrNull()

    private fun detectRegions(sourceImage: BufferedImage): List<List<Point>> {
        val environment = env ?: return createFallbackBounds(sourceImage)
        val session = detSession ?: return createFallbackBounds(sourceImage)

        val imageTensorBuffer = createTensorBuffer(
            sourceImage, DET_INPUT_SIZE, DET_INPUT_SIZE, IMAGENET_MEANS, IMAGENET_STDS
        )
        val tensorShapeDimensions = longArrayOf(1, 3, DET_INPUT_SIZE.toLong(), DET_INPUT_SIZE.toLong())

        val extractedBoundingBoxes = OnnxTensor.createTensor(environment, imageTensorBuffer, tensorShapeDimensions).use { inputTensor ->
            val modelInputName = session.inputNames.iterator().next()
            session.run(mapOf(modelInputName to inputTensor)).use { modelOutput ->
                val predictionTensor = modelOutput[0] as OnnxTensor
                val predictionFloatBuffer = predictionTensor.floatBuffer
                val textProbabilityMap = Array(DET_INPUT_SIZE) { FloatArray(DET_INPUT_SIZE) }
                for (y in 0 until DET_INPUT_SIZE) {
                    predictionFloatBuffer[textProbabilityMap[y]]
                }
                extractRegions(textProbabilityMap, sourceImage.width, sourceImage.height)
            }
        }
        return extractedBoundingBoxes.ifEmpty { createFallbackBounds(sourceImage) }
    }

    private fun createFallbackBounds(sourceImage: BufferedImage): List<List<Point>> {
        return listOf(
            listOf(
                Point(0, 0),
                Point(sourceImage.width, 0),
                Point(sourceImage.width, sourceImage.height),
                Point(0, sourceImage.height)
            )
        )
    }

    private fun extractRegions(
        textProbabilityMap: Array<FloatArray>, originalImageWidth: Int, originalImageHeight: Int
    ): List<List<Point>> {
        val visitedPixelMap = Array(DET_INPUT_SIZE) { BooleanArray(DET_INPUT_SIZE) }
        val finalCoordinateBoxes = mutableListOf<List<Point>>()

        val widthScaleRatio = originalImageWidth.toDouble() / DET_INPUT_SIZE.toDouble()
        val heightScaleRatio = originalImageHeight.toDouble() / DET_INPUT_SIZE.toDouble()
        val detectionConfidenceThreshold = ocrProperties.engine.onnx.detectionThreshold

        for (y in 0 until DET_INPUT_SIZE) {
            val probabilityRow = textProbabilityMap[y]
            val visitedRow = visitedPixelMap[y]
            for (x in 0 until DET_INPUT_SIZE) {
                if (probabilityRow[x] > detectionConfidenceThreshold && !visitedRow[x]) {
                    val connectedComponentBox = findComponent(textProbabilityMap, visitedPixelMap, x, y)

                    if (connectedComponentBox.area >= MIN_PIXEL_COUNT && connectedComponentBox.width >= MIN_DIMENSION_SIZE && connectedComponentBox.height >= MIN_DIMENSION_SIZE) {
                        val horizontalPadding = (connectedComponentBox.width * BOX_EXPAND_RATIO_X).toInt()
                            .coerceAtLeast(BOX_MIN_PADDING)
                        val verticalPadding = (connectedComponentBox.height * BOX_EXPAND_RATIO_Y).toInt()
                            .coerceAtLeast(BOX_MIN_PADDING)

                        val paddedMinX = (connectedComponentBox.minX - horizontalPadding).coerceAtLeast(0)
                        val paddedMaxX = (connectedComponentBox.maxX + horizontalPadding).coerceAtMost(DET_INPUT_SIZE - 1)
                        val paddedMinY = (connectedComponentBox.minY - verticalPadding).coerceAtLeast(0)
                        val paddedMaxY = (connectedComponentBox.maxY + verticalPadding).coerceAtMost(DET_INPUT_SIZE - 1)

                        val rescaledMinX = (paddedMinX * widthScaleRatio).toInt().coerceAtLeast(0)
                        val rescaledMaxX = (paddedMaxX * widthScaleRatio).toInt().coerceAtMost(originalImageWidth)
                        val rescaledMinY = (paddedMinY * heightScaleRatio).toInt().coerceAtLeast(0)
                        val rescaledMaxY = (paddedMaxY * heightScaleRatio).toInt().coerceAtMost(originalImageHeight)

                        finalCoordinateBoxes.add(
                            listOf(
                                Point(rescaledMinX, rescaledMinY),
                                Point(rescaledMaxX, rescaledMinY),
                                Point(rescaledMaxX, rescaledMaxY),
                                Point(rescaledMinX, rescaledMaxY)
                            )
                        )
                    }
                }
            }
        }
        return finalCoordinateBoxes.sortedWith(compareBy({ it[0].y }, { it[0].x }))
    }

    private fun splitSegments(regionImage: BufferedImage): List<SegmentedColumn> {
        val regionWidth = regionImage.width
        val regionHeight = regionImage.height
        if (regionWidth < MIN_SEGMENTABLE_WIDTH) return listOf(SegmentedColumn(regionImage, 0))

        val ignoreVerticalPadding = (regionHeight * PROJECTION_PADDING_RATIO).toInt()
        val effectiveScanHeight = regionHeight - (ignoreVerticalPadding * 2)
        if (effectiveScanHeight <= MIN_SCAN_HEIGHT) return listOf(SegmentedColumn(regionImage, 0))

        val verticalProjectionProfile = getVerticalProjection(regionImage, ignoreVerticalPadding, regionHeight - ignoreVerticalPadding)
        val whitespacePixelCountThreshold = (effectiveScanHeight * WHITESPACE_THRESHOLD_RATIO).toInt()
        val isWhitespaceColumn = BooleanArray(regionWidth) { x -> verticalProjectionProfile[x] >= whitespacePixelCountThreshold }

        val horizontalCutPoints = findSplitPoints(isWhitespaceColumn)
        if (horizontalCutPoints.isEmpty()) return listOf(SegmentedColumn(regionImage, 0))

        return cropSegments(regionImage, horizontalCutPoints).ifEmpty { listOf(SegmentedColumn(regionImage, 0)) }
    }

    private fun getVerticalProjection(regionImage: BufferedImage, startY: Int, endY: Int): IntArray {
        val width = regionImage.width
        val height = regionImage.height
        val rgbArray = IntArray(width * height)
        regionImage.getRGB(0, 0, width, height, rgbArray, 0, width)

        return IntArray(width) { x ->
            var whitespaceCount = 0
            for (y in startY until endY) {
                val rgbPixel = rgbArray[y * width + x]
                val red = (rgbPixel shr 16) and 0xFF
                val green = (rgbPixel shr 8) and 0xFF
                val blue = rgbPixel and 0xFF
                val pixelLuminance = 0.299 * red + 0.587 * green + 0.114 * blue
                if (pixelLuminance > LUMINANCE_THRESHOLD) whitespaceCount++
            }
            whitespaceCount
        }
    }

    private fun findSplitPoints(isWhitespaceColumn: BooleanArray): List<Int> {
        val calculatedCutPoints = mutableListOf<Int>()
        var currentWhitespaceStartIndex = -1
        for (index in isWhitespaceColumn.indices) {
            val isCurrentColumnWhitespace = isWhitespaceColumn[index]
            if (isCurrentColumnWhitespace && currentWhitespaceStartIndex == -1) {
                currentWhitespaceStartIndex = index
            } else if (!isCurrentColumnWhitespace && currentWhitespaceStartIndex != -1) {
                val whitespaceWidth = index - currentWhitespaceStartIndex
                if (whitespaceWidth >= MIN_CHAR_GAP_WIDTH) {
                    calculatedCutPoints.add(currentWhitespaceStartIndex + whitespaceWidth / 2)
                }
                currentWhitespaceStartIndex = -1
            }
        }
        return calculatedCutPoints
    }

    private fun cropSegments(
        regionImage: BufferedImage, horizontalCutPoints: List<Int>
    ): List<SegmentedColumn> {
        val croppedImageSegments = mutableListOf<SegmentedColumn>()
        var currentSegmentStartX = 0
        for (cutPointX in horizontalCutPoints) {
            if (cutPointX > currentSegmentStartX + MIN_SEGMENT_WIDTH) {
                val segmentWidth = cutPointX - currentSegmentStartX
                croppedImageSegments.add(SegmentedColumn(regionImage.getSubimage(currentSegmentStartX, 0, segmentWidth, regionImage.height), currentSegmentStartX))
            }
            currentSegmentStartX = cutPointX
        }
        if (regionImage.width >= currentSegmentStartX + MIN_SEGMENT_WIDTH) {
            croppedImageSegments.add(
                SegmentedColumn(
                    regionImage.getSubimage(
                        currentSegmentStartX, 0, regionImage.width - currentSegmentStartX, regionImage.height
                    ), currentSegmentStartX
                )
            )
        }
        return croppedImageSegments
    }

    private fun findComponent(
        textProbabilityMap: Array<FloatArray>, visitedPixelMap: Array<BooleanArray>, startX: Int, startY: Int
    ): BoundingBox {
        var minComponentX = startX
        var maxComponentX = startX
        var minComponentY = startY
        var maxComponentY = startY

        val confidenceThreshold = ocrProperties.engine.onnx.detectionThreshold
        val pixelTraversalQueue = ArrayDeque<Int>()

        pixelTraversalQueue.add(startY * DET_INPUT_SIZE + startX)
        visitedPixelMap[startY][startX] = true

        while (pixelTraversalQueue.isNotEmpty()) {
            val packedCoordinate = pixelTraversalQueue.removeFirst()
            val currentY = packedCoordinate / DET_INPUT_SIZE
            val currentX = packedCoordinate % DET_INPUT_SIZE

            if (currentX < minComponentX) minComponentX = currentX
            if (currentX > maxComponentX) maxComponentX = currentX
            if (currentY < minComponentY) minComponentY = currentY
            if (currentY > maxComponentY) maxComponentY = currentY

            for (i in 0 until 8) {
                val neighborX = currentX + NEIGHBOR_DX[i]
                val neighborY = currentY + NEIGHBOR_DY[i]

                if (neighborX in 0 until DET_INPUT_SIZE && neighborY in 0 until DET_INPUT_SIZE && textProbabilityMap[neighborY][neighborX] > confidenceThreshold && !visitedPixelMap[neighborY][neighborX]) {
                    visitedPixelMap[neighborY][neighborX] = true
                    pixelTraversalQueue.add(neighborY * DET_INPUT_SIZE + neighborX)
                }
            }
        }
        return BoundingBox(minComponentX, maxComponentX, minComponentY, maxComponentY)
    }

    private fun cropRegion(sourceImage: BufferedImage, regionCoordinateBounds: List<Point>): BufferedImage {
        if (regionCoordinateBounds.isEmpty()) sourceImage.getSubimage(0, 0, 1, 1)

        val minCoordinateX = regionCoordinateBounds.minOf { it.x }.coerceAtLeast(0)
        val maxCoordinateX = regionCoordinateBounds.maxOf { it.x }.coerceAtMost(sourceImage.width)
        val minCoordinateY = regionCoordinateBounds.minOf { it.y }.coerceAtLeast(0)
        val maxCoordinateY = regionCoordinateBounds.maxOf { it.y }.coerceAtMost(sourceImage.height)

        val cropStartX = (minCoordinateX - CROP_PADDING_X).coerceAtLeast(0)
        val cropEndX = (maxCoordinateX + CROP_PADDING_X).coerceAtMost(sourceImage.width)
        val cropStartY = (minCoordinateY - CROP_PADDING_Y).coerceAtLeast(0)
        val cropEndY = (maxCoordinateY + CROP_PADDING_Y).coerceAtMost(sourceImage.height)

        val cropRegionWidth = (cropEndX - cropStartX).coerceAtLeast(1)
        val cropRegionHeight = (cropEndY - cropStartY).coerceAtLeast(1)

        return sourceImage.getSubimage(cropStartX, cropStartY, cropRegionWidth, cropRegionHeight)
    }

    private fun recognize(croppedImageSegment: BufferedImage): TextBlock {
        val environment = env ?: return TextBlock("", 0.0f, emptyList())
        val session = recSession ?: return TextBlock("", 0.0f, emptyList())

        val preprocessedImage = preprocessRec(croppedImageSegment)
        val imageTensorBuffer =
            createTensorBuffer(preprocessedImage, REC_INPUT_WIDTH, REC_INPUT_HEIGHT, null, null)
        val tensorShapeDimensions = longArrayOf(1, 3, REC_INPUT_HEIGHT.toLong(), REC_INPUT_WIDTH.toLong())

        return OnnxTensor.createTensor(environment, imageTensorBuffer, tensorShapeDimensions).use { inputTensor ->
            val modelInputName = session.inputNames.iterator().next()
            session.run(mapOf(modelInputName to inputTensor)).use { modelOutput ->
                val predictionTensor = modelOutput[0] as OnnxTensor
                val predictionDimensions = predictionTensor.info.shape
                val timeSequenceLength = predictionDimensions[1].toInt()
                val vocabularyClassSize = predictionDimensions[2].toInt()
                val predictionFloatBuffer = predictionTensor.floatBuffer

                val timeStepProbabilities = Array(timeSequenceLength) { FloatArray(vocabularyClassSize) }
                for (timeStepIndex in 0 until timeSequenceLength) {
                    predictionFloatBuffer[timeStepProbabilities[timeStepIndex]]
                }
                decode(timeStepProbabilities)
            }
        }
    }

    private fun preprocessRec(sourceImageSegment: BufferedImage): BufferedImage {
        val formattedCanvas = BufferedImage(REC_INPUT_WIDTH, REC_INPUT_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphicsContext = formattedCanvas.createGraphics()
        try {
            graphicsContext.color = Color.WHITE
            graphicsContext.fillRect(0, 0, REC_INPUT_WIDTH, REC_INPUT_HEIGHT)

            graphicsContext.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphicsContext.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphicsContext.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val originalAspectRatio = sourceImageSegment.width.toDouble() / sourceImageSegment.height.toDouble()
            val adjustedImageWidth =
                (REC_INPUT_HEIGHT * originalAspectRatio).toInt().coerceAtMost(REC_INPUT_WIDTH).coerceAtLeast(1)

            graphicsContext.drawImage(sourceImageSegment, 0, 0, adjustedImageWidth, REC_INPUT_HEIGHT, null)
        } finally {
            graphicsContext.dispose()
        }
        return formattedCanvas
    }

    private fun createTensorBuffer(
        sourceImage: BufferedImage, targetWidth: Int, targetHeight: Int, meanValues: FloatArray?, stdDevValues: FloatArray?
    ): FloatBuffer {
        val scaledCanvas = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphicsContext = scaledCanvas.createGraphics()
        try {
            graphicsContext.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphicsContext.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphicsContext.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphicsContext.dispose()
        }

        val rawPixels = (scaledCanvas.raster.dataBuffer as DataBufferInt).data
        val tensorFloatBuffer = FloatBuffer.allocate(3 * targetHeight * targetWidth)
        val floatArray = tensorFloatBuffer.array()

        val totalPixelCount = targetWidth * targetHeight
        val blueOffset = 2 * totalPixelCount

        val (meanR, meanG, meanB) = meanValues ?: floatArrayOf(0.5f, 0.5f, 0.5f)
        val (stdR, stdG, stdB) = stdDevValues ?: floatArrayOf(0.5f, 0.5f, 0.5f)

        for (pixelIndex in 0 until totalPixelCount) {
            val rgbValue = rawPixels[pixelIndex]
            floatArray[pixelIndex] = (((rgbValue shr 16) and 0xFF) / 255.0f - meanR) / stdR
            floatArray[totalPixelCount + pixelIndex] = (((rgbValue shr 8) and 0xFF) / 255.0f - meanG) / stdG
            floatArray[blueOffset + pixelIndex] = ((rgbValue and 0xFF) / 255.0f - meanB) / stdB
        }
        return tensorFloatBuffer
    }

    private fun decode(timeStepProbabilities: Array<FloatArray>): TextBlock {
        val decodedCharacterIndices = mutableListOf<Int>()
        val characterConfidences = mutableListOf<Float>()

        var previousCharacterIndex = -1
        for (classProbabilities in timeStepProbabilities) {
            var highestProbabilityIndex = 0
            var highestProbability = classProbabilities[0]
            for (classIndex in 1 until classProbabilities.size) {
                if (classProbabilities[classIndex] > highestProbability) {
                    highestProbability = classProbabilities[classIndex]
                    highestProbabilityIndex = classIndex
                }
            }
            if (highestProbabilityIndex != 0 && highestProbabilityIndex != previousCharacterIndex) {
                decodedCharacterIndices.add(highestProbabilityIndex)
                characterConfidences.add(highestProbability)
            }
            previousCharacterIndex = highestProbabilityIndex
        }

        val finalizedRecognizedText = decodedCharacterIndices.joinToString("") { dictionaryIndex ->
            if (dictionaryIndex < vocab.size) vocab[dictionaryIndex] else ""
        }.trim()

        val overallAverageConfidence = if (characterConfidences.isNotEmpty()) characterConfidences.average().toFloat() else 0.0f

        return TextBlock(finalizedRecognizedText, overallAverageConfidence, emptyList())
    }

    @PreDestroy
    override fun close() {
        recSession?.let { session ->
            runCatching { session.close() }.onFailure { log.error("Failed to close ONNX recognition session", it) }
        }
        detSession?.let { session ->
            runCatching { session.close() }.onFailure { log.error("Failed to close ONNX detection session", it) }
        }
        env?.let { environment ->
            runCatching { environment.close() }.onFailure { log.error("Failed to release ONNX environment", it) }
        }
    }
}

private data class RecognitionInput(
    val image: BufferedImage,
    val bounds: List<Point>
)

private data class SegmentedColumn(
    val image: BufferedImage,
    val offsetX: Int
)