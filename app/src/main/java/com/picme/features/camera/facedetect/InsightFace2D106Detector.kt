package com.picme.features.camera.facedetect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.picme.core.common.Logger
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

    init {
        initialize()
    }

    fun isReady(): Boolean = ortSession != null && det10gDetector?.isReady() == true

    /**
     * 从 ImageProxy 检测人脸关键点（推荐用于相机预览）
     *
     * @param imageProxy CameraX ImageProxy
     * @param lensFacing 镜头方向（用于坐标调整）
     * @param faceBounds 可选的人脸框，如果提供则跳过 Det10G 检测
     * @return 106 点归一化坐标数组，未检测到返回 null
     */
    @ExperimentalGetImage
    fun detectFromImageProxy(imageProxy: ImageProxy, lensFacing: Int, faceBounds: android.graphics.RectF? = null): FloatArray? {
        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return null
        return try {
            detect(bitmap, lensFacing, faceBounds)
        } finally {
            bitmap.recycle()
        }
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
        val session = ortSession ?: return null

        // [两阶段检测] 第一阶段：如果没有提供 faceBounds，使用 Det10G 检测
        val actualFaceBounds = faceBounds ?: run {
            val det10g = det10gDetector
            if (det10g == null) {
                Logger.w(TAG, "[Diag] Det10G detector not ready")
                return null
            }
            val bounds = det10g.detectLargestFace(bitmap)
            if (bounds == null) {
                Logger.w(TAG, "[Diag] Det10G no face detected")
                return null
            }
            Logger.d(TAG, "[Diag] Det10G provided faceBounds=$bounds")
            bounds
        }

        Logger.d(TAG, "[Diag] 2d106det START: bitmap=${bitmap.width}x${bitmap.height}, faceBounds=$actualFaceBounds")
        return try {
            // 将 RectF 转换为 Rect
            val rectBounds = android.graphics.Rect(
                actualFaceBounds.left.toInt().coerceIn(0, bitmap.width),
                actualFaceBounds.top.toInt().coerceIn(0, bitmap.height),
                actualFaceBounds.right.toInt().coerceIn(0, bitmap.width),
                actualFaceBounds.bottom.toInt().coerceIn(0, bitmap.height)
            )
            Logger.d(TAG, "[Diag] 2d106det rectBounds=$rectBounds")

            val crop = buildLooseFaceCrop(bitmap, rectBounds) ?: run {
                Logger.w(TAG, "[Diag] 2d106det buildLooseFaceCrop returned null")
                return null
            }
            Logger.d(TAG, "[Diag] 2d106det crop bitmap=${crop.bitmap.width}x${crop.bitmap.height}")

            val rawOutput = runInference(session, crop.bitmap)
            crop.bitmap.recycle()
            Logger.d(TAG, "[Diag] 2d106det rawOutput size=${rawOutput.size}")

            // [调试] 采样 rawOutput 范围用于诊断
            val sampleValues = (0 until minOf(POINT_COUNT, 10)).flatMap {
                listOf(rawOutput[it * 2], rawOutput[it * 2 + 1])
            }
            Logger.d(TAG, "[Diag] rawOutput sample values: $sampleValues")

            // [修复] InsightFace 2d106det 模型输出在 [-1, 1] 范围，使用固定公式转换
            val result = FloatArray(POINT_COUNT * 2)
            val mappedPoint = floatArrayOf(0f, 0f)
            for (index in 0 until POINT_COUNT) {
                // 标准公式：将 [-1, 1] 映射到 [0, INPUT_SIZE]
                mappedPoint[0] = (rawOutput[index * 2] + 1f) * (INPUT_SIZE / 2f)
                mappedPoint[1] = (rawOutput[index * 2 + 1] + 1f) * (INPUT_SIZE / 2f)
                crop.inverseTransform.mapPoints(mappedPoint)
                val normalizedX = mappedPoint[0] / bitmap.width.toFloat()
                val normalizedY = mappedPoint[1] / bitmap.height.toFloat()
                result[index * 2] = normalizedX.coerceIn(0f, 1f)
                result[index * 2 + 1] = normalizedY.coerceIn(0f, 1f)
            }

            // [调试] 打印前3个点的归一化坐标
            Logger.d(TAG, "[Diag] 2d106det RESULT: firstPoint=(${result[0]},${result[1]}), secondPoint=(${result[2]},${result[3]}), thirdPoint=(${result[4]},${result[5]})")
            Logger.d(TAG, "[Diag] 2d106det lastPoint=(${result[210]},${result[211]})")
            result
        } catch (error: Exception) {
            Logger.e(TAG, "[Diag] InsightFace 2D106 detection failed", error)
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

    private fun initialize() {
        runCatching {
            // 初始化 Det10G 检测器（内部使用）
            det10gDetector = InsightFaceDet10GDetector(appContext)
            
            val modelFile = ensureModelFile()
            val sessionOptions = OrtSession.SessionOptions()
            ortSession = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
            inputName = ortSession?.inputNames?.firstOrNull()
            resolveInputNormalization(modelFile)
            Logger.i(
                TAG,
                "InsightFace 2D106 initialized: ${modelFile.absolutePath}, mean=$inputMean, std=$inputStd"
            )
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize InsightFace 2D106", error)
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



    private fun buildLooseFaceCrop(bitmap: Bitmap, faceBounds: Rect): LooseCrop? {
        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        // [修复] 不做任何扩展，直接使用原始人脸框
        val looseSize = max(faceWidth, faceHeight) * LOOSE_CROP_SCALE
        if (looseSize <= 0f) {
            return null
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val inputScale = INPUT_SIZE / looseSize
        // [修复] 使用 setValues 直接构造变换矩阵，避免 postScale+postTranslate 右乘导致平移被重复缩放
        val transform = Matrix().apply {
            setValues(
                floatArrayOf(
                    inputScale, 0f, INPUT_SIZE / 2f - centerX * inputScale,
                    0f, inputScale, INPUT_SIZE / 2f - centerY * inputScale,
                    0f, 0f, 1f
                )
            )
        }
        val inverseTransform = Matrix().apply {
            transform.invert(this)
        }

        val croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(croppedBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, transform, null)
        return LooseCrop(bitmap = croppedBitmap, inverseTransform = inverseTransform)
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
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

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
            Logger.i(TAG, "Model has built-in normalization nodes, using mean=0, std=1")
        } else {
            inputMean = DEFAULT_INPUT_MEAN
            inputStd = DEFAULT_INPUT_STD
            Logger.i(TAG, "Using default normalization: mean=$DEFAULT_INPUT_MEAN, std=$DEFAULT_INPUT_STD")
        }
        Logger.d(TAG, "[Diag] Model file size=${modelFile.length()}, hasSub=$hasSubNode, hasMul=$hasMulNode")
    }

    /**
     * 将 ImageProxy 转换为 Bitmap
     * 正确处理 YUV_420_888 到 NV21 的转换，包括 rowStride padding
     */
    @ExperimentalGetImage
    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return imageProxy.image?.let { img ->
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer
            
            val yRowStride = imageProxy.planes[0].rowStride
            val uvRowStride = imageProxy.planes[1].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride
            
            val width = imageProxy.width
            val height = imageProxy.height
            
            Logger.d(TAG, "[Debug] ImageProxy: ${width}x${height}, yRowStride=$yRowStride, uvRowStride=$uvRowStride, uvPixelStride=$uvPixelStride")
            
            // 创建标准的 NV21 数据（不含 padding）
            val nv21 = ByteArray(width * height + width * height / 2)
            
            // 复制 Y plane（逐行，跳过 rowStride padding）
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
            
            // 复制 UV plane
            if (uvPixelStride == 2) {
                // NV21 格式：V 和 U 交错存储
                // 直接从 vBuffer 复制（vBuffer 包含完整的 VU 交错数据）
                val uvHeight = height / 2
                val uvWidth = width / 2
                val bytesPerRow = uvWidth * 2
                for (row in 0 until uvHeight) {
                    val srcPos = row * uvRowStride
                    val bytesToCopy = minOf(bytesPerRow, vBuffer.limit() - srcPos)
                    if (bytesToCopy > 0) {
                        vBuffer.position(srcPos)
                        vBuffer.get(nv21, pos, bytesToCopy)
                        pos += bytesToCopy
                    }
                }
            } else {
                // I420 格式：U 和 V 是独立的平面
                // 需要手动交错为 VU
                val uvHeight = height / 2
                val uvWidth = width / 2
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        nv21[pos++] = vBuffer.get(row * uvRowStride + col)
                        nv21[pos++] = uBuffer.get(row * uvRowStride + col)
                    }
                }
            }
            
            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width,
                height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, width, height),
                100,
                out
            )
            val imageBytes = out.toByteArray()
            var bmp = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            bmp
        }
    }

    private data class LooseCrop(
        val bitmap: Bitmap,
        val inverseTransform: Matrix
    )
}

