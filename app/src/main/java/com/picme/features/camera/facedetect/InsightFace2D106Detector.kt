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
import com.picme.core.common.Logger
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * InsightFace 2D106 备选检测器。
 *
 * 方案：ML Kit 提供人脸框，InsightFace `2d106det.onnx` 输出 106 点。
 * 输出顺序与当前 106 点消费链路一致，直接复用 `Face106ToWarpParams`。
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


    private var ortSession: OrtSession? = null
    private var inputName: String? = null
    private var inputMean: Float = DEFAULT_INPUT_MEAN
    private var inputStd: Float = DEFAULT_INPUT_STD

    init {
        initialize()
    }

    fun isReady(): Boolean = ortSession != null

    fun detect(bitmap: Bitmap, lensFacing: Int, faceBounds: android.graphics.Rect? = null): FloatArray? {
        val session = ortSession ?: return null
        return try {
            val bounds = faceBounds ?: detectLargestFaceBounds(bitmap) ?: return null
            val crop = buildLooseFaceCrop(bitmap, bounds) ?: return null
            val rawOutput = runInference(session, crop.bitmap)
            crop.bitmap.recycle()

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
            result
        } catch (error: Exception) {
            Logger.e(TAG, "InsightFace 2D106 detection failed", error)
            null
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
                "InsightFace 2D106 initialized: ${modelFile.absolutePath}, mean=$inputMean, std=$inputStd"
            )
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize InsightFace 2D106", error)
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

    private fun detectLargestFaceBounds(bitmap: Bitmap): Rect? {
        // ML Kit 人脸框检测已移除：回退为使用整图中心区域作为人脸 ROI
        // InsightFace 2d106det.onnx 本身对居中人脸鲁棒性较好
        val paddingX = (bitmap.width * 0.05f).toInt().coerceAtLeast(0)
        val paddingY = (bitmap.height * 0.05f).toInt().coerceAtLeast(0)
        return Rect(
            paddingX,
            paddingY,
            (bitmap.width - paddingX).coerceAtLeast(paddingX + 1),
            (bitmap.height - paddingY).coerceAtLeast(paddingY + 1)
        )
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

    private data class LooseCrop(
        val bitmap: Bitmap,
        val inverseTransform: Matrix
    )
}

