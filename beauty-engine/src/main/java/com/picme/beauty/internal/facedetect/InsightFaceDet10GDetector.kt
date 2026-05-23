package com.picme.beauty.internal.facedetect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
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
        private const val CONFIDENCE_THRESHOLD = 0.8f
        private const val NMS_THRESHOLD = 0.4f
        private const val DEFAULT_INPUT_MEAN = 127.5f
        private const val DEFAULT_INPUT_STD = 128.0f
        private const val ROI_EXPAND_RATIO = 1.2f

        // [性能优化] 预计算并缓存 anchors，避免每帧重复生成 ~16K 个对象
        private val CACHED_ANCHORS: List<FloatArray> by lazy { generateAnchorsStatic() }

        private fun generateAnchorsStatic(): List<FloatArray> {
            val anchors = ArrayList<FloatArray>(16800)
            val minSizesList = listOf(
                listOf(16f, 32f),
                listOf(64f, 128f),
                listOf(256f, 512f)
            )
            val steps = listOf(8f, 16f, 32f)

            for (level in steps.indices) {
                val step = steps[level]
                val minSizes = minSizesList[level]
                val featureMapSize = (INPUT_SIZE / step).toInt()

                for (y in 0 until featureMapSize) {
                    val cy = (y + 0.5f) * step
                    for (x in 0 until featureMapSize) {
                        val cx = (x + 0.5f) * step
                        for (minSize in minSizes) {
                            anchors.add(floatArrayOf(cx, cy, minSize, minSize, step))
                        }
                    }
                }
            }
            return anchors
        }
    }

    private val appContext = context.applicationContext
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private var inputMean: Float = DEFAULT_INPUT_MEAN
    private var inputStd: Float = DEFAULT_INPUT_STD
    private var debugImageSaved: Boolean = false

    // [性能优化] 复用像素缓冲区，避免每帧 new IntArray / FloatArray
    private val reusablePixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val reusableChwBuffer = FloatArray(INPUT_CHANNELS * INPUT_SIZE * INPUT_SIZE)

    init {
        initialize()
    }

    fun isReady(): Boolean = ortSession != null

    fun detectLargestFace(bitmap: Bitmap): RectF? {
        val session = ortSession ?: return null
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        val totalStart = SystemClock.elapsedRealtime()
        Log.d(TAG, "[Perf] detectLargestFace START: bitmap=${origW.toInt()}x${origH.toInt()}, modelInput=$INPUT_SIZE")
        return try {
            val faces = runInference(session, bitmap)
            val totalElapsed = SystemClock.elapsedRealtime() - totalStart
            Log.d(TAG, "[Perf] detectLargestFace DONE: total=${totalElapsed}ms, faces=${faces.size}")
            if (faces.isNotEmpty()) {
                Log.d(TAG, "[Diag] First face: [${faces[0].x1.toInt()},${faces[0].y1.toInt()},${faces[0].x2.toInt()},${faces[0].y2.toInt()}] conf=${faces[0].confidence}")
            }
            if (faces.isEmpty()) {
                Log.d(TAG, "[Diag] No face detected by Det10G")
                return null
            }
            val largestFace = faces.maxByOrNull { it.confidence * it.area() }
            if (largestFace == null) {
                Log.d(TAG, "[Diag] No valid face found after filtering")
                return null
            }
            // [修复] 考虑 letterbox padding，先减去偏移再缩放
            val scale = INPUT_SIZE.toFloat() / maxOf(origW, origH)
            val scaledW = (origW * scale).toInt()
            val scaledH = (origH * scale).toInt()
            val padLeft = (INPUT_SIZE - scaledW) / 2f
            val padTop = (INPUT_SIZE - scaledH) / 2f

            // 将 640x640 坐标映射回原图，确保坐标在有效范围内
            var mappedX1 = ((largestFace.x1 - padLeft) / scale)
            var mappedY1 = ((largestFace.y1 - padTop) / scale)
            var mappedX2 = ((largestFace.x2 - padLeft) / scale)
            var mappedY2 = ((largestFace.y2 - padTop) / scale)

            // [新增] 放大 ROI 区域,以包含更多面部上下文
            val centerX = (mappedX1 + mappedX2) / 2f
            val centerY = (mappedY1 + mappedY2) / 2f
            val width = mappedX2 - mappedX1
            val height = mappedY2 - mappedY1
            val newWidth = width * ROI_EXPAND_RATIO
            val newHeight = height * ROI_EXPAND_RATIO

            mappedX1 = (centerX - newWidth / 2f).coerceIn(0f, origW)
            mappedY1 = (centerY - newHeight / 2f).coerceIn(0f, origH)
            mappedX2 = (centerX + newWidth / 2f).coerceIn(0f, origW)
            mappedY2 = (centerY + newHeight / 2f).coerceIn(0f, origH)

            val mappedFace = FaceBox(
                x1 = mappedX1,
                y1 = mappedY1,
                x2 = mappedX2,
                y2 = mappedY2,
                confidence = largestFace.confidence
            )
            Log.d(
                TAG,
                "[Diag] Det10G face SELECTED: conf=${mappedFace.confidence}, " +
                    "640bbox=[${largestFace.x1.toInt()},${largestFace.y1.toInt()},${largestFace.x2.toInt()},${largestFace.y2.toInt()}], " +
                    "origBBox=[${mappedFace.x1.toInt()},${mappedFace.y1.toInt()},${mappedFace.x2.toInt()},${mappedFace.y2.toInt()}], " +
                    "scale=$scale, pad=($padLeft,$padTop)"
            )
            mappedFace.toRectF()
        } catch (error: Exception) {
            Log.e(TAG, "[Diag] Det10G detection failed", error)
            null
        }
    }

    fun detectAllFaces(bitmap: Bitmap): List<FaceBox> {
        val session = ortSession ?: return emptyList()
        return try {
            val faces = runInference(session, bitmap)
            Log.d(TAG, "Det10G detected ${faces.size} faces")
            faces
        } catch (error: Exception) {
            Log.e(TAG, "Det10G detection failed", error)
            emptyList()
        }
    }

    fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        inputName = null
        reusablePaddedBitmap?.recycle()
        reusablePaddedBitmap = null
    }

    private fun initialize() {
        runCatching {
            val modelFile = ensureModelFile()

            // [优化] 配置 ONNX Runtime SessionOptions,强制使用 GPU 推理
            val sessionOptions = OrtSession.SessionOptions()

            try {
                // NNAPI 配置: 使用 FP16 加速,允许 GPU/NPU 执行支持的操作
                // 注意: 不设置 CPU_DISABLED,让 NNAPI 自动决定哪些层用 GPU,哪些回退 CPU
                val nnapiFlags = java.util.EnumSet.of(
                    ai.onnxruntime.providers.NNAPIFlags.USE_FP16
                )
                sessionOptions.addNnapi(nnapiFlags)
                Log.i(TAG, "NNAPI execution provider enabled with FP16 (flags=$nnapiFlags)")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI not available, falling back to CPU", e)
            }

            ortSession = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
            inputName = ortSession?.inputNames?.firstOrNull()
            resolveInputNormalization(modelFile)
            Log.i(
                TAG,
                "InsightFace Det10G initialized: ${modelFile.absolutePath}, mean=$inputMean, std=$inputStd"
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize InsightFace Det10G", error)
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
        val tensorStart = SystemClock.elapsedRealtime()
        val inputTensor = createInputTensor(bitmap)
        val tensorElapsed = SystemClock.elapsedRealtime() - tensorStart

        val inferenceStart = SystemClock.elapsedRealtime()
        try {
            val modelInputName = inputName ?: error("Det10G input name missing")
            val results = session.run(mapOf(modelInputName to inputTensor))
            val inferenceElapsed = SystemClock.elapsedRealtime() - inferenceStart

            val parseStart = SystemClock.elapsedRealtime()
            try {
                val faces = parseOutputs(results)
                val parseElapsed = SystemClock.elapsedRealtime() - parseStart

                val nmsStart = SystemClock.elapsedRealtime()
                val nmsResult = applyNMS(faces)
                val nmsElapsed = SystemClock.elapsedRealtime() - nmsStart

                Log.d(TAG, "[Perf] Det10G breakdown: tensor=${tensorElapsed}ms, inference=${inferenceElapsed}ms, parse=${parseElapsed}ms, NMS=${nmsElapsed}ms")
                return nmsResult
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    // [性能优化] 复用 Bitmap 池，避免每帧 createBitmap
    private var reusablePaddedBitmap: Bitmap? = null

    private fun getReusablePaddedBitmap(): Bitmap {
        var bmp = reusablePaddedBitmap
        if (bmp == null || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusablePaddedBitmap = bmp
        }
        return bmp
    }

    private fun createInputTensor(bitmap: Bitmap): OnnxTensor {
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = INPUT_SIZE.toFloat() / maxOf(origW, origH)
        val scaledW = (origW * scale).toInt()
        val scaledH = (origH * scale).toInt()

        val paddedBitmap = getReusablePaddedBitmap()
        val canvas = android.graphics.Canvas(paddedBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        val left = (INPUT_SIZE - scaledW) / 2f
        val top = (INPUT_SIZE - scaledH) / 2f

        val scaledBmp = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        canvas.drawBitmap(scaledBmp, left, top, null)
        scaledBmp.recycle()

        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val pixels = reusablePixelBuffer
        paddedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val chw = reusableChwBuffer
        for (index in 0 until pixelCount) {
            val pixel = pixels[index]
            val red = (pixel shr 16 and 0xFF).toFloat()
            val green = (pixel shr 8 and 0xFF).toFloat()
            val blue = (pixel and 0xFF).toFloat()

            chw[index] = (red - inputMean) / inputStd
            chw[pixelCount + index] = (green - inputMean) / inputStd
            chw[pixelCount * 2 + index] = (blue - inputMean) / inputStd
        }

        if (!debugImageSaved) {
            saveDebugBitmap(paddedBitmap.copy(Bitmap.Config.ARGB_8888, true), "det10g_letterbox_${System.currentTimeMillis()}.jpg")
            debugImageSaved = true

            val sampleSize = minOf(10, pixelCount)
            val sampleValues = StringBuilder()
            for (i in 0 until sampleSize) {
                sampleValues.append("[${String.format("%.2f", chw[i])},${String.format("%.2f", chw[pixelCount + i])},${String.format("%.2f", chw[pixelCount * 2 + i])}] ")
            }
            Log.d(TAG, "[Diag] First $sampleSize pixels normalized (R,G,B): $sampleValues")
            Log.d(TAG, "[Diag] Using mean=$inputMean, std=$inputStd")
        }

        return OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(chw),
            longArrayOf(1, INPUT_CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
    }

    // [性能优化] 预分配固定大小数组，避免 NMS 阶段 mutableList 频繁扩容
    private val reusableAllScores = FloatArray(20000)
    private val reusableAllBoxes = Array(20000) { FloatArray(4) }

    private fun parseOutputs(results: OrtSession.Result): List<FaceBox> {
        val faces = mutableListOf<FaceBox>()

        val outputCount = results.size()
        Log.d(TAG, "[Diag] ONNX outputs count: $outputCount")
        for (i in 0 until outputCount) {
            val tensor = results.get(i) as? OnnxTensor ?: continue
            Log.d(TAG, "[Diag] Output $i shape: ${tensor.info.shape.joinToString(",")}")
        }

        if (outputCount < 6) {
            Log.w(TAG, "[Diag] Unexpected output count: $outputCount, expected >= 6 for RetinaFace")
            return emptyList()
        }

        var scoreCount = 0
        var boxCount = 0

        // [性能优化] 直接解析 ONNX 输出为 FloatArray，避免 flattenFloatArray 递归+动态列表开销
        // [关键修复] ONNX 输出可能是嵌套数组（如 Array<FloatArray>），需先展平
        for (i in 0..2) {
            val scoresTensor = results.get(i) as? OnnxTensor ?: continue
            val rawScores = flattenOnnxOutput(scoresTensor.value)
            if (rawScores == null) {
                Log.w(TAG, "[Diag] Scale $i scores: failed to flatten ONNX output, type=${scoresTensor.value?.javaClass}")
                continue
            }
            for (score in rawScores) {
                if (scoreCount < reusableAllScores.size) {
                    reusableAllScores[scoreCount++] = score
                }
            }
            if (rawScores.isNotEmpty()) {
                var maxScore = Float.MIN_VALUE
                var minScore = Float.MAX_VALUE
                var sumScore = 0.0
                for (score in rawScores) {
                    if (score > maxScore) maxScore = score
                    if (score < minScore) minScore = score
                    sumScore += score
                }
                Log.d(TAG, "[Diag] Scale $i scores: range=[$minScore, $maxScore], avg=${sumScore / rawScores.size}, count=${rawScores.size}")
            }
        }

        for (i in 3..5) {
            val boxesTensor = results.get(i) as? OnnxTensor ?: continue
            val boxes = flattenOnnxOutput(boxesTensor.value)
            if (boxes == null) {
                Log.w(TAG, "[Diag] Scale $i boxes: failed to flatten ONNX output, type=${boxesTensor.value?.javaClass}")
                continue
            }
            for (j in boxes.indices step 4) {
                if (boxCount < reusableAllBoxes.size) {
                    reusableAllBoxes[boxCount][0] = boxes[j]
                    reusableAllBoxes[boxCount][1] = boxes[j + 1]
                    reusableAllBoxes[boxCount][2] = boxes[j + 2]
                    reusableAllBoxes[boxCount][3] = boxes[j + 3]
                    boxCount++
                }
            }
        }

        if (scoreCount == 0 || boxCount == 0) {
            Log.w(TAG, "[Diag] Failed to extract scores or boxes")
            return emptyList()
        }

        val numAnchors = scoreCount

        Log.d(TAG, "[Diag] Merged scores length=$scoreCount, boxes count=$boxCount")

        // 生成 anchors 并解码 bboxes
        val anchors = generateAnchors()
        if (anchors.size != numAnchors) {
            Log.w(TAG, "[Diag] Anchor count mismatch: anchors=${anchors.size}, scores=$numAnchors")
            return emptyList()
        }

        var aboveThreshold = 0
        var invalidBox = 0
        for (i in 0 until numAnchors) {
            val score = reusableAllScores[i]
            if (score < CONFIDENCE_THRESHOLD) continue
            aboveThreshold++

            val boxOffset = reusableAllBoxes[i]
            val anchor = anchors[i]

            val stride = if (anchor.size >= 5) anchor[4] else anchor[2] / 2f

            val x1 = anchor[0] - boxOffset[0] * stride
            val y1 = anchor[1] - boxOffset[1] * stride
            val x2 = anchor[0] + boxOffset[2] * stride
            val y2 = anchor[1] + boxOffset[3] * stride

            val x1Clipped = x1.coerceIn(0f, INPUT_SIZE.toFloat())
            val y1Clipped = y1.coerceIn(0f, INPUT_SIZE.toFloat())
            val x2Clipped = x2.coerceIn(0f, INPUT_SIZE.toFloat())
            val y2Clipped = y2.coerceIn(0f, INPUT_SIZE.toFloat())

            if (x1Clipped >= x2Clipped || y1Clipped >= y2Clipped) {
                invalidBox++
                if (aboveThreshold <= 5) {
                    Log.d(TAG, "[Diag] Invalid box #$i: offset=[${boxOffset.joinToString(",")}] anchor=[${anchor.joinToString(",")}] decoded=[$x1,$y1,$x2,$y2] clipped=[$x1Clipped,$y1Clipped,$x2Clipped,$y2Clipped] score=$score")
                }
                continue
            }

            val boxWidth = x2Clipped - x1Clipped
            val boxHeight = y2Clipped - y1Clipped
            if (boxWidth < 10f || boxHeight < 10f) {
                if (aboveThreshold <= 5) {
                    Log.d(TAG, "[Diag] Too small box #$i: size=${boxWidth.toInt()}x${boxHeight.toInt()} at [$x1Clipped,$y1Clipped,$x2Clipped,$y2Clipped] score=$score")
                }
                continue
            }

            if (aboveThreshold <= 5) {
                Log.d(TAG, "[Diag] Valid box #$i: offset=[${boxOffset.joinToString(",")}] anchor=[${anchor.joinToString(",")}] decoded=[$x1,$y1,$x2,$y2] clipped=[$x1Clipped,$y1Clipped,$x2Clipped,$y2Clipped] score=$score")
            }
            faces.add(FaceBox(x1Clipped, y1Clipped, x2Clipped, y2Clipped, score))
        }

        Log.d(TAG, "[Diag] After threshold: aboveThreshold=$aboveThreshold, invalidBox=$invalidBox, validFaces=${faces.size}")
        return faces
    }

    /**
     * [性能优化] 使用预缓存的 anchors，避免每帧重复生成
     */
    private fun generateAnchors(): List<FloatArray> = CACHED_ANCHORS

    private fun applyNMS(faces: List<FaceBox>): List<FaceBox> {
        if (faces.isEmpty()) {
            Log.d(TAG, "[Diag] NMS skipped: 0 face(s)")
            return faces
        }

        // [优化] Top-K 策略：只取前 100 个最高分的框，避免 NMS 处理过多框
        val topK = 100
        val topFaces = faces.sortedByDescending { it.confidence }.take(topK)

        if (faces.size > topK) {
            Log.d(TAG, "[Diag] Top-K: reduced from ${faces.size} to $topK")
        }

        val sorted = topFaces
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

        Log.d(TAG, "[Diag] NMS: before=${faces.size}, after=${result.size}, topConf=${result.firstOrNull()?.confidence}")
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

    /**
     * [性能优化] 展平 ONNX 输出为 FloatArray。
     *
     * ONNX Runtime Java API 中，OnnxTensor.value 的类型取决于输出维度：
     * - 1D [N] → FloatArray（直接返回）
     * - 2D [M, N] → Array<FloatArray>（展平）
     * - 3D [B, M, N] → Array<Array<FloatArray>>（展平）
     *
     * 本方法使用预分配缓冲区避免动态列表扩容，比原 flattenFloatArray 更高效。
     */
    private fun flattenOnnxOutput(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                // 预计算总元素数（避免动态列表扩容）
                var totalSize = 0
                fun countElements(node: Any?) {
                    when (node) {
                        is FloatArray -> totalSize += node.size
                        is Array<*> -> node.forEach { countElements(it) }
                    }
                }
                countElements(value)
                if (totalSize == 0) return null

                val result = FloatArray(totalSize)
                var index = 0
                fun copyElements(node: Any?) {
                    when (node) {
                        is FloatArray -> {
                            System.arraycopy(node, 0, result, index, node.size)
                            index += node.size
                        }
                        is Array<*> -> node.forEach { copyElements(it) }
                    }
                }
                copyElements(value)
                result
            }
            else -> null
        }
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
            Log.i(TAG, "Model has built-in normalization nodes, using mean=0, std=1")
        } else {
            inputMean = DEFAULT_INPUT_MEAN
            inputStd = DEFAULT_INPUT_STD
            Log.i(TAG, "Using default normalization: mean=$DEFAULT_INPUT_MEAN, std=$DEFAULT_INPUT_STD")
        }
        Log.d(TAG, "[Diag] Model file size=${modelFile.length()}, hasSub=$hasSubNode, hasMul=$hasMulNode")
    }

    /**
     * 保存调试图像到外部存储
     */
    private fun saveDebugBitmap(bitmap: Bitmap, fileName: String) {
        try {
            val debugDir = File(appContext.getExternalFilesDir(null), "debug_det10g")
            if (!debugDir.exists()) {
                debugDir.mkdirs()
            }
            val file = File(debugDir, fileName)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "[Debug] Saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "[Debug] Failed to save bitmap", e)
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
