package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import com.picme.agent.core.mnn.MnnGlobalReleaseLock
import com.picme.agent.core.mnn.MnnResourceManager
import com.picme.beauty.api.Logger
import com.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.picme.beauty.internal.model.ModelManager

/**
 * 基于 MNN + Vulkan GPU 的 ROI 检测器
 * 替代 InsightFace Det10G (ONNX Runtime)，提供更快的 GPU 推理
 *
 * 兼容骁龙 765G + Adreno 620（Vulkan 1.1）
 *
 * [Agent First] 支持动态加载/卸载：
 * - 通过 MnnResourceManager 注册引用，参与全局内存协调
 * - 场景切换时自动卸载（如进入聊天页），返回相机页时自动恢复
 * - 内存压力时优先被卸载（模型小，恢复快）
 */
class MnnRoiDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : RoiDetector {

    companion object {
        private const val TAG = "MnnRoi"
        private const val MODEL_KEY = "det_500m_mnn"
        private const val INPUT_SIZE = 320  // [RetinaFace-MobileNet0.25] 320×320 输入，75% 像素减少
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val ROI_EXPAND_RATIO = 1.2f  // [对齐 ONNX] ROI 扩展比例，与 InsightFaceDet10G 一致
        private const val ENGINE_NAME = "MNN-Vulkan"

        // [det_500m] RetinaFace-MobileNet0.25 9 个输出层名称（与 MNNConvert 输出一致）
        // 尺度分组: stride 8/16/32，每个 3 输出 (score/bbox/landmark)
        private val OUTPUT_NAMES = arrayOf(
            "443", "468", "493",   // score: stride 8/16/32
            "446", "471", "496",   // bbox: stride 8/16/32
            "449", "474", "499"    // landmark: stride 8/16/32
        )
    }

    private val appContext = context.applicationContext
    private val resourceManager = MnnResourceManager.getInstance(appContext)
    private var detector: MnnFaceDetector? = null
    private var isInitialized = false
    private var isGpuEnabled: Boolean = false

    // [性能优化] Bitmap 缩放复用池
    private var reusableScaledBitmap: Bitmap? = null

    init {
        // [优化] 不立即初始化，改为懒加载
        Logger.d(TAG, "MnnRoiDetector created (lazy initialization, requireGpu=$requireGpu)")

        // 注册资源管理监听器，响应全局卸载/加载事件
        resourceManager.registerFaceDetectionUnloadListener(::onResourceManagerUnload)
        resourceManager.registerFaceDetectionLoadListener(::onResourceManagerLoad)

        // [P0-4] 注册分级释放回调，供 releaseAtLevel("face", level) 使用
        resourceManager.registerFaceReleaseCallback(MnnResourceManager.ReleaseLevel.SESSION) {
            MnnGlobalReleaseLock.withLock {
                detector?.releaseSession()
            }
        }
        resourceManager.registerFaceReleaseCallback(MnnResourceManager.ReleaseLevel.FULL) {
            performUnload()
        }
    }

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
                // 向 ResourceManager 注册引用，参与全局协调
                resourceManager.acquireFaceDetection("MnnRoiDetector")
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

    /**
     * ResourceManager 触发的卸载回调
     * 释放 native 模型资源，但保留 Kotlin 对象以便快速恢复
     */
    private fun onResourceManagerUnload() {
        synchronized(this) {
            if (!isInitialized) return
            Logger.i(TAG, "Unloading due to ResourceManager request")
            performUnload()
        }
    }

    /**
     * ResourceManager 触发的加载回调
     * 重新初始化模型（如果当前未加载）
     */
    private fun onResourceManagerLoad() {
        synchronized(this) {
            if (isInitialized) return
            Logger.i(TAG, "Loading due to ResourceManager request")
            initialize()
            isInitialized = true
        }
    }

    /**
     * 执行实际卸载（线程安全）
     *
     * 使用 MnnGlobalReleaseLock 串行化 MNN native 释放，
     * 防止与 ASR/LLM 的 MNN 释放并发导致崩溃。
     */
    private fun performUnload() {
        MnnGlobalReleaseLock.withLock {
            detector?.release()
        }
        detector = null
        isInitialized = false
        isGpuEnabled = false
        Logger.d(TAG, "Native resources unloaded")
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

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detectRetinaFace(scaledBitmap, CONFIDENCE_THRESHOLD, 0.4f)

            if (result == null || result.size < 5) {
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
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val matrix = Matrix()
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
        // 注销监听器，避免已释放对象收到回调
        resourceManager.unregisterFaceDetectionUnloadListener(::onResourceManagerUnload)
        resourceManager.unregisterFaceDetectionLoadListener(::onResourceManagerLoad)

        // 释放引用（触发 ResourceManager 协调）
        resourceManager.releaseFaceDetection(
            owner = "MnnRoiDetector",
            onSafeUnload = ::performUnload,
            onSoftRelease = ::performUnload
        )

        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Logger.i(TAG, "MnnRoiDetector released")
    }

}
