package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.picme.beauty.internal.model.ModelManager
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
        private const val MODEL_KEY = "2d106_mnn"
        private const val INPUT_SIZE = 192  // [对齐 ONNX] 与 InsightFace2D106Detector 保持一致
        private const val POINT_COUNT = 106
        private const val ENGINE_NAME = "MNN-Vulkan"
    }

    private val appContext = context.applicationContext
    private var detector: MnnFaceDetector? = null
    private var isInitialized = false
    private var isGpuEnabled: Boolean = false

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
            val modelFile = ModelManager.prepareModel(MODEL_KEY, appContext)

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
                isGpuEnabled = true
                Log.i(TAG, "MnnLandmarkDetector initialized in ${initElapsed}ms with Vulkan GPU")
            } else {
                // [关键策略] 要求 GPU 时初始化失败，直接放弃，不降级到 CPU
                isGpuEnabled = false
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
            val cropResult = prepareInputBitmap(bitmap, roi)
            val prepElapsed = SystemClock.elapsedRealtime() - prepStart

            Log.d(TAG, "[Perf] MnnLandmark START: engine=$ENGINE_NAME, gpu=$isGpuEnabled, original=${bitmap.width}x${bitmap.height}, input=${cropResult.bitmap.width}x${cropResult.bitmap.height}, roi=$roi")

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detect(cropResult.bitmap)
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart

            // [诊断日志] 输出原始模型输出前 10 个点
            if (result != null && result.isNotEmpty()) {
                val sb = StringBuilder("[Diag] MNN raw output first 10 points: ")
                for (i in 0 until minOf(10, result.size / 2)) {
                    sb.append("(${String.format("%.3f", result[i * 2])},${String.format("%.3f", result[i * 2 + 1])}) ")
                }
                Log.d(TAG, sb.toString())
            }

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            if (result == null || result.isEmpty()) {
                Log.d(TAG, "[Perf] MnnLandmark DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms), no landmarks")
                return null
            }

            // 使用逆变换矩阵将模型输出坐标映射回原始图像
            val landmarks = parseLandmarks(result, bitmap.width, bitmap.height, cropResult.inverseTransform)

            Log.d(TAG, "[Perf] MnnLandmark DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms), points=${landmarks.size / 2}")
            landmarks
        } catch (e: Exception) {
            Log.e(TAG, "MnnLandmark detection failed", e)
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
     * 使用与 InsightFace2D106Detector 相同的变换逻辑：
     * - 以人脸中心为锚点缩放
     * - 将人脸中心对齐到 INPUT_SIZE/2
     * - 计算逆变换矩阵用于坐标映射回原始图像
     */
    private fun prepareInputBitmap(source: Bitmap, roi: RectF?): CropResult {
        // 计算有效的人脸边界框（与 ONNX 版本的 rectBounds 对应）
        val faceBounds = if (roi != null) {
            android.graphics.Rect(
                roi.left.toInt().coerceIn(0, source.width),
                roi.top.toInt().coerceIn(0, source.height),
                roi.right.toInt().coerceIn(0, source.width),
                roi.bottom.toInt().coerceIn(0, source.height)
            )
        } else {
            // 无 ROI 时使用全图
            android.graphics.Rect(0, 0, source.width, source.height)
        }

        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        val looseSize = maxOf(faceWidth, faceHeight)
        if (looseSize <= 0f) {
            // 回退：直接返回全图缩放
            return buildFallbackCrop(source)
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val inputScale = INPUT_SIZE / looseSize

        // [关键修复] 使用与 ONNX 版本完全相同的变换矩阵构造方式
        // 变换公式：dst = inputScale * src + (INPUT_SIZE/2 - center * inputScale)
        // 即将人脸中心 (centerX, centerY) 映射到画布中心 (INPUT_SIZE/2, INPUT_SIZE/2)
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
     * 解析 MNN 输出的 106 点关键点
     * 使用逆变换矩阵将模型坐标系 [-1, 1] 映射回原始图像坐标系
     *
     * 与 InsightFace2D106Detector 保持一致：
     * 1. 模型输出 [-1, 1] → INPUT_SIZE 像素坐标
     * 2. 逆变换矩阵映射回原始图像空间
     * 3. 归一化到 [0, 1]
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
            // 与 ONNX 版本完全一致：(value + 1) * halfInputSize
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
        Log.i(TAG, "MnnLandmarkDetector released")
    }

}
