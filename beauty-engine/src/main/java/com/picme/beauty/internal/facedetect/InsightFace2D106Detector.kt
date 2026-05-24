package com.picme.beauty.internal.facedetect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.picme.beauty.internal.model.ModelManager
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * InsightFace 2D106 备选检测器。
 *
 * 内部采用两阶段检测架构：
 * - 第一阶段：Det10G (RetinaFace) 快速检测人脸存在性并提供 ROI
 * - 第二阶段：2d106det.onnx 在 ROI 内输出 106 点关键点
 *
 * 外部调用者无需关心内部实现细节，只需调用 detect() 方法即可。
 */
class InsightFace2D106Detector(context: Context) {

    companion object {
        private const val TAG = "PicMe:InsightFace106"
        private const val MODEL_KEY = "2d106_onnx"
        private const val INPUT_SIZE = 192
        private const val INPUT_CHANNELS = 3
        private const val POINT_COUNT = 106
        private const val LOOSE_CROP_SCALE = 1f
        private const val DEFAULT_INPUT_MEAN = 127.5f
        private const val DEFAULT_INPUT_STD = 128.0f
        private const val ENGINE_NAME = "ONNX-Nnapi"
    }

    private val appContext = context.applicationContext
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()

    // 内部 Det10G 检测器（不对外暴露）
    private var det10gDetector: InsightFaceDet10GDetector? = null

    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private var inputMean: Float = DEFAULT_INPUT_MEAN
    private var inputStd: Float = DEFAULT_INPUT_STD
    private var isGpuEnabled: Boolean = false
    private var isInitialized = false

    // [性能优化] 复用像素和 CHW 缓冲区，避免每帧 new IntArray/FloatArray
    private val reusablePixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val reusableChwBuffer = FloatArray(INPUT_CHANNELS * INPUT_SIZE * INPUT_SIZE)
    // [性能优化] 复用结果数组和临时点数组
    private val reusableResult = FloatArray(POINT_COUNT * 2)
    private val reusableMappedPoint = floatArrayOf(0f, 0f)

    init {
        // [ANR 修复] 改为懒加载，不立即初始化，避免主线程阻塞
        Log.d(TAG, "InsightFace2D106Detector created (lazy initialization)")
    }

    /**
     * 懒加载初始化 - 仅在首次 detect 时调用
     */
    private fun ensureInitialized() {
        if (isInitialized) return

        synchronized(this) {
            if (isInitialized) return

            initialize()
            isInitialized = true
        }
    }

    fun isReady(): Boolean {
        ensureInitialized()
        return ortSession != null && det10gDetector?.isReady() == true
    }

    /**
     * 检测人脸关键点
     *
     * @param bitmap 输入图像
     * @param lensFacing 镜头方向（用于坐标调整）
     * @param faceBounds 人脸边界框（可选），如果为 null 则内部自动使用 Det10G 检测
     * @return 106 点归一化坐标数组，未检测到返回 null
     */
    fun detect(bitmap: Bitmap, lensFacing: Int, faceBounds: android.graphics.RectF? = null): FloatArray? {
        // [ANR 修复] 懒加载初始化
        ensureInitialized()

        val session = ortSession ?: return null
        val totalStart = SystemClock.elapsedRealtime()

        // [两阶段检测] 第一阶段：如果没有提供 faceBounds，使用 Det10G 检测
        val actualFaceBounds = faceBounds ?: run {
            val det10g = det10gDetector
            if (det10g == null) {
                Log.w(TAG, "[Diag] Det10G detector not ready")
                return null
            }
            val bounds = det10g.detectLargestFace(bitmap)
            if (bounds == null) {
                Log.w(TAG, "[Diag] Det10G no face detected")
                return null
            }
            bounds
        }

        Log.d(TAG, "[Perf] 2d106det START: engine=$ENGINE_NAME, gpu=$isGpuEnabled, bitmap=${bitmap.width}x${bitmap.height}, faceBounds=$actualFaceBounds")
        return try {
            val rectBounds = android.graphics.Rect(
                actualFaceBounds.left.toInt().coerceIn(0, bitmap.width),
                actualFaceBounds.top.toInt().coerceIn(0, bitmap.height),
                actualFaceBounds.right.toInt().coerceIn(0, bitmap.width),
                actualFaceBounds.bottom.toInt().coerceIn(0, bitmap.height)
            )

            val cropStart = SystemClock.elapsedRealtime()
            val crop = buildLooseFaceCrop(bitmap, rectBounds) ?: run {
                Log.w(TAG, "[Diag] 2d106det buildLooseFaceCrop returned null")
                return null
            }
            val cropElapsed = SystemClock.elapsedRealtime() - cropStart

            val inferenceStart = SystemClock.elapsedRealtime()
            val rawOutput = runInference(session, crop.bitmap)
            crop.bitmap.recycle()
            val inferenceElapsed = SystemClock.elapsedRealtime() - inferenceStart

            // [诊断日志] 输出原始模型输出前 10 个点
            val sb = StringBuilder("[Diag] ONNX raw output first 10 points: ")
            for (i in 0 until 10) {
                sb.append("(${String.format("%.3f", rawOutput[i * 2])},${String.format("%.3f", rawOutput[i * 2 + 1])}) ")
            }
            Log.d(TAG, sb.toString())

            val transformStart = SystemClock.elapsedRealtime()
            val result = reusableResult
            val mappedPoint = reusableMappedPoint
            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()
            val halfInputSize = INPUT_SIZE / 2f
            for (index in 0 until POINT_COUNT) {
                mappedPoint[0] = (rawOutput[index * 2] + 1f) * halfInputSize
                mappedPoint[1] = (rawOutput[index * 2 + 1] + 1f) * halfInputSize
                crop.inverseTransform.mapPoints(mappedPoint)
                result[index * 2] = (mappedPoint[0] / bitmapWidth).coerceIn(0f, 1f)
                result[index * 2 + 1] = (mappedPoint[1] / bitmapHeight).coerceIn(0f, 1f)
            }
            val transformElapsed = SystemClock.elapsedRealtime() - transformStart
            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            Log.d(TAG, "[Perf] 2d106det DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms, crop=${cropElapsed}ms, inference=${inferenceElapsed}ms, transform=${transformElapsed}ms")
            result
        } catch (error: Exception) {
            Log.e(TAG, "[Diag] InsightFace 2D106 detection failed", error)
            null
        }
    }

