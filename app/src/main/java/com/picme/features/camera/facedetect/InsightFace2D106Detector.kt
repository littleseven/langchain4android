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
        private const val LOOSE_CROP_SCALE = 1.5f
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
     * @return 106 点归一化坐标数组，未检测到返回 null
     */
    @ExperimentalGetImage
    fun detectFromImageProxy(imageProxy: ImageProxy, lensFacing: Int): FloatArray? {
        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return null
        return try {
            detect(bitmap, lensFacing)
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
                Logger.d(TAG, "[Diag] Det10G no face detected")
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

            val result = FloatArray(POINT_COUNT * 2)
            val mappedPoint = floatArrayOf(0f, 0f)
            for (index in 0 until POINT_COUNT) {
                mappedPoint[0] = (rawOutput[index * 2] + 1f) * (INPUT_SIZE / 2f)
                mappedPoint[1] = (rawOutput[index * 2 + 1] + 1f) * (INPUT_SIZE / 2f)
                crop.inverseTransform.mapPoints(mappedPoint)
                val normalizedX = mappedPoint[0] / bitmap.width.toFloat()
                val normalizedY = mappedPoint[1] / bitmap.height.toFloat()
                result[index * 2] = normalizedX.coerceIn(0f, 1f)
                result[index * 2 + 1] = normalizedY.coerceIn(0f, 1f)
            }
            Logger.d(TAG, "[Diag] 2d106det RESULT: firstPoint=(${result[0]},${result[1]}), lastPoint=(${result[210]},${result[211]})")
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
        val looseSize = max(faceWidth, faceHeight) * LOOSE_CROP_SCALE
        if (looseSize <= 0f) {
            return null
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val scale = INPUT_SIZE / looseSize
        val transform = Matrix().apply {
            postScale(scale, scale)
            postTranslate(
                INPUT_SIZE / 2f - centerX * scale,
                INPUT_SIZE / 2f - centerY * scale
            )
        }
        val inverseTransform = Matrix().apply {
            transform.invert(this)
        }

        val croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(croppedBitmap)
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
        } else {
            inputMean = DEFAULT_INPUT_MEAN
            inputStd = DEFAULT_INPUT_STD
        }
    }

    /**
     * 将 ImageProxy 转换为 Bitmap
     * 处理 YUV420/NV21 格式、rowStride padding 和旋转
     */
    @ExperimentalGetImage
    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return imageProxy.image?.let { img ->
            // [关键修复] 处理 rowStride padding
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer
            
            val yRowStride = imageProxy.planes[0].rowStride
            val uRowStride = imageProxy.planes[1].rowStride
            val vRowStride = imageProxy.planes[2].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride
            
            val width = imageProxy.width
            val height = imageProxy.height
            val uvWidth = width / 2
            val uvHeight = height / 2
            
            // Y 平面大小（不含 padding）
            val yPlaneSize = width * height
            // UV 平面大小（不含 padding）
            val uvPlaneSize = uvWidth * uvHeight
            
            val nv21 = ByteArray(yPlaneSize + uvPlaneSize * 2)
            
            // 复制 Y plane（逐行，跳过 rowStride padding）
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                val dstPos = row * width
                yBuffer.position(srcPos)
                yBuffer.get(nv21, dstPos, width)
            }
            
            // 复制 UV planes
            val uvOffset = yPlaneSize
            
            if (uvPixelStride == 2) {
                // NV21 格式：VU 交错
                for (row in 0 until uvHeight) {
                    val uSrcPos = row * uRowStride
                    val vSrcPos = row * vRowStride
                    val dstPos = uvOffset + row * uvWidth * 2
                    
                    for (col in 0 until uvWidth) {
                        nv21[dstPos + col * 2] = vBuffer.get(vSrcPos + col * 2)
                        nv21[dstPos + col * 2 + 1] = uBuffer.get(uSrcPos + col * 2)
                    }
                }
            } else {
                // I420 格式：U 和 V 是独立的平面
                // 需要转换为 NV21 (Y + VU 交错)
                for (row in 0 until uvHeight) {
                    val uSrcPos = row * uRowStride
                    val vSrcPos = row * vRowStride
                    val dstPos = uvOffset + row * uvWidth * 2
                    
                    for (col in 0 until uvWidth) {
                        nv21[dstPos + col * 2] = vBuffer.get(vSrcPos + col)
                        nv21[dstPos + col * 2 + 1] = uBuffer.get(uSrcPos + col)
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
                android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
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

