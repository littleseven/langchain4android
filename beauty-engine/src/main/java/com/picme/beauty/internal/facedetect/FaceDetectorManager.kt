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
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType
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
    
    // [关键修复] 使用懒加载，首次 detect 时才从 DataStore 读取配置
    private var pipelineConfig: DetectionPipelineConfig? = null
    private var isPipelineInitialized = false

    private var mediaPipeDetector: MediaPipeFaceDetector? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null
    
    // [性能优化] MNN GPU 检测器
    private var mnnRoiDetector: MnnRoiDetector? = null
    private var mnnLandmarkDetector: MnnLandmarkDetector? = null

    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        mediaPipeDetector = MediaPipeFaceDetector(context)
        // [关键修复] 不在 init 时初始化 pipeline，改为懒加载
        Log.i(TAG, "FaceDetectorManager initialized (lazy pipeline)")
    }

    private fun initializePipeline() {
        Log.i(TAG, "=== Initializing Detection Pipeline ===")
        val config = pipelineConfig!!
        Log.i(TAG, "  ROI: ${config.roiDetector.displayName} + ${config.roiEngine.displayName} + ${config.roiDevice.displayName}")
        Log.i(TAG, "  Landmark: ${config.landmarkDetector.displayName} + ${config.landmarkEngine.displayName} + ${config.landmarkDevice.displayName}")

        // [优化] 懒加载模式下，release() 不会立即销毁资源，只是标记为未初始化
        roiDetector?.release()
        landmarkDetector?.release()

        val (newRoiDetector, newLandmarkDetector) = DetectionPipelineFactory.createPipeline(
            config, appContext
        )
        roiDetector = newRoiDetector
        landmarkDetector = newLandmarkDetector

        Log.i(TAG, "  ROI Detector: ${roiDetector?.javaClass?.simpleName}")
        Log.i(TAG, "  Landmark Detector: ${landmarkDetector?.javaClass?.simpleName}")
        Log.i(TAG, "  Pipeline initialized successfully")
        Log.i(TAG, "=========================================")
        
        isPipelineInitialized = true
    }
    
    // [关键修复] 从 DataStore 读取配置并初始化 pipeline
    private fun loadAndInitializePipeline() {
        if (isPipelineInitialized) return
        
        Log.i(TAG, "Loading pipeline config from DataStore...")
        
        // [临时方案] 使用默认值，由 CameraRuntimeState 的 LaunchedEffect 调用 updatePipelineConfig 来设置
        pipelineConfig = DetectionPipelineConfig(
            roiDetector = RoiDetectorType.DET10G,
            landmarkDetector = LandmarkDetectorType.INSIGHTFACE_2D106
        )
        
        Log.i(TAG, "DataStore values - ROI: DET10G, Landmark: INSIGHTFACE_2D106 (default)")
        
        initializePipeline()
    }

    override fun updatePipelineConfig(newConfig: DetectionPipelineConfig) {
        Log.i(TAG, "updatePipelineConfig called: roi=${newConfig.roiDetector}, landmark=${newConfig.landmarkDetector}")
        if (pipelineConfig == newConfig) {
            Log.w(TAG, "Config unchanged, skipping update")
            return
        }

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
        // [关键修复] 首次 detect 时从 DataStore 读取配置并初始化 pipeline
        if (!isPipelineInitialized) {
            loadAndInitializePipeline()
        }
        
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
                        // MediaPipe 使用 CPU，无 ROI 概念
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
                        Log.d(TAG, "No face detected by MediaPipe (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                EngineType.INSIGHTFACE -> {
                    detectInsightFace(bitmap, lensFacing, startTime)
                }

                EngineType.MNN -> {
                    detectMnn(bitmap, lensFacing, startTime)
                }
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Log.e(TAG, "Face detection failed in ${detectionEngineMode.name} mode", e)
            null
        }
    }

    /**
     * [GPU 检测优化 Phase 2] MediaPipe 直接 YUV Image 检测
     *
     * 跳过 CPU 端 Bitmap 生成，MediaPipe 内部直接从 YUV_420_888 做 GPU 推理。
     *
     * @param mediaImage CameraX ImageProxy.image
     * @param rotationDegrees 图像旋转角度，用于结果坐标旋转补偿
     * @param lensFacing 镜头方向
     * @return 检测结果，无人脸返回 null
     */
    override fun detect(mediaImage: android.media.Image, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult? {
        val startTime = SystemClock.elapsedRealtime()
        lastDetectionSource = FaceDetectionSource.NONE

        val detector = mediaPipeDetector
        if (detector == null || !detector.isReady()) {
            Log.w(TAG, "MediaPipe detector not ready for direct image")
            return null
        }

        return try {
            val mediaPipeResult = detector.detect(mediaImage, rotationDegrees, lensFacing)
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime

            if (mediaPipeResult != null) {
                lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                Log.d(TAG, "Face detected by MediaPipe (direct image) in ${lastProcessTimeMs}ms")
                // MediaPipe 使用 GPU，但无 ROI 概念
                FaceDetectionResult(
                    landmarks106 = mediaPipeResult,
                    detectionSource = FaceDetectionSource.MEDIAPIPE,
                    roiRect = null,
                    roiDetectorName = "N/A",
                    useGpuForRoi = false,
                    landmarkDetectorName = "MediaPipe (GPU)",
                    useGpuForLandmark = true
                )
            } else {
                Log.d(TAG, "No face detected by MediaPipe direct image (${lastProcessTimeMs}ms)")
                null
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Log.e(TAG, "MediaPipe direct image detection failed", e)
            null
        }
    }

    private fun detectInsightFace(bitmap: Bitmap, lensFacing: Int, startTime: Long): FaceDetectionResult? {
        var roiResult: android.graphics.RectF? = null
        var landmarkResult: FloatArray? = null
        var roiError: Exception? = null
        var landmarkError: Exception? = null
        
        val roiStartTime = SystemClock.elapsedRealtime()
        
        // [性能优化] ROI 检测在独立线程中执行
        val roiJob = Thread(
            Runnable {
                try {
                    roiResult = roiDetector?.detectRoi(bitmap)
                } catch (e: Exception) {
                    roiError = e
                    Log.e(TAG, "ROI detection failed in parallel thread", e)
                }
            },
            "FaceDetect-ROI"
        )
        
        // Landmark 等待 ROI 完成后才能开始（因为需要 ROI 坐标）
        // 所以实际上是串行执行，但可以在 ROI 推理的同时准备 Landmark 输入
        roiJob.start()
        roiJob.join()
        
        // ROI 完成后才开始 Landmark 检测
        val landmarkStart = SystemClock.elapsedRealtime()
        landmarkResult = landmarkDetector?.detectLandmarks(bitmap, lensFacing, roiResult)
        val landmarkTime = SystemClock.elapsedRealtime() - landmarkStart
        
        val roiTime = SystemClock.elapsedRealtime() - roiStartTime
        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
        
        Log.d(TAG, "[Perf] InsightFace breakdown: ROI=${roiTime}ms, Landmark=${landmarkTime}ms, Total=${lastProcessTimeMs}ms")
        
        // 优先使用 Landmark 结果（更关键）
        if (landmarkResult != null) {
            lastDetectionSource = FaceDetectionSource.INSIGHTFACE
            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.INSIGHTFACE)
                as? InsightFaceAdapter
                ?: return null
            val adaptedResult = adapter.adapt(landmarkResult, lensFacing).getOrNull()
            if (adaptedResult != null) {
                Log.d(TAG, "InsightFace detection success in ${lastProcessTimeMs}ms")
                val normalizedRoi = roiResult?.let { r ->
                    android.graphics.RectF(
                        r.left / bitmap.width.toFloat(),
                        r.top / bitmap.height.toFloat(),
                        r.right / bitmap.width.toFloat(),
                        r.bottom / bitmap.height.toFloat()
                    )
                }
                
                // [调试信息] 构建检测器名称和 GPU 状态
                val roiDetectorName = roiDetector?.javaClass?.simpleName ?: "Unknown"
                val landmarkDetectorName = landmarkDetector?.javaClass?.simpleName ?: "Unknown"
                // MNN 检测器默认使用 GPU，ONNX Runtime 使用 CPU
                val useGpuForRoi = roiDetectorName.contains("Mnn", ignoreCase = true)
                val useGpuForLandmark = landmarkDetectorName.contains("Mnn", ignoreCase = true)
                
                return FaceDetectionResult(
                    landmarks106 = adaptedResult,
                    detectionSource = FaceDetectionSource.INSIGHTFACE,
                    roiRect = normalizedRoi,
                    roiDetectorName = roiDetectorName,
                    useGpuForRoi = useGpuForRoi,
                    landmarkDetectorName = landmarkDetectorName,
                    useGpuForLandmark = useGpuForLandmark
                )
            } else {
                Log.w(TAG, "InsightFace adaptation failed")
            }
        } else {
            Log.d(TAG, "No face detected by InsightFace (${lastProcessTimeMs}ms)")
            if (landmarkError != null) {
                Log.e(TAG, "Landmark detection error", landmarkError)
            }
        }
        
        return null
    }

    private fun detectMnn(bitmap: Bitmap, lensFacing: Int, startTime: Long): FaceDetectionResult? {
        var roiResult: android.graphics.RectF? = null
        var landmarkResult: FloatArray? = null
        
        val roiStartTime = SystemClock.elapsedRealtime()
        val roiDetector = mnnRoiDetector
        if (roiDetector != null) {
            try {
                roiResult = roiDetector.detectRoi(bitmap)
                Log.i(TAG, "[Perf] MNN ROI detection completed in ${SystemClock.elapsedRealtime() - roiStartTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "MNN ROI detection failed", e)
            }
        }
        
        if (roiResult == null) {
            Log.d(TAG, "No face detected by MNN ROI (${SystemClock.elapsedRealtime() - startTime}ms)")
            return null
        }
        
        // [性能优化] Landmark 检测使用 MnnLandmarkDetector
        val landmarkStartTime = SystemClock.elapsedRealtime()
        val landmarkDetector = mnnLandmarkDetector
        if (landmarkDetector != null) {
            try {
                landmarkResult = landmarkDetector.detectLandmarks(bitmap, lensFacing, roiResult)
                Log.i(TAG, "[Perf] MNN Landmark detection completed in ${SystemClock.elapsedRealtime() - landmarkStartTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "MNN Landmark detection failed", e)
            }
        }
        
        lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
        
        return if (landmarkResult != null && landmarkResult.size >= POINT_COUNT * 2) {
            lastDetectionSource = FaceDetectionSource.MNN
            
            // 归一化 ROI 坐标
            val normalizedRoi = android.graphics.RectF(
                roiResult.left / bitmap.width.toFloat(),
                roiResult.top / bitmap.height.toFloat(),
                roiResult.right / bitmap.width.toFloat(),
                roiResult.bottom / bitmap.height.toFloat()
            )
            
            Log.i(TAG, "[Perf] MNN detection DONE: total=${lastProcessTimeMs}ms, GPU✓")
            
            FaceDetectionResult(
                landmarks106 = landmarkResult,
                detectionSource = FaceDetectionSource.MNN,
                roiRect = normalizedRoi,
                roiDetectorName = "MnnRoiDetector",
                useGpuForRoi = true,
                landmarkDetectorName = "MnnLandmarkDetector",
                useGpuForLandmark = true
            )
        } else {
            Log.w(TAG, "MNN landmark adaptation failed")
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