    fun release() {
        runCatching { ortSession?.close() }
        det10gDetector?.release()
        det10gDetector = null
        ortSession = null
        inputName = null
    }

    // [性能优化] 复用 Bitmap 池，避免每帧 createBitmap
    private var reusableCropBitmap: Bitmap? = null
    private var reusableCropCanvas: Canvas? = null

    private fun getReusableCropBitmap(): Bitmap {
        var bmp = reusableCropBitmap
        if (bmp == null || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusableCropBitmap = bmp
        }
        return bmp
    }

    private fun getReusableCropCanvas(): Canvas {
        var cvs = reusableCropCanvas
        if (cvs == null) {
            cvs = Canvas(getReusableCropBitmap())
            reusableCropCanvas = cvs
        } else {
            cvs.setBitmap(getReusableCropBitmap())
        }
        return cvs
    }

    private fun initialize() {
        runCatching {
            // 初始化 Det10G 检测器（内部使用）
            det10gDetector = InsightFaceDet10GDetector(appContext)

            val modelFile = ModelManager.prepareModel(MODEL_KEY, appContext)

            // [优化] 配置 ONNX Runtime SessionOptions,强制使用 GPU 推理
            val sessionOptions = OrtSession.SessionOptions()

            try {
                // NNAPI 配置: 使用 FP16 加速,允许 GPU/NPU 执行支持的操作
                val nnapiFlags = java.util.EnumSet.of(
                    ai.onnxruntime.providers.NNAPIFlags.USE_FP16
                )
                sessionOptions.addNnapi(nnapiFlags)
                isGpuEnabled = true
                Log.i(TAG, "NNAPI execution provider enabled with FP16 for 2D106 (flags=$nnapiFlags)")
            } catch (e: Exception) {
                isGpuEnabled = false
                Log.w(TAG, "NNAPI not available for 2D106, falling back to CPU", e)
            }

            ortSession = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
            inputName = ortSession?.inputNames?.firstOrNull()
            resolveInputNormalization(modelFile)
            Log.i(
                TAG,
                "InsightFace 2D106 initialized: ${modelFile.absolutePath}, mean=$inputMean, std=$inputStd"
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize InsightFace 2D106", error)
            ortSession = null
            inputName = null
            det10gDetector = null
            inputMean = DEFAULT_INPUT_MEAN
            inputStd = DEFAULT_INPUT_STD
        }
    }



    // [性能优化] 复用 Matrix 对象，避免每帧创建
    private val reusableTransformMatrix = Matrix()
    private val reusableInverseMatrix = Matrix()

    private fun buildLooseFaceCrop(bitmap: Bitmap, faceBounds: Rect): LooseCrop? {
        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        val looseSize = max(faceWidth, faceHeight) * LOOSE_CROP_SCALE
        if (looseSize <= 0f) {
            return null
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val inputScale = INPUT_SIZE / looseSize

        reusableTransformMatrix.setValues(
            floatArrayOf(
                inputScale, 0f, INPUT_SIZE / 2f - centerX * inputScale,
                0f, inputScale, INPUT_SIZE / 2f - centerY * inputScale,
                0f, 0f, 1f
            )
        )
        reusableInverseMatrix.reset()
        reusableTransformMatrix.invert(reusableInverseMatrix)

        val croppedBitmap = getReusableCropBitmap()
        val canvas = getReusableCropCanvas()
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, reusableTransformMatrix, null)
        return LooseCrop(bitmap = croppedBitmap, inverseTransform = Matrix(reusableInverseMatrix))
    }

    private fun runInference(session: OrtSession, bitmap: Bitmap): FloatArray {
        val inputTensor = createInputTensor(bitmap)
        try {
            val modelInputName = inputName ?: error("InsightFace input name missing")
            val results = session.run(mapOf(modelInputName to inputTensor))
            try {
                val outputTensor = results[0] as OnnxTensor
                val flattened = flattenFloatArray(outputTensor.value)
                    ?: error("Unsupported InsightFace output type: ${outputTensor.value?.javaClass}")
                require(flattened.size >= POINT_COUNT * 2) {
                    "Unexpected InsightFace output length: ${flattened.size}"
                }
                return if (flattened.size == POINT_COUNT * 2) {
                    flattened
                } else {
                    flattened.copyOfRange(flattened.size - POINT_COUNT * 2, flattened.size)
                }
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun createInputTensor(bitmap: Bitmap): OnnxTensor {
        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val pixels = reusablePixelBuffer
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

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

        return OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(chw),
            longArrayOf(1, INPUT_CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
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

    private data class LooseCrop(
        val bitmap: Bitmap,
        val inverseTransform: Matrix
    )
}

