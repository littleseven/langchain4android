package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import com.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetectionResult
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.InferenceBackendType
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.agent.core.mnn.MnnResourceManager
import com.picme.beauty.api.Logger
import android.graphics.RectF

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
            Logger.d(TAG, "No face detected by ROI (${lastProcessTimeMs}ms)")
            return null
        }

        // Landmark 检测：直接使用 initializePipeline() 创建的 landmarkDetector
        val landmarkStart = SystemClock.elapsedRealtime()
        landmarkResult = landmarkDetector?.detectLandmarks(bitmap, lensFacing, roiResult)
        val landmarkTime = SystemClock.elapsedRealtime() - landmarkStart
        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

        Logger.d(TAG, "[Perf] Detection breakdown: ROI=${roiTime}ms, Landmark=${landmarkTime}ms, Total=${lastProcessTimeMs}ms")

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