package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.picme.beauty.internal.facedetect.ncnn.NcnnFaceDetector
import java.io.File

/**
 * 基于 NCNN 的 106 点关键点检测器
 * 替代 InsightFace 2D106 (ONNX Runtime)，提供轻量级 GPU 推理
 */
class NcnnLandmarkDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : LandmarkDetector {

    companion object {
        private const val TAG = "PicMe:NcnnLandmark"
        private const val MODEL_PARAM_ASSET_PATH = "insightface/2d106det.param"
        private const val MODEL_BIN_ASSET_PATH = "insightface/2d106det.bin"
        private const val MODEL_PARAM_FILE_NAME = "ncnn_2d106det.param"
        private const val MODEL_BIN_FILE_NAME = "ncnn_2d106det.bin"
        private const val INPUT_SIZE = 192
        private const val POINT_COUNT = 106
    }

    private val appContext = context.applicationContext
    private var detector: NcnnFaceDetector? = null
    private var isInitialized = false

    // [性能优化] 复用 Bitmap 池
    private var reusableScaledBitmap: Bitmap? = null

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

    init {
        Log.d(TAG, "NcnnLandmarkDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        try {
            val paramFile = ensureModelFile(MODEL_PARAM_FILE_NAME, MODEL_PARAM_ASSET_PATH)
            val binFile = ensureModelFile(MODEL_BIN_FILE_NAME, MODEL_BIN_ASSET_PATH)

            Log.i(TAG, "Initializing NCNN landmark detector (requireGpu=$requireGpu)...")
            Log.d(TAG, "Model files: param=${paramFile.absolutePath} (${paramFile.length()} bytes), bin=${binFile.absolutePath} (${binFile.length()} bytes)")

            val initStart = SystemClock.elapsedRealtime()
            detector = NcnnFaceDetector.create(
                paramPath = paramFile.absolutePath,
                binPath = binFile.absolutePath,
                inputSize = INPUT_SIZE,
                useGpu = requireGpu,
                inputName = "data",
                outputNames = arrayOf("fc1")
            )
            val initElapsed = SystemClock.elapsedRealtime() - initStart

            if (detector != null) {
                Log.i(TAG, "NcnnLandmarkDetector initialized in ${initElapsed}ms")
                return
            }

            // 如果 GPU 初始化失败，尝试 CPU fallback
            if (requireGpu) {
                Log.w(TAG, "NCNN GPU initialization failed, attempting CPU fallback...")
                detector = NcnnFaceDetector.create(
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    inputSize = INPUT_SIZE,
                    useGpu = false,
                    inputName = "data",
                    outputNames = arrayOf("fc1")
                )
                if (detector != null) {
                    Log.i(TAG, "NcnnLandmarkDetector initialized with CPU fallback in ${SystemClock.elapsedRealtime() - initStart}ms")
                } else {
                    Log.e(TAG, "NCNN CPU fallback also failed, detector will remain null")
                }
            } else {
                Log.e(TAG, "NCNN CPU initialization failed, detector will remain null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NcnnLandmarkDetector (requireGpu=$requireGpu)", e)
            detector = null
        }
    }

    override fun detectLandmarks(bitmap: Bitmap, lensFacing: Int, roi: RectF?): FloatArray? {
        ensureInitialized()

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Log.w(TAG, "[Perf] NcnnLandmarkDetector not initialized after lazy init, skipping")
            return null
        }

        return try {
            val prepStart = SystemClock.elapsedRealtime()
            val cropResult = prepareInputBitmap(bitmap, roi)
            val prepElapsed = SystemClock.elapsedRealtime() - prepStart

            Log.d(TAG, "[Perf] NcnnLandmark START: original=${bitmap.width}x${bitmap.height}, input=${cropResult.bitmap.width}x${cropResult.bitmap.height}, roi=$roi")

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detect(cropResult.bitmap)
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart

            if (result != null && result.isNotEmpty()) {
                val sb = StringBuilder("[Diag] NCNN raw output first 10 points: ")
                for (i in 0 until minOf(10, result.size / 2)) {
                    sb.append("(${String.format("%.3f", result[i * 2])},${String.format("%.3f", result[i * 2 + 1])}) ")
                }
                Log.d(TAG, sb.toString())
            }

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            if (result == null || result.isEmpty()) {
                Log.d(TAG, "[Perf] NcnnLandmark DONE: total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms), no landmarks")
                return null
            }

            val landmarks = parseLandmarks(result, bitmap.width, bitmap.height, cropResult.inverseTransform)

            Log.d(TAG, "[Perf] NcnnLandmark DONE: total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms), points=${landmarks.size / 2}")
            landmarks
        } catch (e: Exception) {
            Log.e(TAG, "NcnnLandmark detection failed", e)
            null
        }
    }

