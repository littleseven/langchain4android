package com.picme.beauty.internal.facedetect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
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
        private const val MODEL_ASSET_PATH = "insightface/2d106det.onnx"
        private const val MODEL_FILE_NAME = "insightface_2d106det.onnx"
        private const val INPUT_SIZE = 192
        private const val INPUT_CHANNELS = 3
        private const val POINT_COUNT = 106
        private const val LOOSE_CROP_SCALE = 1f
        private const val DEFAULT_INPUT_MEAN = 127.5f
        private const val DEFAULT_INPUT_STD = 128.0f
    }

    private val appContext = context.applicationContext
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()

    // 内部 Det10G 检测器（不对外暴露）
    private var det10gDetector: InsightFaceDet10GDetector? = null

    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private var inputMean: Float = DEFAULT_INPUT_MEAN
    private var inputStd: Float = DEFAULT_INPUT_STD

    // [性能优化] 复用像素和 CHW 缓冲区，避免每帧 new IntArray/FloatArray
    private val reusablePixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val reusableChwBuffer = FloatArray(INPUT_CHANNELS * INPUT_SIZE * INPUT_SIZE)
    // [性能优化] 复用结果数组和临时点数组
    private val reusableResult = FloatArray(POINT_COUNT * 2)
    private val reusableMappedPoint = floatArrayOf(0f, 0f)

    init {
        initialize()
    }

    fun isReady(): Boolean = ortSession != null && det10gDetector?.isReady() == true

    /**
     * 检测人脸关键点
     *
     * @param bitmap 输入图像
     * @param lensFacing 镜头方向（用于坐标调整）
     * @param faceBounds 人脸边界框（可选），如果为 null 则内部自动使用 Det10G 检测
     * @return 106 点归一化坐标数组，未检测到返回 null
     */
    fun detect(bitmap: Bitmap, lensFacing: Int, faceBounds: android.graphics.RectF? = null): FloatArray? {
        val session = ortSession ?: return null

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
            Log.d(TAG, "[Diag] Det10G provided faceBounds=$bounds")
            bounds
        }

        Log.d(TAG, "[Diag] 2d106det START: bitmap=${bitmap.width}x${bitmap.height}, faceBounds=$actualFaceBounds")
        return try {
            // 将 RectF 转换为 Rect
            val rectBounds = android.graphics.Rect(
                actualFaceBounds.left.toInt().coerceIn(0, bitmap.width),
                actualFaceBounds.top.toInt().coerceIn(0, bitmap.height),
                actualFaceBounds.right.toInt().coerceIn(0, bitmap.width),
                actualFaceBounds.bottom.toInt().coerceIn(0, bitmap.height)
            )
            Log.d(TAG, "[Diag] 2d106det rectBounds=$rectBounds")

            val crop = buildLooseFaceCrop(bitmap, rectBounds) ?: run {
                Log.w(TAG, "[Diag] 2d106det buildLooseFaceCrop returned null")
                return null
            }
            Log.d(TAG, "[Diag] 2d106det crop bitmap=${crop.bitmap.width}x${crop.bitmap.height}")

            val rawOutput = runInference(session, crop.bitmap)
            crop.bitmap.recycle()
            Log.d(TAG, "[Diag] 2d106det rawOutput size=${rawOutput.size}")

            // [调试] 采样 rawOutput 范围用于诊断
            val sampleValues = (0 until minOf(POINT_COUNT, 10)).flatMap {
                listOf(rawOutput[it * 2], rawOutput[it * 2 + 1])
            }
            Log.d(TAG, "[Diag] rawOutput sample values: $sampleValues")

            // [性能优化] 复用预分配的 result 和 mappedPoint 数组
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

            // [调试] 打印前3个点的归一化坐标
            Log.d(TAG, "[Diag] 2d106det RESULT: firstPoint=(${result[0]},${result[1]}), secondPoint=(${result[2]},${result[3]}), thirdPoint=(${result[4]},${result[5]})")
            Log.d(TAG, "[Diag] 2d106det lastPoint=(${result[210]},${result[211]})")
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
            
            val modelFile = ensureModelFile()
            
            // [优化] 配置 ONNX Runtime SessionOptions,优先使用 GPU
            val sessionOptions = OrtSession.SessionOptions()
            
            try {
                // 尝试添加 NNAPI (Android GPU/NPU 加速)
                sessionOptions.addNnapi()
                Log.i(TAG, "NNAPI execution provider enabled for 2D106")
            } catch (e: Exception) {
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

