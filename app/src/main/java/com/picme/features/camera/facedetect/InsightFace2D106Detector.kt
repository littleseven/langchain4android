package com.picme.features.camera.facedetect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.CameraSelector
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
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
        private const val INPUT_MEAN = 127.5f
        private const val INPUT_STD = 128.0f
    }

    private val appContext = context.applicationContext
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )

    private var ortSession: OrtSession? = null
    private var inputName: String? = null

    init {
        initialize()
    }

    fun isReady(): Boolean = ortSession != null

    fun detect(bitmap: Bitmap, lensFacing: Int): FloatArray? {
        val session = ortSession ?: return null
        return try {
            val faceBounds = detectLargestFaceBounds(bitmap) ?: return null
            val crop = buildLooseFaceCrop(bitmap, faceBounds) ?: return null
            val rawOutput = runInference(session, crop.bitmap)
            crop.bitmap.recycle()

            val result = FloatArray(POINT_COUNT * 2)
            val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
            val scale = crop.looseSize / INPUT_SIZE.toFloat()
            for (index in 0 until POINT_COUNT) {
                val cropX = (rawOutput[index * 2] + 1f) * (INPUT_SIZE / 2f)
                val cropY = (rawOutput[index * 2 + 1] + 1f) * (INPUT_SIZE / 2f)
                var normalizedX = (crop.left + cropX * scale) / bitmap.width.toFloat()
                val normalizedY = (crop.top + cropY * scale) / bitmap.height.toFloat()
                if (isFrontCamera) {
                    normalizedX = 1f - normalizedX
                }
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
        runCatching { faceDetector.close() }
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
            Logger.i(TAG, "InsightFace 2D106 initialized: ${modelFile.absolutePath}")
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize InsightFace 2D106", error)
            ortSession = null
            inputName = null
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
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = Tasks.await(faceDetector.process(inputImage))
        val largestFace = faces.maxByOrNull { face -> face.boundingBox.width() * face.boundingBox.height() }
            ?: return null
        return largestFace.boundingBox
    }

    private fun buildLooseFaceCrop(bitmap: Bitmap, faceBounds: Rect): LooseCrop? {
        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        val looseSize = max(faceWidth, faceHeight) * LOOSE_CROP_SCALE
        val left = faceBounds.exactCenterX() - looseSize / 2f
        val top = faceBounds.exactCenterY() - looseSize / 2f
        val right = left + looseSize
        val bottom = top + looseSize

        val srcLeft = left.coerceAtLeast(0f).toInt()
        val srcTop = top.coerceAtLeast(0f).toInt()
        val srcRight = right.coerceAtMost(bitmap.width.toFloat()).toInt()
        val srcBottom = bottom.coerceAtMost(bitmap.height.toFloat()).toInt()
        if (srcRight <= srcLeft || srcBottom <= srcTop) {
            return null
        }

        val croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(croppedBitmap)
        val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
        val scale = INPUT_SIZE / looseSize
        val dstRect = RectF(
            (srcLeft - left) * scale,
            (srcTop - top) * scale,
            (srcRight - left) * scale,
            (srcBottom - top) * scale
        )
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        return LooseCrop(bitmap = croppedBitmap, left = left, top = top, looseSize = looseSize)
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
            chw[index] = (red - INPUT_MEAN) / INPUT_STD
            chw[pixelCount + index] = (green - INPUT_MEAN) / INPUT_STD
            chw[pixelCount * 2 + index] = (blue - INPUT_MEAN) / INPUT_STD
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

    private data class LooseCrop(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val looseSize: Float
    )
}

