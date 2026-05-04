package com.picme.features.camera.facedetect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.picme.core.common.Logger
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * InsightFace Det10G (RetinaFace) 人脸检测器。
 *
 * 职责：提供人脸边界框判断是否存在人脸，为 2d106det 提供 ROI。
 */
class InsightFaceDet10GDetector(context: Context) {

    companion object {
        private const val TAG = "PicMe:InsightFaceDet10G"
        private const val MODEL_ASSET_PATH = "insightface/det_10g.onnx"
        private const val MODEL_FILE_NAME = "insightface_det_10g.onnx"
        private const val INPUT_SIZE = 640
        private const val INPUT_CHANNELS = 3
        private const val CONFIDENCE_THRESHOLD = 0.3f  // 降低阈值提高召回率
        private const val NMS_THRESHOLD = 0.4f
        private const val DEFAULT_INPUT_MEAN = 127.5f
        private const val DEFAULT_INPUT_STD = 128.0f
    }

    private val appContext = context.applicationContext
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private var inputMean: Float = DEFAULT_INPUT_MEAN
    private var inputStd: Float = DEFAULT_INPUT_STD

    init {
        initialize()
    }

    fun isReady(): Boolean = ortSession != null

    fun detectLargestFace(bitmap: Bitmap): RectF? {
        val session = ortSession ?: return null
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        Logger.d(TAG, "[Diag] detectLargestFace START: bitmap=${origW.toInt()}x${origH.toInt()}, modelInput=$INPUT_SIZE")
        return try {
            val faces = runInference(session, bitmap)
            Logger.d(TAG, "[Diag] detectLargestFace after inference: faces=${faces.size}")
            if (faces.isEmpty()) {
                Logger.d(TAG, "[Diag] No face detected by Det10G")
                return null
            }
            val largestFace = faces.maxByOrNull { it.confidence * it.area() }
            if (largestFace == null) {
                Logger.d(TAG, "[Diag] No valid face found after filtering")
                return null
            }
            // Det10G 在 640x640 输入上推理，bbox 坐标需映射回原始 bitmap 尺寸
            val scaleX = origW / INPUT_SIZE
            val scaleY = origH / INPUT_SIZE
            val mappedFace = FaceBox(
                x1 = largestFace.x1 * scaleX,
                y1 = largestFace.y1 * scaleY,
                x2 = largestFace.x2 * scaleX,
                y2 = largestFace.y2 * scaleY,
                confidence = largestFace.confidence
            )
            Logger.d(
                TAG,
                "[Diag] Det10G face SELECTED: conf=${mappedFace.confidence}, " +
                    "640bbox=[${largestFace.x1.toInt()},${largestFace.y1.toInt()},${largestFace.x2.toInt()},${largestFace.y2.toInt()}], " +
                    "origBBox=[${mappedFace.x1.toInt()},${mappedFace.y1.toInt()},${mappedFace.x2.toInt()},${mappedFace.y2.toInt()}], " +
                    "scale=($scaleX,$scaleY)"
            )
            mappedFace.toRectF()
        } catch (error: Exception) {
            Logger.e(TAG, "[Diag] Det10G detection failed", error)
            null
        }
    }

    fun detectAllFaces(bitmap: Bitmap): List<FaceBox> {
        val session = ortSession ?: return emptyList()
        return try {
            val faces = runInference(session, bitmap)
            Logger.d(TAG, "Det10G detected ${faces.size} faces")
            faces
        } catch (error: Exception) {
            Logger.e(TAG, "Det10G detection failed", error)
            emptyList()
        }
    }

    fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        inputName = null
    }

    private fun initialize() {
        runCatching {
            val modelFile = ensureModelFile()
            val sessionOptions = OrtSession.SessionOptions()
            ortSession = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
            inputName = ortSession?.inputNames?.firstOrNull()
            resolveInputNormalization(modelFile)
            Logger.i(
                TAG,
                "InsightFace Det10G initialized: ${modelFile.absolutePath}, mean=$inputMean, std=$inputStd"
            )
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize InsightFace Det10G", error)
            ortSession = null
            inputName = null
            inputMean = DEFAULT_INPUT_MEAN
            inputStd = DEFAULT_INPUT_STD
        }
    }

    private fun ensureModelFile(): File {
        val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
        if (modelFile.exists() && modelFile.length() > 0L) {
            return modelFile
        }
        modelFile.outputStream().use { output ->
            appContext.assets.open(MODEL_ASSET_PATH).use { input ->
                input.copyTo(output)
            }
        }
        return modelFile
    }

    private fun runInference(session: OrtSession, bitmap: Bitmap): List<FaceBox> {
        val inputTensor = createInputTensor(bitmap)
        try {
            val modelInputName = inputName ?: error("Det10G input name missing")
            val results = session.run(mapOf(modelInputName to inputTensor))
            try {
                val faces = parseOutputs(results)
                return applyNMS(faces)
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun createInputTensor(bitmap: Bitmap): OnnxTensor {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(pixelCount)
        scaledBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        scaledBitmap.recycle()

        val chw = FloatArray(INPUT_CHANNELS * pixelCount)
        for (index in 0 until pixelCount) {
            val pixel = pixels[index]
            val red = (pixel shr 16 and 0xFF).toFloat()
            val green = (pixel shr 8 and 0xFF).toFloat()
            val blue = (pixel and 0xFF).toFloat()
            chw[index] = (red - inputMean) / inputStd
            chw[pixelCount + index] = (green - inputMean) / inputStd
            chw[pixelCount * 2 + index] = (blue - inputMean) / inputStd
        }

        return OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(chw),
            longArrayOf(1, INPUT_CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
    }

    private fun parseOutputs(results: OrtSession.Result): List<FaceBox> {
        val faces = mutableListOf<FaceBox>()

        val outputCount = results.size()
        Logger.d(TAG, "[Diag] ONNX outputs count: $outputCount")
        for (i in 0 until outputCount) {
            val tensor = results.get(i) as? OnnxTensor ?: continue
            Logger.d(TAG, "[Diag] Output $i shape: ${tensor.info.shape.joinToString(",")}")
        }

        // RetinaFace Det10G 有 9 个输出：
        // - 3 个尺度的 scores: outputs[0], [1], [2]
        // - 3 个尺度的 boxes: outputs[3], [4], [5]
        // - 3 个尺度的 landmarks: outputs[6], [7], [8]
        if (outputCount < 6) {
            Logger.w(TAG, "[Diag] Unexpected output count: $outputCount, expected >= 6 for RetinaFace")
            return emptyList()
        }

        // 收集所有尺度的 scores 和 boxes
        val allScores = mutableListOf<Float>()
        val allBoxes = mutableListOf<FloatArray>()

        // 合并 3 个尺度的 scores (每个是 [N, 1] -> face confidence)
        for (i in 0..2) {
            val scoresTensor = results.get(i) as? OnnxTensor ?: continue
            val scores = flattenFloatArray(scoresTensor.value) ?: continue
            allScores.addAll(scores.toList())
        }

        // 合并 3 个尺度的 boxes (每个是 [N, 4])
        for (i in 3..5) {
            val boxesTensor = results.get(i) as? OnnxTensor ?: continue
            val boxes = flattenFloatArray(boxesTensor.value) ?: continue
            // 每 4 个值组成一个 box
            for (j in boxes.indices step 4) {
                allBoxes.add(floatArrayOf(boxes[j], boxes[j+1], boxes[j+2], boxes[j+3]))
            }
        }

        if (allScores.isEmpty() || allBoxes.isEmpty()) {
            Logger.w(TAG, "[Diag] Failed to extract scores or boxes")
            return emptyList()
        }

        val scores = allScores.toFloatArray()
        val numAnchors = scores.size

        Logger.d(TAG, "[Diag] Merged scores length=${scores.size}, boxes count=${allBoxes.size}")

        // 生成 anchors 并解码 bboxes
        val anchors = generateAnchors()
        if (anchors.size != numAnchors) {
            Logger.w(TAG, "[Diag] Anchor count mismatch: anchors=${anchors.size}, scores=$numAnchors")
            return emptyList()
        }

        var aboveThreshold = 0
        var invalidBox = 0
        for (i in 0 until numAnchors) {
            val score = scores[i]
            if (score < CONFIDENCE_THRESHOLD) continue
            aboveThreshold++

            // 解码 bbox: RetinaFace 使用中心点 + 宽高格式
            // boxes[i] = [dx, dy, dw, dh] 相对于 anchor 的偏移
            val boxOffset = allBoxes[i]
            val anchor = anchors[i]
            
            // 解码公式（RetinaFace 标准）
            val cx = anchor[0] + boxOffset[0] * anchor[2]
            val cy = anchor[1] + boxOffset[1] * anchor[3]
            val w = anchor[2] * Math.exp(boxOffset[2].toDouble()).toFloat()
            val h = anchor[3] * Math.exp(boxOffset[3].toDouble()).toFloat()
            
            // 转换为中心点 + 宽高 -> 左上角 + 右下角
            val x1 = (cx - w / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
            val y1 = (cy - h / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
            val x2 = (cx + w / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
            val y2 = (cy + h / 2f).coerceIn(0f, INPUT_SIZE.toFloat())

            if (x1 >= x2 || y1 >= y2) {
                invalidBox++
                if (aboveThreshold <= 5) {
                    Logger.d(TAG, "[Diag] Invalid box #$i: offset=[${boxOffset.joinToString(",")}] anchor=[${anchor.joinToString(",")}] decoded=[$x1,$y1,$x2,$y2] score=$score")
                }
                continue
            }

            if (aboveThreshold <= 5) {
                Logger.d(TAG, "[Diag] Valid box #$i: offset=[${boxOffset.joinToString(",")}] anchor=[${anchor.joinToString(",")}] decoded=[$x1,$y1,$x2,$y2] score=$score")
            }
            faces.add(FaceBox(x1, y1, x2, y2, score))
        }

        Logger.d(TAG, "[Diag] After threshold: aboveThreshold=$aboveThreshold, invalidBox=$invalidBox, validFaces=${faces.size}")
        return faces
    }

    /**
     * 生成 RetinaFace Det10G 的 anchors
     * 
     * 根据 InsightFace Det10G (RetinaFace) 的标准配置：
     * - 3 个 FPN 层级，对应 stride [8, 16, 32]
     * - 每个位置 2 个 anchor（不同尺寸）
     * - Anchor 尺寸基于 stride 计算
     */
    private fun generateAnchors(): List<FloatArray> {
        val anchors = mutableListOf<FloatArray>()
        
        // RetinaFace 标准配置（来自官方实现）
        val minSizesList = listOf(
            listOf(16f, 32f),      // stride=8 的 anchor 尺寸
            listOf(64f, 128f),     // stride=16 的 anchor 尺寸  
            listOf(256f, 512f)     // stride=32 的 anchor 尺寸
        )
        val steps = listOf(8f, 16f, 32f)
        
        for (level in steps.indices) {
            val step = steps[level]
            val minSizes = minSizesList[level]
            val featureMapHeight = ceil(INPUT_SIZE / step).toInt()
            val featureMapWidth = ceil(INPUT_SIZE / step).toInt()
            
            for (y in 0 until featureMapHeight) {
                for (x in 0 until featureMapWidth) {
                    for (minSize in minSizes) {
                        val cx = (x + 0.5f) * step
                        val cy = (y + 0.5f) * step
                        anchors.add(floatArrayOf(cx, cy, minSize, minSize))
                    }
                }
            }
        }
        
        Logger.d(TAG, "[Diag] Generated ${anchors.size} anchors")
        return anchors
    }

    private fun applyNMS(faces: List<FaceBox>): List<FaceBox> {
        if (faces.size <= 1) {
            Logger.d(TAG, "[Diag] NMS skipped: ${faces.size} face(s)")
            return faces
        }

        val sorted = faces.sortedByDescending { it.confidence }
        val suppressed = BooleanArray(sorted.size)
        val result = mutableListOf<FaceBox>()

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            result.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val iou = calculateIoU(sorted[i], sorted[j])
                if (iou > NMS_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }

        Logger.d(TAG, "[Diag] NMS: before=${faces.size}, after=${result.size}, topConf=${result.firstOrNull()?.confidence}")
        return result
    }

    private fun calculateIoU(a: FaceBox, b: FaceBox): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)

        val intersectionWidth = max(0f, x2 - x1)
        val intersectionHeight = max(0f, y2 - y1)
        val intersectionArea = intersectionWidth * intersectionHeight

        val unionArea = a.area() + b.area() - intersectionArea
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun flattenFloatArray(value: Any?): FloatArray? {
        val flattened = mutableListOf<Float>()

        fun visit(node: Any?) {
            when (node) {
                is Float -> flattened.add(node)
                is Double -> flattened.add(node.toFloat())
                is FloatArray -> node.forEach { item -> flattened.add(item) }
                is DoubleArray -> node.forEach { item -> flattened.add(item.toFloat()) }
                is Array<*> -> node.forEach { item -> visit(item) }
            }
        }

        visit(value)
        return flattened.takeIf { items -> items.isNotEmpty() }?.toFloatArray()
    }

    private fun resolveInputNormalization(modelFile: File) {
        val modelText = runCatching {
            String(modelFile.readBytes(), Charsets.ISO_8859_1)
        }.getOrNull()
        val hasSubNode = modelText?.let { text ->
            text.contains("_minusscalar0") || text.contains("_minus") || text.contains("bn_data")
        } == true
        val hasMulNode = modelText?.let { text ->
            text.contains("_mulscalar0") || text.contains("_mul") || text.contains("bn_data")
        } == true
        if (hasSubNode && hasMulNode) {
            inputMean = 0f
            inputStd = 1f
        } else {
            inputMean = DEFAULT_INPUT_MEAN
            inputStd = DEFAULT_INPUT_STD
        }
    }

    data class FaceBox(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val confidence: Float
    ) {
        fun width(): Float = x2 - x1
        fun height(): Float = y2 - y1
        fun area(): Float = width() * height()
        fun toRectF(): RectF = RectF(x1, y1, x2, y2)
    }
}
