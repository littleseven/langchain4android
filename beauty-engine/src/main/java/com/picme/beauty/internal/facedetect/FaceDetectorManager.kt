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
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.beauty.internal.facedetect.adapter.InsightFaceAdapter

/**
 * 人脸检测管理器
 *
 * 双引擎调度器（MediaPipe + InsightFace），实现 FaceDetector 公开接口。
 * 所有检测输入均为 Bitmap，ImageProxy → Bitmap 转换由调用方负责。
 */
class FaceDetectorManager(context: Context) : FaceDetector {

    private var detectionEngineMode: EngineType = EngineType.INSIGHTFACE

    companion object {
        private const val TAG = "PicMe:FaceDetector"

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private val appContext: Context = context.applicationContext

    private var roiDetector: RoiDetector? = null
    private var landmarkDetector: LandmarkDetector? = null
    private var pipelineConfig: DetectionPipelineConfig = DetectionPipelineConfig()

    private var mediaPipeDetector: MediaPipeFaceDetector? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null

    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        mediaPipeDetector = MediaPipeFaceDetector(context)
        initializePipeline()
        Log.i(TAG, "FaceDetectorManager initialized")
    }

    private fun initializePipeline() {
        Log.i(TAG, "=== Initializing Detection Pipeline ===")
        Log.i(TAG, "  Config: roi=${pipelineConfig.roiDetector}, landmark=${pipelineConfig.landmarkDetector}")

        roiDetector?.release()
        landmarkDetector?.release()

        roiDetector = DetectionPipelineFactory.createRoiDetector(
            pipelineConfig.roiDetector, appContext
        )
        landmarkDetector = DetectionPipelineFactory.createLandmarkDetector(
            pipelineConfig.landmarkDetector, appContext
        )

        Log.i(TAG, "  ROI Detector: ${roiDetector?.javaClass?.simpleName}")
        Log.i(TAG, "  Landmark Detector: ${landmarkDetector?.javaClass?.simpleName}")
        Log.i(TAG, "  Pipeline initialized successfully")
        Log.i(TAG, "=========================================")
    }

    override fun updatePipelineConfig(newConfig: DetectionPipelineConfig) {
        if (pipelineConfig == newConfig) return

        pipelineConfig = newConfig
        initializePipeline()
    }

    override fun setEngineMode(mode: EngineType) {
        if (detectionEngineMode != mode) {
            detectionEngineMode = mode
            Log.i(TAG, "Detection engine mode switched to: ${mode.name}")
        }
    }

    override fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult? {
        val startTime = SystemClock.elapsedRealtime()
        lastDetectionSource = FaceDetectionSource.NONE

        return try {
            when (detectionEngineMode) {
                EngineType.MEDIAPIPE -> {
                    val detector = mediaPipeDetector
                    if (detector == null || !detector.isReady()) {
                        Log.w(TAG, "MediaPipe detector not ready")
                        return null
                    }

                    val mediaPipeResult = detector.detect(bitmap, rotationDegrees, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

                    if (mediaPipeResult != null) {
                        lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                        Log.d(TAG, "Face detected by MediaPipe in ${lastProcessTimeMs}ms")
                        FaceDetectionResult(mediaPipeResult, lastDetectionSource)
                    } else {
                        Log.d(TAG, "No face detected by MediaPipe (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                EngineType.INSIGHTFACE -> {
                    val roiStartTime = SystemClock.elapsedRealtime()
                    val roi = roiDetector?.detectRoi(bitmap)
                    val roiTime = SystemClock.elapsedRealtime() - roiStartTime

                    val landmarkStart = SystemClock.elapsedRealtime()
                    val rawResult = landmarkDetector?.detectLandmarks(bitmap, lensFacing, roi)
                    val landmarkTime = SystemClock.elapsedRealtime() - landmarkStart

                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

                    Log.d(TAG, "[Perf] InsightFace breakdown: ROI=${roiTime}ms, Landmark=${landmarkTime}ms, Total=${lastProcessTimeMs}ms")

                    if (rawResult != null) {
                        lastDetectionSource = FaceDetectionSource.INSIGHTFACE
                        val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.INSIGHTFACE)
                            as? InsightFaceAdapter
                            ?: return null
                        val adaptedResult = adapter.adapt(rawResult, lensFacing).getOrNull()
                        if (adaptedResult != null) {
                            Log.d(TAG, "InsightFace detection success in ${lastProcessTimeMs}ms")
                            val normalizedRoi = roi?.let { r ->
                                android.graphics.RectF(
                                    r.left / bitmap.width.toFloat(),
                                    r.top / bitmap.height.toFloat(),
                                    r.right / bitmap.width.toFloat(),
                                    r.bottom / bitmap.height.toFloat()
                                )
                            }
                            FaceDetectionResult(adaptedResult, lastDetectionSource, normalizedRoi)
                        } else {
                            Log.w(TAG, "InsightFace adaptation failed")
                            null
                        }
                    } else {
                        Log.d(TAG, "No face detected by InsightFace (${lastProcessTimeMs}ms)")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Log.e(TAG, "Face detection failed in ${detectionEngineMode.name} mode", e)
            null
        }
    }

    override fun detectPhoto(bitmap: Bitmap, lensFacing: Int): FaceDetectionResult? {
        val detector = mediaPipeDetector
        if (detector == null || !detector.isReady()) {
            Log.w(TAG, "MediaPipe detector not ready for photo")
            return null
        }

        val mediaPipeResult = detector.detectForPhoto(bitmap, lensFacing)
        return if (mediaPipeResult != null) {
            FaceDetectionResult(mediaPipeResult, FaceDetectionSource.MEDIAPIPE)
        } else {
            null
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
        mediaPipeDetector = null
        insightFaceDetector?.release()
        insightFaceDetector = null
        lastDetectionSource = FaceDetectionSource.NONE
        Log.i(TAG, "FaceDetectorManager released")
    }
}
