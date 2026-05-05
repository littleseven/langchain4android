package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.features.camera.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.features.camera.facedetect.adapter.InsightFaceAdapter
import com.picme.features.camera.preview.core.FaceDetectionSource

/**
 * MediaPipe Face Landmarker 封装器
 *
 * 功能：
 * 1. 检测人脸 468 个 3D 关键点
 * 2. 将 468 点映射为与字节火山引擎兼容的 106 点格式
 * 3. 输出归一化坐标（0.0 ~ 1.0）
 *
 * 映射依据：
 * - 字节火山引擎106点标准：docs/face-detection/VOLCANO_106_POINTS.md
 * - MediaPipe 468点标准：docs/face-detection/MEDIAPIPE_468_REFERENCE.md
 * - 映射策略：优先使用对等语义点，缺失点使用插值
 *
 * 106点拓扑方向（被摄者真实面部，前置摄像头镜像后）：
 *   轮廓：从右脸鬓角(0,画面左侧) → 下巴(16) → 左脸鬓角(32,画面右侧)，开放曲线
 *   画面左侧=实际右脸，画面右侧=实际左脸
 *
 * @since 2026-04 替代 ML Kit Face Detection（大美丽模式）
 */
class FaceDetectorManager(
    context: Context,
    private var detectionEngineMode: FaceDetectionEngineMode = FaceDetectionEngineMode.INSIGHTFACE
) {

    fun setDetectionEngineMode(mode: FaceDetectionEngineMode) {
        if (detectionEngineMode != mode) {
            detectionEngineMode = mode
            Log.i(TAG, "Detection engine mode switched to: ${mode.name}")
        }
    }

    fun getDetectionEngineMode(): FaceDetectionEngineMode = detectionEngineMode

    companion object {
        private const val TAG = "PicMe:MediaPipeFace"

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private val appContext: Context = context.applicationContext
    
    // 新的流水线架构
    private var roiDetector: RoiDetector? = null
    private var landmarkDetector: LandmarkDetector? = null
    private var pipelineConfig: DetectionPipelineConfig = DetectionPipelineConfig()
    
    // 保留旧的检测器用于兼容
    private var mediaPipeDetector: MediaPipeFaceDetector? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null
    
    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        // 初始化旧的检测器用于兼容
        mediaPipeDetector = MediaPipeFaceDetector(context)
        
        // 初始化新的流水线
        initializePipeline()
        
        Log.i(TAG, "FaceDetectorManager initialized")
    }
    
    /**
     * 初始化检测流水线
     */
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
    
    /**
     * 更新检测配置
     */
    fun updatePipelineConfig(newConfig: DetectionPipelineConfig) {
        if (pipelineConfig == newConfig) return
        
        pipelineConfig = newConfig
        initializePipeline()
    }

    /**
     * 处理相机帧，检测人脸并返回 106 点归一化坐标
     *
     * @param imageProxy CameraX ImageProxy
     * @param lensFacing 镜头方向，用于坐标镜像
     * @return 106 点归一化坐标列表（FloatArray，偶数索引=x，奇数索引=y），无人脸返回 null
     */
    @ExperimentalGetImage
    fun detect(imageProxy: ImageProxy, lensFacing: Int): DetectionResult? {
        val startTime = SystemClock.elapsedRealtime()
        lastDetectionSource = FaceDetectionSource.NONE

        return try {
            when (detectionEngineMode) {
                FaceDetectionEngineMode.MEDIAPIPE -> {
                    val detector = mediaPipeDetector
                    if (detector == null || !detector.isReady()) {
                        Log.w(TAG, "MediaPipe detector not ready")
                        return@detect null
                    }
                    
                    val mediaPipeResult = detector.detectForPreview(imageProxy, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    
                    if (mediaPipeResult != null) {
                        lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                        Log.d(TAG, "Face detected by MediaPipe in ${lastProcessTimeMs}ms")
                        DetectionResult(mediaPipeResult, lastDetectionSource)
                    } else {
                        Log.d(TAG, "No face detected by MediaPipe (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                FaceDetectionEngineMode.INSIGHTFACE -> {
                    // [新流水线] 两阶段检测
                    // [方案4] 先转换 Bitmap,然后复用
                    val bitmapConvertStart = SystemClock.elapsedRealtime()
                    val bitmap = ImageUtils.imageProxyToBitmap(imageProxy) ?: return@detect null
                    val bitmapConvertTime = SystemClock.elapsedRealtime() - bitmapConvertStart
                    
                    val roiStartTime = SystemClock.elapsedRealtime()
                    // [方案4] 使用 Bitmap 版本的 ROI 检测,避免重复转换
                    val roi = (roiDetector as? Det10GRoiDetector)?.detectRoiFromBitmap(bitmap)
                        ?: roiDetector?.detectRoi(imageProxy)  // 降级:如果不是 Det10G,则用原方法
                    val roiTime = SystemClock.elapsedRealtime() - roiStartTime
                    
                    val landmarkStart = SystemClock.elapsedRealtime()
                    val rawResult = landmarkDetector?.detectLandmarks(
                        bitmap, lensFacing, roi
                    )
                    val landmarkTime = SystemClock.elapsedRealtime() - landmarkStart
                    
                    bitmap.recycle()
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    
                    Log.d(TAG, "[Perf] InsightFace breakdown: BitmapConvert=${bitmapConvertTime}ms, ROI=${roiTime}ms, Landmark=${landmarkTime}ms, Total=${lastProcessTimeMs}ms")
                    
                    if (rawResult != null) {
                        lastDetectionSource = FaceDetectionSource.INSIGHTFACE
                        val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.INSIGHTFACE)
                            as? InsightFaceAdapter
                            ?: return@detect null
                        val adaptedResult = adapter.adapt(rawResult, lensFacing).getOrNull()
                        if (adaptedResult != null) {
                            Log.d(TAG, "InsightFace detection success in ${lastProcessTimeMs}ms")
                            // [修复] ROI 已经是基于 Bitmap 尺寸的像素坐标,需要使用 Bitmap 尺寸进行归一化
                            // 注意: imageProxy 经过旋转后变成 Bitmap,尺寸可能不同
                            val rotatedWidth = when (imageProxy.imageInfo.rotationDegrees) {
                                90, 270 -> imageProxy.height
                                else -> imageProxy.width
                            }
                            val rotatedHeight = when (imageProxy.imageInfo.rotationDegrees) {
                                90, 270 -> imageProxy.width
                                else -> imageProxy.height
                            }
                            val normalizedRoi = roi?.let { r ->
                                android.graphics.RectF(
                                    r.left / rotatedWidth.toFloat(),
                                    r.top / rotatedHeight.toFloat(),
                                    r.right / rotatedWidth.toFloat(),
                                    r.bottom / rotatedHeight.toFloat()
                                )
                            }
                            DetectionResult(adaptedResult, lastDetectionSource, normalizedRoi)
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

    /**
     * 对 Bitmap 直接进行人脸检测（用于拍照路径）
     */
    fun detectPhoto(bitmap: android.graphics.Bitmap, lensFacing: Int): DetectionResult? {
        val detector = mediaPipeDetector
        if (detector == null || !detector.isReady()) {
            Log.w(TAG, "MediaPipe detector not ready for photo")
            return null
        }
        
        val mediaPipeResult = detector.detectForPhoto(bitmap, lensFacing)
        return if (mediaPipeResult != null) {
            DetectionResult(mediaPipeResult, FaceDetectionSource.MEDIAPIPE)
        } else {
            null
        }
    }

    /**
     * 获取最近一次检测耗时
     */
    fun getLastProcessTimeMs(): Long = lastProcessTimeMs

    fun getLastDetectionSource(): FaceDetectionSource = lastDetectionSource

    /**
     * 从 106 点 FloatArray 中提取指定索引的点
     */
    fun getPoint(landmarks106: FloatArray, index: Int): PointF {
        require(index in 0 until POINT_COUNT)
        return PointF(landmarks106[index * 2], landmarks106[index * 2 + 1])
    }

    /**
     * 释放资源
     */
    fun release() {
        // 释放新流水线
        roiDetector?.release()
        landmarkDetector?.release()
        roiDetector = null
        landmarkDetector = null
        
        // 释放旧检测器用于兼容
        mediaPipeDetector?.release()
        mediaPipeDetector = null
        insightFaceDetector?.release()
        insightFaceDetector = null
        lastDetectionSource = FaceDetectionSource.NONE
        Log.i(TAG, "FaceDetectorManager released")
    }

    data class DetectionResult(
        val landmarks106: FloatArray,
        val detectionSource: FaceDetectionSource,
        val roiRect: android.graphics.RectF? = null  // [新增] ROI 区域(归一化坐标)
    )
}
