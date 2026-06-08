package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.Image
import android.os.SystemClock
import com.picme.agent.core.platform.mnn.MnnResourceManager
import com.picme.beauty.api.Logger
import com.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetectionResult
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.InferenceBackendType
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import java.nio.ByteBuffer

/**
 * 人脸检测管理器
 *
 * 多引擎调度器（MediaPipe + InsightFace + MNN + NCNN），实现 FaceDetector 公开接口。
 * 所有检测输入均为 Bitmap，ImageProxy → Bitmap 转换由调用方负责。
 *
 * 配置来源：设置页的 StageConfig（ROI + Landmark 独立配置），通过 updatePipelineConfig() 传入。
 *
 * [Agent First] 场景感知内存管理：
 * - 相机页自动通知 ResourceManager 保留人脸检测模型
 * - 离开相机页自动触发模型卸载，释放 MNN 内存
 * - 内存压力时人脸检测优先被卸载（模型小、恢复快）
 */
class FaceDetectorManager(context: Context) : FaceDetector {

    companion object {
        private const val TAG = "FaceDetector"

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private val appContext: Context = context.applicationContext
    private val resourceManager = MnnResourceManager.getInstance(appContext)

    // [线程安全] 所有状态变量通过 lock 保护，防止预览/拍照/配置更新竞态
    private val lock = Any()

    private var roiDetector: RoiDetector? = null
    private var landmarkDetector: LandmarkDetector? = null

    private var pipelineConfig: DetectionPipelineConfig? = null
    private var isPipelineInitialized = false

    // [按需创建] 各 detector 实例缓存，仅在配置需要时创建
    private var mediaPipeDetector: MediaPipeFaceDetector? = null
    private var mnnRoiDetector: MnnRoiDetector? = null
    private var mnnLandmarkDetector: MnnLandmarkDetector? = null
    private var ncnnRoiDetector: NcnnRoiDetector? = null
    private var ncnnLandmarkDetector: NcnnLandmarkDetector? = null

    @Volatile
    private var lastProcessTimeMs: Long = 0
    @Volatile
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    @Deprecated("设置页为唯一配置来源，使用 updatePipelineConfig() 替代")
    override fun setEngineMode(mode: EngineType) {
        Logger.w(TAG, "setEngineMode() is deprecated, use updatePipelineConfig() instead")
    }

    override fun updatePipelineConfig(newConfig: DetectionPipelineConfig) {
        Logger.i(TAG, "updatePipelineConfig: roi=${newConfig.roiDetector}/${newConfig.roiEngine}, landmark=${newConfig.landmarkDetector}/${newConfig.landmarkEngine}")

        synchronized(lock) {
            val current = pipelineConfig
            if (current != null &&
                current.roiDetector == newConfig.roiDetector &&
                current.landmarkDetector == newConfig.landmarkDetector &&
                current.roiEngine == newConfig.roiEngine &&
                current.landmarkEngine == newConfig.landmarkEngine &&
                current.roiDevice == newConfig.roiDevice &&
                current.landmarkDevice == newConfig.landmarkDevice &&
                current.useLooseCrop == newConfig.useLooseCrop
            ) {
                Logger.w(TAG, "Config unchanged, skipping update")
                return
            }

            pipelineConfig = newConfig
            initializePipelineLocked()
        }
    }

    private fun initializePipelineLocked() {
        val config = pipelineConfig ?: return

        roiDetector?.release()
        landmarkDetector?.release()

        val (newRoiDetector, newLandmarkDetector) = DetectionPipelineFactory.createPipeline(
            config, appContext
        )
        roiDetector = newRoiDetector
        landmarkDetector = newLandmarkDetector
        isPipelineInitialized = true

        Logger.i(TAG, "Pipeline initialized: ROI=${newRoiDetector.javaClass.simpleName}, Landmark=${newLandmarkDetector.javaClass.simpleName}")
    }

    override fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult? {
        // [核心原则] 未收到设置页配置时不做任何预创建/默认初始化
        // 设置页是配置的唯一来源，通过 updatePipelineConfig() 下发
        if (!isPipelineInitialized) {
            Logger.w(TAG, "Pipeline not initialized, no config from settings yet. Skipping detection.")
            return null
        }

        val startTime = SystemClock.elapsedRealtime()
        lastDetectionSource = FaceDetectionSource.NONE

        return try {
            synchronized(lock) {
                val config = pipelineConfig ?: return null
                when {
                    config.roiDetector == RoiDetectorType.MEDIAPIPE &&
                        config.landmarkDetector == LandmarkDetectorType.MEDIAPIPE -> {
                        detectMediaPipeUnified(bitmap, rotationDegrees, lensFacing, startTime)
                    }
                    else -> {
                        detectPipeline(bitmap, lensFacing, config, startTime)
                    }
                }
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Face detection failed", e)
            null
        } catch (e: Error) {
            // [NCNN 保护] 捕获 native 崩溃（如 OpenMP 线程亲和性错误）
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Face detection native error (NCNN/OpenMP?)", e)
            null
        }
    }

    /**
     * [Zero-Copy] 人脸 ROI 检测——直接从 YUV NV21 输入
     *
     * 支持 MNN 和 NCNN ROI 检测器的 NV21 直传路径。
     * NV21 DirectByteBuffer 直传 C++ 层，在 native 端完成
     * NV21→RGB + resize + letterbox + 归一化的一体化预处理。
     *
     * @param nv21Data 紧凑 NV21 DirectByteBuffer
     * @param width 原始图像宽度
     * @param height 原始图像高度
     * @return ROI 矩形（原图坐标），或 null
     */
     fun detectRoiFromNv21(nv21Data: ByteBuffer, width: Int, height: Int): RectF? {
        if (!isPipelineInitialized) {
            Logger.w(TAG, "Pipeline not initialized, skipping NV21 detection")
            return null
        }

        val config = pipelineConfig ?: return null
        val roiStart = SystemClock.elapsedRealtime()

        return try {
            val result: RectF? = when (config.roiEngine) {
                InferenceBackendType.MNN -> {
                    val mnnRoi = roiDetector as? MnnRoiDetector
                    mnnRoi?.detectRoiFromYuv(nv21Data, width, height)
                }
                InferenceBackendType.NCNN -> {
                    val ncnnRoi = roiDetector as? NcnnRoiDetector
                    ncnnRoi?.detectRoiFromYuv(nv21Data, width, height)
                }
                else -> {
                    Logger.d(TAG, "NV21 path not available for ${config.roiEngine}")
                    null
                }
            }

            val roiTime = SystemClock.elapsedRealtime() - roiStart
            if (result != null) {
                Logger.d(TAG, "[Perf] NV21 ROI(${config.roiEngine}) detected: ${roiTime}ms, rect=$result")
            } else {
                Logger.d(TAG, "[Perf] NV21 ROI(${config.roiEngine}) no face: ${roiTime}ms")
            }
            result
        } catch (e: Exception) {
            val roiTime = SystemClock.elapsedRealtime() - roiStart
            Logger.e(TAG, "[Perf] NV21 ROI failed: ${roiTime}ms", e)
            null
        }
    }

    /**
     * 仅执行 landmark 检测（使用预计算的 ROI）
     *
     * 配合 detectRoiFromNv21() 使用：ROI 由 NV21 零拷贝路径得到，
     * landmark 检测基于 Bitmap 完成（含 ROI 裁剪）。
     *
     * @return FaceDetectionResult（含 106 landmarks），或 null
     */
     fun detectLandmarksWithRoi(bitmap: Bitmap, lensFacing: Int, roi: RectF): FaceDetectionResult? {
        if (!isPipelineInitialized) {
            Logger.w(TAG, "Pipeline not initialized")
            return null
        }

        val startTime = SystemClock.elapsedRealtime()
        val config = pipelineConfig ?: return null

        return try {
            synchronized(lock) {
                val lmStart = SystemClock.elapsedRealtime()
                val landmarkResult = landmarkDetector?.detectLandmarks(bitmap, lensFacing, roi)
                val lmTime = SystemClock.elapsedRealtime() - lmStart
                lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

                if (landmarkResult == null || landmarkResult.size < POINT_COUNT * 2) {
                    Logger.d(TAG, "[Perf] NV21 path: Landmark failed (${lmTime}ms, total=${lastProcessTimeMs}ms)")
                    return null
                }

                val detectionSource = when (config.landmarkEngine) {
                    InferenceBackendType.MNN -> FaceDetectionSource.MNN
                    InferenceBackendType.NCNN -> FaceDetectionSource.NCNN
                    else -> FaceDetectionSource.MEDIAPIPE
                }
                lastDetectionSource = detectionSource

                val adapter = FaceLandmarkAdapterRegistry.getAdapter(detectionSource)
                    ?: return null
                val adaptedResult = adapter.adapt(landmarkResult, lensFacing).getOrNull()
                    ?: return null

                val normalizedRoi = RectF(
                    roi.left / bitmap.width.toFloat(),
                    roi.top / bitmap.height.toFloat(),
                    roi.right / bitmap.width.toFloat(),
                    roi.bottom / bitmap.height.toFloat()
                )

                val useGpuForLandmark = config.landmarkEngine == InferenceBackendType.MNN ||
                    config.landmarkEngine == InferenceBackendType.NCNN

                val roiEngineLabel = "${config.roiDetector.name}/${config.roiEngine.name}(NV21)"
                val lmEngineLabel = "${config.landmarkDetector.name}/${config.landmarkEngine.name}"
                Logger.d(TAG, "[Perf] NV21 path breakdown: ROI($roiEngineLabel) → Landmark($lmEngineLabel)=${lmTime}ms, Total=${lastProcessTimeMs}ms")

                FaceDetectionResult(
                    landmarks106 = adaptedResult,
                    detectionSource = detectionSource,
                    roiRect = normalizedRoi,
                    roiDetectorName = "${config.roiEngine.name}(NV21)",
                    useGpuForRoi = config.roiEngine == InferenceBackendType.MNN,
                    landmarkDetectorName = config.landmarkEngine.name,
                    useGpuForLandmark = useGpuForLandmark
                )
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Landmark detection with precomputed ROI failed", e)
            null
        }
    }

    /**
     * [Zero-Copy] 关键点检测——YUV NV21 + ROI 裁剪
     *
     * 配合 detectRoiFromNv21() 使用：ROI 由 NV21 零拷贝路径得到后，
     * Landmark 检测也直接基于 NV21 完成，跳过 Bitmap 创建步骤。
     *
     * 目前仅支持 MNN landmark 引擎。
     *
     * @param nv21Data 紧凑 NV21 DirectByteBuffer
     * @param nv21Width NV21 图像宽度
     * @param nv21Height NV21 图像高度
     * @param roi ROI 矩形（NV21 像素坐标）
     * @param lensFacing 镜头方向
     * @return FaceDetectionResult（含 106 landmarks），或 null
     */
    fun detectLandmarksFromNv21WithRoi(
        nv21Data: ByteBuffer,
        nv21Width: Int,
        nv21Height: Int,
        roi: RectF,
        lensFacing: Int
    ): FaceDetectionResult? {
        if (!isPipelineInitialized) {
            Logger.w(TAG, "Pipeline not initialized")
            return null
        }

        val startTime = SystemClock.elapsedRealtime()
        val config = pipelineConfig ?: return null

        val mnnLandmarkDetector = landmarkDetector as? MnnLandmarkDetector
            ?: run {
                Logger.d(TAG, "[Perf] NV21 Landmark: MNN not available (current=${config.landmarkEngine})")
                return null
            }

        val roiLeft = maxOf(0, roi.left.toInt())
        val roiTop = maxOf(0, roi.top.toInt())
        val roiRight = minOf(nv21Width, roi.right.toInt())
        val roiBottom = minOf(nv21Height, roi.bottom.toInt())

        if (roiRight <= roiLeft || roiBottom <= roiTop) {
            Logger.w(TAG, "[Perf] NV21 Landmark: invalid ROI")
            return null
        }

        return try {
            val lmStart = SystemClock.elapsedRealtime()
            val landmarkResult = mnnLandmarkDetector.detectLandmarksFromNv21(
                nv21Data, nv21Width, nv21Height,
                roiLeft, roiTop, roiRight, roiBottom
            )
            val lmTime = SystemClock.elapsedRealtime() - lmStart
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

            if (landmarkResult == null || landmarkResult.size < POINT_COUNT * 2) {
                Logger.d(TAG, "[Perf] NV21 Landmark: failed (${lmTime}ms, total=${lastProcessTimeMs}ms)")
                return null
            }

            val detectionSource = FaceDetectionSource.MNN
            lastDetectionSource = detectionSource

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(detectionSource)
                ?: return null
            val adaptedResult = adapter.adapt(landmarkResult, lensFacing).getOrNull()
                ?: return null

            val normalizedRoi = RectF(
                roiLeft / nv21Width.toFloat(),
                roiTop / nv21Height.toFloat(),
                roiRight / nv21Width.toFloat(),
                roiBottom / nv21Height.toFloat()
            )

            val roiEngineLabel = "${config.roiEngine.name}(NV21)"
            val lmEngineLabel = "${config.landmarkEngine.name}(NV21→GPU)"
            Logger.d(TAG, "[Perf] NV21 path breakdown (zero-copy): ROI($roiEngineLabel) → Landmark($lmEngineLabel)=${lmTime}ms, Total=${lastProcessTimeMs}ms")

            FaceDetectionResult(
                landmarks106 = adaptedResult,
                detectionSource = detectionSource,
                roiRect = normalizedRoi,
                roiDetectorName = "${config.roiEngine.name}(NV21)",
                useGpuForRoi = config.roiEngine == InferenceBackendType.MNN,
                landmarkDetectorName = "${config.landmarkEngine.name}(NV21-GPU)",
                useGpuForLandmark = true
            )
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Landmark NV21+ROI detection failed", e)
            null
        }
    }

    private fun getMediaPipeDetector(): MediaPipeFaceDetector {
        return mediaPipeDetector ?: MediaPipeFaceDetector(appContext).also {
            mediaPipeDetector = it
        }
    }

    private fun getMnnRoiDetector(): MnnRoiDetector {
        return mnnRoiDetector ?: MnnRoiDetector(appContext, requireGpu = true).also {
            mnnRoiDetector = it
        }
    }

    private fun getMnnLandmarkDetector(): MnnLandmarkDetector {
        return mnnLandmarkDetector ?: MnnLandmarkDetector(appContext, requireGpu = true).also {
            mnnLandmarkDetector = it
        }
    }

    private fun getNcnnRoiDetector(): NcnnRoiDetector {
        return ncnnRoiDetector ?: NcnnRoiDetector(appContext, requireGpu = true).also {
            ncnnRoiDetector = it
        }
    }

    private fun getNcnnLandmarkDetector(): NcnnLandmarkDetector {
        return ncnnLandmarkDetector ?: NcnnLandmarkDetector(appContext, requireGpu = true).also {
            ncnnLandmarkDetector = it
        }
    }

    /**
     * MediaPipe 统一检测路径
     * 使用 pipeline 中已创建的 MediaPipeLandmarkDetector，避免重复创建 MediaPipeFaceDetector
     */
    private fun detectMediaPipeUnified(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int, startTime: Long): FaceDetectionResult? {
        val detector = landmarkDetector as? MediaPipeLandmarkDetector
        if (detector == null) {
            Logger.w(TAG, "MediaPipe landmark detector not available in pipeline")
            return null
        }

        val mediaPipeResult = detector.detectLandmarks(bitmap, lensFacing, null)
        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

        return if (mediaPipeResult != null) {
            lastDetectionSource = FaceDetectionSource.MEDIAPIPE
            Logger.d(TAG, "[Perf] MediaPipe unified detection: ${lastProcessTimeMs}ms, ${mediaPipeResult.size / 2}pts")
            FaceDetectionResult(
                landmarks106 = mediaPipeResult,
                detectionSource = FaceDetectionSource.MEDIAPIPE,
                roiRect = null,
                roiDetectorName = "N/A",
                useGpuForRoi = false,
                landmarkDetectorName = "MediaPipe",
                useGpuForLandmark = false
            )
        } else {
            null
        }
    }

    private fun detectPipeline(bitmap: Bitmap, lensFacing: Int, config: DetectionPipelineConfig, startTime: Long): FaceDetectionResult? {
        var roiResult: RectF? = null
        var landmarkResult: FloatArray? = null

        val roiStartTime = SystemClock.elapsedRealtime()

        // ROI 检测：直接使用 initializePipeline() 创建的 roiDetector
        // 避免使用缓存的 getMnnRoiDetector() 等旧实例
        roiResult = roiDetector?.detectRoi(bitmap)

        val roiTime = SystemClock.elapsedRealtime() - roiStartTime

        if (roiResult == null) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.d(TAG, "[Perf] No face detected by ROI (${roiTime}ms, total=${lastProcessTimeMs}ms)")
            return null
        }

        // Landmark 检测：直接使用 initializePipeline() 创建的 landmarkDetector
        val landmarkStart = SystemClock.elapsedRealtime()
        landmarkResult = landmarkDetector?.detectLandmarks(bitmap, lensFacing, roiResult)
        val landmarkTime = SystemClock.elapsedRealtime() - landmarkStart
        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

        val roiEngineLabel = "${config.roiDetector.name}/${config.roiEngine.name}"
        val lmEngineLabel = "${config.landmarkDetector.name}/${config.landmarkEngine.name}"
        Logger.d(TAG, "[Perf] Detection breakdown: ROI($roiEngineLabel)=${roiTime}ms, Landmark($lmEngineLabel)=${landmarkTime}ms, Total=${lastProcessTimeMs}ms")

        return if (landmarkResult != null && landmarkResult.size >= POINT_COUNT * 2) {
            val detectionSource = when (config.landmarkEngine) {
                InferenceBackendType.MNN -> FaceDetectionSource.MNN
                InferenceBackendType.NCNN -> FaceDetectionSource.NCNN
                else -> FaceDetectionSource.MEDIAPIPE
            }
            lastDetectionSource = detectionSource

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(detectionSource)
                ?: return null
            val adaptedResult = adapter.adapt(landmarkResult, lensFacing).getOrNull()
                ?: return null

            val normalizedRoi = RectF(
                roiResult.left / bitmap.width.toFloat(),
                roiResult.top / bitmap.height.toFloat(),
                roiResult.right / bitmap.width.toFloat(),
                roiResult.bottom / bitmap.height.toFloat()
            )

            val useGpuForRoi = config.roiEngine == InferenceBackendType.MNN ||
                config.roiEngine == InferenceBackendType.NCNN
            val useGpuForLandmark = config.landmarkEngine == InferenceBackendType.MNN ||
                config.landmarkEngine == InferenceBackendType.NCNN

            FaceDetectionResult(
                landmarks106 = adaptedResult,
                detectionSource = detectionSource,
                roiRect = normalizedRoi,
                roiDetectorName = config.roiEngine.name,
                useGpuForRoi = useGpuForRoi,
                landmarkDetectorName = config.landmarkEngine.name,
                useGpuForLandmark = useGpuForLandmark
            )
        } else {
            Logger.w(TAG, "Landmark result invalid: size=${landmarkResult?.size}")
            null
        }
    }

    override fun detectPhoto(bitmap: Bitmap, lensFacing: Int): FaceDetectionResult? {
        // [核心原则] 未收到设置页配置时不做任何预创建/默认初始化
        if (!isPipelineInitialized) {
            Logger.w(TAG, "Photo detection skipped: no config from settings yet")
            return null
        }

        val startTime = SystemClock.elapsedRealtime()

        return try {
            synchronized(lock) {
                val config = pipelineConfig ?: return null
                when {
                    config.landmarkDetector == LandmarkDetectorType.MEDIAPIPE -> {
                        val detector = landmarkDetector as? MediaPipeLandmarkDetector
                        if (detector == null) {
                            Logger.w(TAG, "MediaPipe landmark detector not available in pipeline for photo")
                            return null
                        }
                        val mediaPipeResult = detector.detectLandmarks(bitmap, lensFacing, null)
                        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                        if (mediaPipeResult != null) {
                            lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                            FaceDetectionResult(mediaPipeResult, FaceDetectionSource.MEDIAPIPE)
                        } else {
                            null
                        }
                    }
                    else -> {
                        detectPipeline(bitmap, lensFacing, config, startTime)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Photo detection failed", e)
            null
        } catch (e: Error) {
            // [NCNN 保护] 捕获 native 崩溃
            Logger.e(TAG, "Photo detection native error (NCNN/OpenMP?)", e)
            null
        }
    }

    /**
     * 预览路径检测（Image 零拷贝输入）
     *
     * 仅 MediaPipe 统一路径支持：使用 [MediaImageBuilder] 直接包装
     * CameraX ImageProxy.image，跳过 YUV→ARGB CPU 转换（~5ms）。
     *
     * @return FaceDetectionResult？或 null（若非 MediaPipe 路径）
     */
    fun detectFromImage(mediaImage: Image, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult? {
        if (!isPipelineInitialized) {
            Logger.w(TAG, "Pipeline not initialized, skipping Image detection")
            return null
        }

        val detector = landmarkDetector as? MediaPipeLandmarkDetector
        if (detector == null) {
            Logger.w(TAG, "MediaPipe landmark detector not available for Image path")
            return null
        }

        val startTime = SystemClock.elapsedRealtime()

        return try {
            val mediaPipeResult = detector.detectLandmarks(mediaImage, lensFacing, rotationDegrees, null)
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

            if (mediaPipeResult != null) {
                lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                Logger.d(TAG, "[Perf] MediaPipe Image (zero-copy): ${lastProcessTimeMs}ms, ${mediaPipeResult.size / 2}pts")
                FaceDetectionResult(
                    landmarks106 = mediaPipeResult,
                    detectionSource = FaceDetectionSource.MEDIAPIPE,
                    roiRect = null,
                    roiDetectorName = "N/A",
                    useGpuForRoi = false,
                    landmarkDetectorName = "MediaPipe(Image)",
                    useGpuForLandmark = false
                )
            } else {
                null
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "MediaPipe Image detection failed", e)
            null
        }
    }

    fun isMediaPipePipeline(): Boolean {
        val config = pipelineConfig ?: return false
        return config.roiDetector == RoiDetectorType.MEDIAPIPE &&
            config.landmarkDetector == LandmarkDetectorType.MEDIAPIPE
    }

    override fun getLastProcessTimeMs(): Long = lastProcessTimeMs

    override fun getLastDetectionSource(): FaceDetectionSource = lastDetectionSource

    fun getPoint(landmarks106: FloatArray, index: Int): PointF {
        require(index in 0 until POINT_COUNT)
        return PointF(landmarks106[index * 2], landmarks106[index * 2 + 1])
    }

    /**
     * 通知进入相机场景
     * 触发 ResourceManager 保留人脸检测模型
     */
    fun onEnterCameraScene() {
        resourceManager.setScene(MnnResourceManager.Scene.CAMERA)
        Logger.d(TAG, "Entered camera scene")
    }

    /**
     * 通知离开相机场景
     * 触发 ResourceManager 释放人脸检测模型
     */
    fun onLeaveCameraScene() {
        resourceManager.setScene(MnnResourceManager.Scene.OTHER)
        Logger.d(TAG, "Left camera scene")
    }

    override fun release() {
        synchronized(lock) {
            roiDetector?.release()
            landmarkDetector?.release()
            roiDetector = null
            landmarkDetector = null

            mediaPipeDetector?.release()
            mnnRoiDetector?.release()
            mnnLandmarkDetector?.release()
            ncnnRoiDetector?.release()
            ncnnLandmarkDetector?.release()
            mediaPipeDetector = null
            mnnRoiDetector = null
            mnnLandmarkDetector = null
            ncnnRoiDetector = null
            ncnnLandmarkDetector = null

            isPipelineInitialized = false
            lastDetectionSource = FaceDetectionSource.NONE
        }
        Logger.i(TAG, "FaceDetectorManager released")
    }
}