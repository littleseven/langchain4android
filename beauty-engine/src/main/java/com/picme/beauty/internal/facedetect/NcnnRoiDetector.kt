package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.picme.beauty.internal.facedetect.ncnn.NcnnFaceDetector
import java.io.File

/**
 * 基于 NCNN 的 ROI 检测器
 * 替代 InsightFace Det10G (ONNX Runtime)，提供轻量级 GPU 推理
 */
class NcnnRoiDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : RoiDetector {

    companion object {
        private const val TAG = "PicMe:NcnnRoi"
        private const val MODEL_PARAM_ASSET_PATH = "insightface/det_10g.param"
        private const val MODEL_BIN_ASSET_PATH = "insightface/det_10g.bin"
        private const val MODEL_PARAM_FILE_NAME = "ncnn_det_10g.param"
        private const val MODEL_BIN_FILE_NAME = "ncnn_det_10g.bin"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val ROI_EXPAND_RATIO = 1.2f

        // RetinaFace 9 个输出 blob 名称
        private val OUTPUT_NAMES = arrayOf(
            "448", "471", "494",
            "451", "474", "497",
            "454", "477", "500"
        )
    }

    private val appContext = context.applicationContext
    private var detector: NcnnFaceDetector? = null
    private var isInitialized = false

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
        Log.d(TAG, "NcnnRoiDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        try {
            val paramFile = ensureModelFile(MODEL_PARAM_FILE_NAME, MODEL_PARAM_ASSET_PATH)
            val binFile = ensureModelFile(MODEL_BIN_FILE_NAME, MODEL_BIN_ASSET_PATH)

            Log.i(TAG, "Initializing NCNN RetinaFace detector (requireGpu=$requireGpu)...")
            Log.d(TAG, "Model files: param=${paramFile.absolutePath} (${paramFile.length()} bytes), bin=${binFile.absolutePath} (${binFile.length()} bytes)")

            val initStart = SystemClock.elapsedRealtime()
            detector = NcnnFaceDetector.create(
                paramPath = paramFile.absolutePath,
                binPath = binFile.absolutePath,
                inputSize = INPUT_SIZE,
                useGpu = requireGpu,
                inputName = "input.1",
                outputNames = OUTPUT_NAMES
            )
            val initElapsed = SystemClock.elapsedRealtime() - initStart

            if (detector != null) {
                Log.i(TAG, "NcnnRoiDetector initialized in ${initElapsed}ms")
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
                    inputName = "input.1",
                    outputNames = OUTPUT_NAMES
                )
                if (detector != null) {
                    Log.i(TAG, "NcnnRoiDetector initialized with CPU fallback in ${SystemClock.elapsedRealtime() - initStart}ms")
                } else {
                    Log.e(TAG, "NCNN CPU fallback also failed, detector will remain null")
                }
            } else {
                Log.e(TAG, "NCNN CPU initialization failed, detector will remain null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NcnnRoiDetector (requireGpu=$requireGpu)", e)
            detector = null
        }
    }

    override fun detectRoi(bitmap: Bitmap): RectF? {
        ensureInitialized()

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Log.w(TAG, "[Perf] NcnnRoiDetector not initialized after lazy init, skipping")
            return null
        }

        return try {
            val scaleStart = SystemClock.elapsedRealtime()
            val scaledBitmap = getScaledBitmap(bitmap, INPUT_SIZE)
            val scaleElapsed = SystemClock.elapsedRealtime() - scaleStart

            Log.d(TAG, "[Perf] NcnnRoi START: original=${bitmap.width}x${bitmap.height}, scaled=${scaledBitmap.width}x${scaledBitmap.height}")

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detectRetinaFace(scaledBitmap, CONFIDENCE_THRESHOLD, 0.4f)
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            if (result == null || result.size < 5) {
                Log.d(TAG, "[Perf] NcnnRoi DONE: total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms), no face")
                return null
            }

            val origW = bitmap.width.toFloat()
            val origH = bitmap.height.toFloat()
            val scale = INPUT_SIZE.toFloat() / maxOf(origW, origH)
            val scaledW = (origW * scale).toInt()
            val scaledH = (origH * scale).toInt()
            val padLeft = (INPUT_SIZE - scaledW) / 2f
            val padTop = (INPUT_SIZE - scaledH) / 2f

            Log.d(TAG, "[Diag] Letterbox params: scale=$scale, scaledSize=${scaledW}x${scaledH}, pad=($padLeft,$padTop)")
            Log.d(TAG, "[Diag] Raw NCNN output: (${result[0]}, ${result[1]}, ${result[2]}, ${result[3]}), score=${result[4]}")

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

            Log.d(TAG, "[Diag] ROI coords: (${roi.left.toInt()},${roi.top.toInt()},${roi.right.toInt()},${roi.bottom.toInt()}), size=${(roi.right-roi.left).toInt()}x${(roi.bottom-roi.top).toInt()}")
            Log.i(TAG, "[Perf] NcnnRoi DONE: total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms)")
            roi
        } catch (e: Exception) {
            Log.e(TAG, "NcnnRoi detection failed", e)
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
        Log.i(TAG, "NcnnRoiDetector released")
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
