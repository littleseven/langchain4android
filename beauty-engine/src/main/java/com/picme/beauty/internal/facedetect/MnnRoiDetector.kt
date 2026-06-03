package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.picme.beauty.api.Logger
import com.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.picme.beauty.internal.model.ModelManager
import java.io.File

/**
 * 基于 MNN + Vulkan GPU 的 ROI 检测器
 * 替代 InsightFace Det10G (ONNX Runtime)，提供更快的 GPU 推理
 *
 * 兼容骁龙 765G + Adreno 620（Vulkan 1.1）
 */
class MnnRoiDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : RoiDetector {

    companion object {
        private const val TAG = "MnnRoi"
        private const val MODEL_KEY = "det10g_mnn"
        private const val INPUT_SIZE = 640  // [对齐 ONNX] 使用与 ONNX 相同的输入尺寸，确保检测结果一致
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val ROI_EXPAND_RATIO = 1.2f  // [对齐 ONNX] ROI 扩展比例，与 InsightFaceDet10G 一致
        private const val ENGINE_NAME = "MNN-Vulkan"

        // RetinaFace 9 个输出层名称（与 MNNConvert 输出一致）
        private val OUTPUT_NAMES = arrayOf(
            "448", "471", "494",   // scale 1: score, bbox, landmark
            "451", "474", "497",   // scale 2: score, bbox, landmark
            "454", "477", "500"    // scale 3: score, bbox, landmark
        )
    }

    private val appContext = context.applicationContext
    private var detector: MnnFaceDetector? = null
    private var isInitialized = false
    private var isGpuEnabled: Boolean = false

    // [性能优化] Bitmap 缩放复用池
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
        Logger.d(TAG, "MnnRoiDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        try {
            val modelFile = ModelManager.prepareModel(MODEL_KEY, appContext)

            Logger.i(TAG, "Initializing MNN RetinaFace detector with Vulkan GPU (requireGpu=$requireGpu)...")
            val initStart = SystemClock.elapsedRealtime()
            detector = MnnFaceDetector.create(
                modelPath = modelFile.absolutePath,
                inputSize = INPUT_SIZE,
                useGpu = requireGpu,
                inputName = "input.1",
                outputNames = OUTPUT_NAMES
            )
            val initElapsed = SystemClock.elapsedRealtime() - initStart

            if (detector != null) {
                isGpuEnabled = true
                Logger.i(TAG, "MnnRoiDetector initialized in ${initElapsed}ms with Vulkan GPU")
            } else {
                // [关键策略] 要求 GPU 时初始化失败，直接放弃，不降级到 CPU
                isGpuEnabled = false
                if (requireGpu) {
                    Logger.e(TAG, "MNN GPU initialization failed and requireGpu=true, detector will remain null (no CPU fallback)")
                } else {
                    Logger.w(TAG, "MNN GPU initialization failed, attempting CPU fallback...")
                    // TODO: 实现 CPU 降级逻辑（如果需要）
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize MnnRoiDetector (requireGpu=$requireGpu)", e)
            detector = null
        }
    }

    override fun detectRoi(bitmap: Bitmap): RectF? {
        // [优化] 懒加载初始化
        ensureInitialized()

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Logger.w(TAG, "[Perf] MnnRoiDetector not initialized after lazy init, skipping")
            return null
        }

        return try {
            val scaleStart = SystemClock.elapsedRealtime()
            val scaledBitmap = getScaledBitmap(bitmap, INPUT_SIZE)
            val scaleElapsed = SystemClock.elapsedRealtime() - scaleStart

            Logger.d(TAG, "[Perf] MnnRoi START: engine=$ENGINE_NAME, gpu=$isGpuEnabled, original=${bitmap.width}x${bitmap.height}, scaled=${scaledBitmap.width}x${scaledBitmap.height}")

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detectRetinaFace(scaledBitmap, CONFIDENCE_THRESHOLD, 0.4f)
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            if (result == null || result.size < 5) {
                Logger.d(TAG, "[Perf] MnnRoi DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms), no face")
                return null
            }

            // result: [x1, y1, x2, y2, score, landmarks(10)]
            // [关键修复] MNN native 层输出的是 320x320 letterbox 空间的坐标
            // 需要逆向 letterbox 变换映射回原图尺寸
            val origW = bitmap.width.toFloat()
            val origH = bitmap.height.toFloat()
            val scale = INPUT_SIZE.toFloat() / maxOf(origW, origH)
            val scaledW = (origW * scale).toInt()
            val scaledH = (origH * scale).toInt()
            val padLeft = (INPUT_SIZE - scaledW) / 2f
            val padTop = (INPUT_SIZE - scaledH) / 2f

            Logger.d(TAG, "[Diag] Letterbox params: scale=$scale, scaledSize=${scaledW}x${scaledH}, pad=($padLeft,$padTop)")
            Logger.d(TAG, "[Diag] Raw MNN output: (${result[0]}, ${result[1]}, ${result[2]}, ${result[3]}), score=${result[4]}")

            // [对齐 ONNX] 1. 减去 letterbox padding，再除以缩放比例，映射回原图
            var mappedX1 = ((result[0] - padLeft) / scale)
            var mappedY1 = ((result[1] - padTop) / scale)
            var mappedX2 = ((result[2] - padLeft) / scale)
            var mappedY2 = ((result[3] - padTop) / scale)

            // [对齐 ONNX] 2. 放大 ROI 区域，以包含更多面部上下文
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

            val roi = RectF(mappedX1, mappedY1, mappedX2, mappedY2)

            Logger.d(TAG, "[Diag] ROI coords: (${roi.left.toInt()},${roi.top.toInt()},${roi.right.toInt()},${roi.bottom.toInt()}), size=${(roi.right-roi.left).toInt()}x${(roi.bottom-roi.top).toInt()}")

            Logger.i(TAG, "[Perf] MnnRoi DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms), GPU✓")
            roi
        } catch (e: Exception) {
            Logger.e(TAG, "MnnRoi detection failed", e)
            null
        }
    }

    /**
     * 获取复用的缩放 Bitmap，避免每帧创建
     */
    private fun getScaledBitmap(source: Bitmap, targetSize: Int): Bitmap {
        if (source.width == targetSize && source.height == targetSize) {
            return source
        }
        var bmp = reusableScaledBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != targetSize || bmp.height != targetSize) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = bmp
        }
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        val matrix = android.graphics.Matrix()
        val scale = targetSize.toFloat() / maxOf(source.width, source.height)
        val scaledW = (source.width * scale).toInt()
        val scaledH = (source.height * scale).toInt()
        val left = (targetSize - scaledW) / 2f
        val top = (targetSize - scaledH) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(source, matrix, null)
        return bmp
    }

    override fun release() {
        detector?.release()
        detector = null
        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Logger.i(TAG, "MnnRoiDetector released")
    }

}
