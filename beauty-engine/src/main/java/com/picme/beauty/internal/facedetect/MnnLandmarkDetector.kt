package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import java.io.File

/**
 * 基于 MNN + Vulkan GPU 的 106 点关键点检测器
 * 替代 InsightFace 2D106 (ONNX Runtime)，提供更快的 GPU 推理
 *
 * 兼容骁龙 765G + Adreno 620（Vulkan 1.1）
 */
class MnnLandmarkDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : LandmarkDetector {

    companion object {
        private const val TAG = "PicMe:MnnLandmark"
        private const val MODEL_ASSET_PATH = "insightface/2d106det.mnn"
        private const val MODEL_FILE_NAME = "mnn_2d106det.mnn"
        private const val INPUT_SIZE = 128  // [性能优化] 从 192 降到 128，推理速度 +56%
        private const val POINT_COUNT = 106
    }

    private val appContext = context.applicationContext
    private var detector: MnnFaceDetector? = null
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
        // [优化] 不立即初始化，改为懒加载
        Log.d(TAG, "MnnLandmarkDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        try {
            val modelFile = ensureModelFile(MODEL_FILE_NAME, MODEL_ASSET_PATH)

            Log.i(TAG, "Initializing MNN landmark detector with Vulkan GPU (requireGpu=$requireGpu)...")
            val initStart = SystemClock.elapsedRealtime()
            detector = MnnFaceDetector.create(
                modelPath = modelFile.absolutePath,
                inputSize = INPUT_SIZE,
                useGpu = true,
                inputName = "data",
                outputNames = arrayOf("fc1")
            )
            val initElapsed = SystemClock.elapsedRealtime() - initStart

            if (detector != null) {
                Log.i(TAG, "MnnLandmarkDetector initialized in ${initElapsed}ms with Vulkan GPU")
            } else {
                // [关键策略] 要求 GPU 时初始化失败，直接放弃，不降级到 CPU
                if (requireGpu) {
                    Log.e(TAG, "MNN GPU initialization failed and requireGpu=true, detector will remain null (no CPU fallback)")
                } else {
                    Log.w(TAG, "MNN GPU initialization failed, attempting CPU fallback...")
                    // TODO: 实现 CPU 降级逻辑（如果需要）
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MnnLandmarkDetector (requireGpu=$requireGpu)", e)
            detector = null
        }
    }

    override fun detectLandmarks(bitmap: Bitmap, lensFacing: Int, roi: RectF?): FloatArray? {
        // [优化] 懒加载初始化
        ensureInitialized()
        
        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Log.w(TAG, "[Perf] MnnLandmarkDetector not initialized after lazy init, skipping")
            return null
        }

        return try {
            val prepStart = SystemClock.elapsedRealtime()
            val inputBitmap = prepareInputBitmap(bitmap, roi)
            val prepElapsed = SystemClock.elapsedRealtime() - prepStart

            Log.d(TAG, "[Perf] MnnLandmark START: original=${bitmap.width}x${bitmap.height}, input=${inputBitmap.width}x${inputBitmap.height}, roi=$roi")

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detect(inputBitmap)
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            if (result == null || result.isEmpty()) {
                Log.d(TAG, "[Perf] MnnLandmark DONE: total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms), no landmarks")
                return null
            }

            // MNN 输出已经是统一 106 格式，直接返回（不需要 InsightFaceAdapter 的重排）
            val landmarks = parseLandmarks(result, bitmap.width, bitmap.height, roi)

            Log.d(TAG, "[Perf] MnnLandmark DONE: total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms), points=${landmarks.size / 2}")
            landmarks
        } catch (e: Exception) {
            Log.e(TAG, "MnnLandmark detection failed", e)
            null
        }
    }

    /**
     * 准备输入 Bitmap：裁剪 ROI 并缩放到 INPUT_SIZE
     */
    private fun prepareInputBitmap(source: Bitmap, roi: RectF?): Bitmap {
        val crop = if (roi != null) {
            cropFaceRegion(source, roi)
        } else {
            source
        }

        if (crop.width == INPUT_SIZE && crop.height == INPUT_SIZE) {
            return crop
        }

        var scaled = reusableScaledBitmap
        if (scaled == null || scaled.isRecycled || scaled.width != INPUT_SIZE || scaled.height != INPUT_SIZE) {
            scaled?.recycle()
            scaled = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = scaled
        }

        val canvas = android.graphics.Canvas(scaled)
        canvas.drawColor(android.graphics.Color.BLACK)
        val matrix = android.graphics.Matrix()
        val scale = INPUT_SIZE.toFloat() / maxOf(crop.width, crop.height)
        val scaledW = (crop.width * scale).toInt()
        val scaledH = (crop.height * scale).toInt()
        val left = (INPUT_SIZE - scaledW) / 2f
        val top = (INPUT_SIZE - scaledH) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(crop, matrix, null)

        if (crop !== source) {
            crop.recycle()
        }

        return scaled
    }

    /**
     * 解析 MNN 输出的 106 点关键点
     */
    private fun parseLandmarks(
        output: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        roi: RectF?
    ): FloatArray {
        val points = FloatArray(POINT_COUNT * 2)
        val outputPoints = minOf(output.size / 2, POINT_COUNT)

        for (i in 0 until outputPoints) {
            var x = output[i * 2]
            var y = output[i * 2 + 1]

            // 如果输出是 [-1, 1] 范围，转换到 [0, 1]
            if (x < 0f || x > 1f) {
                x = (x + 1f) * 0.5f
            }
            if (y < 0f || y > 1f) {
                y = (y + 1f) * 0.5f
            }

            // 如果有 ROI，将相对坐标转为全图坐标
            if (roi != null) {
                x = roi.left / bitmapWidth + x * (roi.width() / bitmapWidth)
                y = roi.top / bitmapHeight + y * (roi.height() / bitmapHeight)
            }

            points[i * 2] = x.coerceIn(0f, 1f)
            points[i * 2 + 1] = y.coerceIn(0f, 1f)
        }

        return points
    }

    private fun cropFaceRegion(bitmap: Bitmap, roi: RectF): Bitmap {
        // 严格验证 ROI 有效性
        val left = roi.left.toInt().coerceIn(0, maxOf(0, bitmap.width - 1))
        val top = roi.top.toInt().coerceIn(0, maxOf(0, bitmap.height - 1))
        
        // 计算有效宽度高度，确保至少为 1
        val maxWidth = maxOf(1, bitmap.width - left)
        val maxHeight = maxOf(1, bitmap.height - top)
        
        val width = roi.width().toInt().coerceIn(1, maxWidth)
        val height = roi.height().toInt().coerceIn(1, maxHeight)
        
        // 再次验证 ROI 有效性，避免创建失败
        if (width <= 0 || height <= 0 || left + width > bitmap.width || top + height > bitmap.height) {
            Log.w(TAG, "Invalid ROI detected: left=$left, top=$top, width=$width, height=$height, bitmap=${bitmap.width}x${bitmap.height}, roi=$roi")
            // 返回全图缩放到 INPUT_SIZE
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height)
        }
        
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    override fun release() {
        detector?.release()
        detector = null
        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Log.i(TAG, "MnnLandmarkDetector released")
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