    /**
     * 裁剪信息，包含裁剪后的 Bitmap 和逆变换矩阵
     */
    private data class CropResult(
        val bitmap: Bitmap,
        val inverseTransform: Matrix
    )

    /**
     * 准备输入 Bitmap：裁剪 ROI 并缩放到 INPUT_SIZE
     */
    private fun prepareInputBitmap(source: Bitmap, roi: RectF?): CropResult {
        val faceBounds = if (roi != null) {
            android.graphics.Rect(
                roi.left.toInt().coerceIn(0, source.width),
                roi.top.toInt().coerceIn(0, source.height),
                roi.right.toInt().coerceIn(0, source.width),
                roi.bottom.toInt().coerceIn(0, source.height)
            )
        } else {
            android.graphics.Rect(0, 0, source.width, source.height)
        }

        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        val looseSize = maxOf(faceWidth, faceHeight)
        if (looseSize <= 0f) {
            return buildFallbackCrop(source)
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val inputScale = INPUT_SIZE / looseSize

        val transformMatrix = Matrix()
        transformMatrix.setValues(
            floatArrayOf(
                inputScale, 0f, INPUT_SIZE / 2f - centerX * inputScale,
                0f, inputScale, INPUT_SIZE / 2f - centerY * inputScale,
                0f, 0f, 1f
            )
        )

        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)

        var scaled = reusableScaledBitmap
        if (scaled == null || scaled.isRecycled || scaled.width != INPUT_SIZE || scaled.height != INPUT_SIZE) {
            scaled?.recycle()
            scaled = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = scaled
        }

        val canvas = Canvas(scaled)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(source, transformMatrix, null)

        return CropResult(scaled, inverseMatrix)
    }

    /**
     * 回退裁剪：当 ROI 无效时，使用全图居中缩放
     */
    private fun buildFallbackCrop(source: Bitmap): CropResult {
        val sourceWidth = source.width.toFloat()
        val sourceHeight = source.height.toFloat()
        val scale = INPUT_SIZE / maxOf(sourceWidth, sourceHeight)
        val scaledW = sourceWidth * scale
        val scaledH = sourceHeight * scale
        val left = (INPUT_SIZE - scaledW) / 2f
        val top = (INPUT_SIZE - scaledH) / 2f

        val transformMatrix = Matrix()
        transformMatrix.setValues(
            floatArrayOf(
                scale, 0f, left,
                0f, scale, top,
                0f, 0f, 1f
            )
        )

        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)

        var scaled = reusableScaledBitmap
        if (scaled == null || scaled.isRecycled || scaled.width != INPUT_SIZE || scaled.height != INPUT_SIZE) {
            scaled?.recycle()
            scaled = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = scaled
        }

        val canvas = Canvas(scaled)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(source, transformMatrix, null)

        return CropResult(scaled, inverseMatrix)
    }

    /**
     * 解析 NCNN 输出的 106 点关键点
     * 使用逆变换矩阵将模型坐标系 [-1, 1] 映射回原始图像坐标系
     */
    private fun parseLandmarks(
        output: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        inverseTransform: Matrix
    ): FloatArray {
        val points = FloatArray(POINT_COUNT * 2)
        val outputPoints = minOf(output.size / 2, POINT_COUNT)
        val halfInputSize = INPUT_SIZE / 2f
        val mappedPoint = floatArrayOf(0f, 0f)

        for (i in 0 until outputPoints) {
            val x = output[i * 2]
            val y = output[i * 2 + 1]

            // 模型输出 [-1, 1] → INPUT_SIZE 像素坐标
            mappedPoint[0] = (x + 1f) * halfInputSize
            mappedPoint[1] = (y + 1f) * halfInputSize

            // 使用逆变换矩阵映射回原始图像坐标
            inverseTransform.mapPoints(mappedPoint)

            // 归一化到 [0, 1]
            points[i * 2] = (mappedPoint[0] / bitmapWidth).coerceIn(0f, 1f)
            points[i * 2 + 1] = (mappedPoint[1] / bitmapHeight).coerceIn(0f, 1f)
        }

        return points
    }

    override fun release() {
        detector?.release()
        detector = null
        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Log.i(TAG, "NcnnLandmarkDetector released")
    }

    private fun ensureModelFile(fileName: String, assetPath: String): File {
        val file = File(appContext.filesDir, fileName)
        if (file.exists() && file.length() > 0L) {
            return file
        }

        try {
            appContext.assets.open(assetPath).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model copied from assets: $assetPath -> ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets: $assetPath", e)
            throw e
        }

        return file
    }
}
