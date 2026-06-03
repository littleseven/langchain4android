package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.picme.beauty.api.Logger
import com.picme.beauty.internal.facedetect.ncnn.NcnnFaceDetector
import com.picme.beauty.internal.model.ModelManager

/**
 * 基于 NCNN 的 ROI 检测器
 * 替代 InsightFace Det10G (ONNX Runtime)，提供轻量级 GPU 推理
 */
class NcnnRoiDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : RoiDetector {

    companion object {
        private const val TAG = "NcnnRoi"
        private const val MODEL_KEY = "det_500m_ncnn"
        private const val INPUT_SIZE = 320  // [RetinaFace-MobileNet0.25] 320×320 输入
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val ROI_EXPAND_RATIO = 1.2f
        private const val ENGINE_NAME = "NCNN-Vulkan"

        // [关键修复] 进程级全局锁：OpenMP 运行时初始化不是线程安全的，
        // 所有 NCNN 调用（包括 ROI 和 Landmark）必须串行化到同一线程。
        // 注意：必须与 NcnnLandmarkDetector 使用同一个锁对象。
        private val NCNN_GLOBAL_LOCK = NcnnRoiDetector::class.java

        // [det_500m] RetinaFace-MobileNet0.25 NCNN 9 个输出 blob 名称（PNNX→NCNN 转换后）
        // 尺度分组: stride 8/16/32，每个 3 输出 (score/bbox/landmark)
        // NCNN 输出: out0=score8, out1=score16, out2=score32,
        //            out3=bbox8, out4=bbox16, out5=bbox32,
        //            out6=landmark8, out7=landmark16, out8=landmark32
        private val OUTPUT_NAMES = arrayOf(
            "out0", "out1", "out2",  // score: stride 8/16/32
            "out3", "out4", "out5",  // bbox: stride 8/16/32
            "out6", "out7", "out8"   // landmark: stride 8/16/32
        )
    }

    private val appContext = context.applicationContext
    private var detector: NcnnFaceDetector? = null
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
        Logger.d(TAG, "NcnnRoiDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        // [修复崩溃] OpenMP 运行时初始化不是线程安全的，必须全局同步
        synchronized(NCNN_GLOBAL_LOCK) {
            try {
                val (paramFile, binFile) = ModelManager.prepareNcnnModel(MODEL_KEY, appContext)

                Logger.i(TAG, "Initializing NCNN RetinaFace detector (requireGpu=$requireGpu)...")
                Logger.d(TAG, "Model files: param=${paramFile.absolutePath} (${paramFile.length()} bytes), bin=${binFile.absolutePath} (${binFile.length()} bytes)")

                // [诊断] 验证文件可读性
                if (!paramFile.canRead()) {
                    Logger.e(TAG, "Param file not readable: ${paramFile.absolutePath}")
                }
                if (!binFile.canRead()) {
                    Logger.e(TAG, "Bin file not readable: ${binFile.absolutePath}")
                }

                val initStart = SystemClock.elapsedRealtime()
                detector = NcnnFaceDetector.create(
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    inputSize = INPUT_SIZE,
                    useGpu = requireGpu,
                    inputName = "in0",
                    outputNames = OUTPUT_NAMES
                )
                val initElapsed = SystemClock.elapsedRealtime() - initStart

                if (detector != null) {
                    isGpuEnabled = true
                    Logger.i(TAG, "NcnnRoiDetector initialized in ${initElapsed}ms")
                    return
                }

                // [CO指令] 不启用 fallback，直接报告失败
                isGpuEnabled = false
                Logger.e(TAG, "NCNN initialization FAILED (requireGpu=$requireGpu, no fallback per CO directive)")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to initialize NcnnRoiDetector (requireGpu=$requireGpu)", e)
                detector = null
            }
        }
    }

    override fun detectRoi(bitmap: Bitmap): RectF? {
        ensureInitialized()

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Logger.w(TAG, "[Perf] NcnnRoiDetector not initialized after lazy init, skipping")
            return null
        }

        // [关键修复] OpenMP 线程亲和性初始化不是线程安全的，
        // 所有 NCNN 调用（包括 ROI 和 Landmark）必须串行化到同一线程
        return synchronized(NCNN_GLOBAL_LOCK) {
            try {
                detectRoiLocked(bitmap, det, totalStart)
            } catch (e: Exception) {
                Logger.e(TAG, "NcnnRoi detection failed", e)
                null
            }
        }
    }

    private fun detectRoiLocked(bitmap: Bitmap, det: NcnnFaceDetector, totalStart: Long): RectF? {
        val scaleStart = SystemClock.elapsedRealtime()
        val scaledBitmap = getScaledBitmap(bitmap, INPUT_SIZE)
        val scaleElapsed = SystemClock.elapsedRealtime() - scaleStart

        Logger.d(TAG, "[Perf] NcnnRoi START: engine=$ENGINE_NAME, gpu=$isGpuEnabled, original=${bitmap.width}x${bitmap.height}, scaled=${scaledBitmap.width}x${scaledBitmap.height}")

        val inferStart = SystemClock.elapsedRealtime()
        val result = det.detectRetinaFace(scaledBitmap, CONFIDENCE_THRESHOLD, 0.3f)
        val inferElapsed = SystemClock.elapsedRealtime() - inferStart

        val totalElapsed = SystemClock.elapsedRealtime() - totalStart

        if (result == null || result.size < 5) {
            Logger.d(TAG, "[Perf] NcnnRoi DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms), no face")
            return null
        }

        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        val scale = INPUT_SIZE.toFloat() / maxOf(origW, origH)
        val scaledW = (origW * scale).toInt()
        val scaledH = (origH * scale).toInt()
        val padLeft = (INPUT_SIZE - scaledW) / 2f
        val padTop = (INPUT_SIZE - scaledH) / 2f

        Logger.d(TAG, "[Diag] Letterbox params: scale=$scale, scaledSize=${scaledW}x${scaledH}, pad=($padLeft,$padTop)")
        Logger.d(TAG, "[Diag] Raw NCNN output: (${result[0]}, ${result[1]}, ${result[2]}, ${result[3]}), score=${result[4]}")

        var mappedX1 = ((result[0] - padLeft) / scale)
        var mappedY1 = ((result[1] - padTop) / scale)
        var mappedX2 = ((result[2] - padLeft) / scale)
        var mappedY2 = ((result[3] - padTop) / scale)

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
        Logger.i(TAG, "[Perf] NcnnRoi DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms)")
        return roi
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
        Logger.i(TAG, "NcnnRoiDetector released")
    }

}
