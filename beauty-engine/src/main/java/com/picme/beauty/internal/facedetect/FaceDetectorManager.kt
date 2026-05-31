package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetectionResult
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.InferenceBackendType
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.beauty.internal.facedetect.adapter.InsightFaceAdapter

/**
 * 人脸检测管理器
 *
 * 多引擎调度器（MediaPipe + InsightFace + MNN + NCNN），实现 FaceDetector 公开接口。
 * 所有检测输入均为 Bitmap，ImageProxy → Bitmap 转换由调用方负责。
 *
 * 配置来源：设置页的 StageConfig（ROI + Landmark 独立配置），通过 updatePipelineConfig() 传入。
 */
class FaceDetectorManager(context: Context) : FaceDetector {

    companion object {
        private const val TAG = "PicMe:FaceDetector"

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private val appContext: Context = context.applicationContext

    private var roiDetector: RoiDetector? = null
    private var landmarkDetector: LandmarkDetector? = null

    private var pipelineConfig: DetectionPipelineConfig? = null
    private var isPipelineInitialized = false

    // [按需创建] 各 detector 实例缓存，仅在配置需要时创建
    private var mediaPipeDetector: MediaPipeFaceDetector? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null
    private var mnnRoiDetector: MnnRoiDetector? = null
    private var mnnLandmarkDetector: MnnLandmarkDetector? = null
    private var ncnnRoiDetector: NcnnRoiDetector? = null
    private var ncnnLandmarkDetector: NcnnLandmarkDetector? = null

    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    @Deprecated("设置页为唯一配置来源，使用 updatePipelineConfig() 替代")
    override fun setEngineMode(mode: EngineType) {
        Log.w(TAG, "setEngineMode() is deprecated, use updatePipelineConfig() instead")
    }

    override fun updatePipelineConfig(newConfig: DetectionPipelineConfig) {
        Log.i(TAG, "updatePipelineConfig: roi=${newConfig.roiDetector}/${newConfig.roiEngine}, landmark=${newConfig.landmarkDetector}/${newConfig.landmarkEngine}")
        if (pipelineConfig == newConfig) {
            Log.w(TAG, "Config unchanged, skipping update")
            return
        }

        pipelineConfig = newConfig
        initializePipeline()
    }

    private fun initializePipeline() {
        val config = pipelineConfig ?: return

        roiDetector?.release()
        landmarkDetector?.release()

        val (newRoiDetector, newLandmarkDetector) = DetectionPipelineFactory.createPipeline(
            config, appContext
        )
        roiDetector = newRoiDetector
        landmarkDetector = newLandmarkDetector
        isPipelineInitialized = true

        Log.i(TAG, "Pipeline initialized: ROI=${newRoiDetector.javaClass.simpleName}, Landmark=${newLandmarkDetector.javaClass.simpleName}")
    }

    override fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult? {
        if (!isPipelineInitialized) {
            Log.w(TAG, "Pipeline not initialized, using default config")
            pipelineConfig = DetectionPipelineConfig(
                roiDetector = RoiDetectorType.DET10G,
                landmarkDetector = LandmarkDetectorType.INSIGHTFACE_2D106
            )
            initializePipeline()
        }

        val config = pipelineConfig!!
        val startTime = SystemClock.elapsedRealtime()
        lastDetectionSource = FaceDetectionSource.NONE

        return try {
            when {
                config.roiDetector == RoiDetectorType.MEDIAPIPE &&
                    config.landmarkDetector == LandmarkDetectorType.MEDIAPIPE -> {
                    detectMediaPipe(bitmap, rotationDegrees, lensFacing, startTime)
                }
                else -> {
                    detectPipeline(bitmap, lensFacing, config, startTime)
                }
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Log.e(TAG, "Face detection failed", e)
            null
        }
    }

    private fun getMediaPipeDetector(): MediaPipeFaceDetector {
        return mediaPipeDetector ?: MediaPipeFaceDetector(appContext).also {
            mediaPipeDetector = it
        }
    }

    private fun getInsightFaceDetector(): InsightFace2D106Detector {
        return insightFaceDetector ?: InsightFace2D106Detector(appContext).also {
            insightFaceDetector = it
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

    private fun detectMediaPipe(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int, startTime: Long): FaceDetectionResult? {
        val detector = getMediaPipeDetector()
        if (!detector.isReady()) {
            Log.w(TAG, "MediaPipe detector not ready")
            return null
        }

        val mediaPipeResult = detector.detect(bitmap, rotationDegrees, lensFacing)
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
        var roiResult: android.graphics.RectF? = null
        var landmarkResult: FloatArray? = null

        val roiStartTime = SystemClock.elapsedRealtime()

        // ROI 检测
        roiResult = when (config.roiEngine) {
            InferenceBackendType.MNN -> getMnnRoiDetector().detectRoi(bitmap)
            InferenceBackendType.NCNN -> getNcnnRoiDetector().detectRoi(bitmap)
            else -> roiDetector?.detectRoi(bitmap)
        }

        val roiTime = SystemClock.elapsedRealtime() - roiStartTime

        if (roiResult == null) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "No face detected by ROI (${lastProcessTimeMs}ms)")
            return null
        }

        // Landmark 检测
        val landmarkStart = SystemClock.elapsedRealtime()
        landmarkResult = when (config.landmarkEngine) {
            InferenceBackendType.MNN -> getMnnLandmarkDetector().detectLandmarks(bitmap, lensFacing, roiResult)
            InferenceBackendType.NCNN -> getNcnnLandmarkDetector().detectLandmarks(bitmap, lensFacing, roiResult)
            else -> landmarkDetector?.detectLandmarks(bitmap, lensFacing, roiResult)
        }
        val landmarkTime = SystemClock.elapsedRealtime() - landmarkStart
        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

        Log.d(TAG, "[Perf] Detection breakdown: ROI=${roiTime}ms, Landmark=${landmarkTime}ms, Total=${lastProcessTimeMs}ms")

        return if (landmarkResult != null && landmarkResult.size >= POINT_COUNT * 2) {
            val detectionSource = when (config.landmarkEngine) {
                InferenceBackendType.MNN -> FaceDetectionSource.MNN
                InferenceBackendType.NCNN -> FaceDetectionSource.NCNN
                else -> FaceDetectionSource.INSIGHTFACE
            }
            lastDetectionSource = detectionSource

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(detectionSource)
                ?: return null
            val adaptedResult = adapter.adapt(landmarkResult, lensFacing).getOrNull()
                ?: return null

            val normalizedRoi = android.graphics.RectF(
                roiResult.left / bitmap.width.toFloat(),
                roiResult.top / bitmap.height.toFloat(),
                roiResult.right / bitmap.width.toFloat(),
                roiResult.bottom / bitmap.height.toFloat()
            )

            val useGpu = config.landmarkEngine == InferenceBackendType.MNN ||
                config.landmarkEngine == InferenceBackendType.NCNN

            FaceDetectionResult(
                landmarks106 = adaptedResult,
                detectionSource = detectionSource,
                roiRect = normalizedRoi,
                roiDetectorName = config.roiEngine.name,
                useGpuForRoi = useGpu,
                landmarkDetectorName = config.landmarkEngine.name,
                useGpuForLandmark = useGpu
            )
        } else {
            Log.w(TAG, "Landmark result invalid: size=${landmarkResult?.size}")
            null
        }
    }

    override fun detectPhoto(bitmap: Bitmap, lensFacing: Int): FaceDetectionResult? {
        val config = pipelineConfig ?: DetectionPipelineConfig(
            roiDetector = RoiDetectorType.DET10G,
            landmarkDetector = LandmarkDetectorType.INSIGHTFACE_2D106
        )
        val startTime = SystemClock.elapsedRealtime()

        return when {
            config.landmarkDetector == LandmarkDetectorType.MEDIAPIPE -> {
                val detector = getMediaPipeDetector()
                if (!detector.isReady()) {
                    Log.w(TAG, "MediaPipe detector not ready for photo")
                    return null
                }
                val mediaPipeResult = detector.detectForPhoto(bitmap, lensFacing)
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

    override fun getLastProcessTimeMs(): Long = lastProcessTimeMs

    override fun getLastDetectionSource(): FaceDetectionSource = lastDetectionSource

    fun getPoint(landmarks106: FloatArray, index: Int): PointF {
        require(index in 0 until POINT_COUNT)
        return PointF(landmarks106[index * 2], landmarks106[index * 2 + 1])
    }

    override fun release() {
        roiDetector?.release()
        landmarkDetector?.release()
        roiDetector = null
        landmarkDetector = null

        mediaPipeDetector?.release()
        insightFaceDetector?.release()
        mnnRoiDetector?.release()
        mnnLandmarkDetector?.release()
        ncnnRoiDetector?.release()
        ncnnLandmarkDetector?.release()
        mediaPipeDetector = null
        insightFaceDetector = null
        mnnRoiDetector = null
        mnnLandmarkDetector = null
        ncnnRoiDetector = null
        ncnnLandmarkDetector = null

        isPipelineInitialized = false
        lastDetectionSource = FaceDetectionSource.NONE
        Log.i(TAG, "FaceDetectorManager released")
    }
}
