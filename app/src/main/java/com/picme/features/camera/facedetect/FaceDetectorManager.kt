package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.picme.core.common.Logger
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
    
    // 委托给专门的检测器
    private var mediaPipeDetector: MediaPipeFaceDetector? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null
    
    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        // 初始化 MediaPipe 检测器
        mediaPipeDetector = MediaPipeFaceDetector(context)
        
        // InsightFace 检测器在需要时懒加载
        Log.i(TAG, "FaceDetectorManager initialized")
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
        
        // InsightFace 需要 Bitmap，MediaPipe 可以直接处理 ImageProxy
        val bitmap = if (detectionEngineMode == FaceDetectionEngineMode.INSIGHTFACE) {
            imageProxy.image?.let { img ->
                // Simple YUV to Bitmap conversion
                val yBuffer = imageProxy.planes[0].buffer
                val uBuffer = imageProxy.planes[1].buffer
                val vBuffer = imageProxy.planes[2].buffer
                
                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()
                
                val nv21 = ByteArray(ySize + uSize + vSize)
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
                
                val yuvImage = android.graphics.YuvImage(
                    nv21,
                    android.graphics.ImageFormat.NV21,
                    imageProxy.width,
                    imageProxy.height,
                    null
                )
                val out = java.io.ByteArrayOutputStream()
                yuvImage.compressToJpeg(
                    android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
                    100,
                    out
                )
                val imageBytes = out.toByteArray()
                var bmp = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees != 0) {
                    val matrix = android.graphics.Matrix().apply {
                        postRotate(rotationDegrees.toFloat())
                    }
                    bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                }
                bmp
            } ?: return null
        } else {
            null
        }

        return try {
            when (detectionEngineMode) {
                FaceDetectionEngineMode.MEDIAPIPE -> {
                    val detector = mediaPipeDetector
                    if (detector == null || !detector.isReady()) {
                        Logger.w(TAG, "MediaPipe detector not ready")
                        return@detect null
                    }
                    
                    val mediaPipeResult = detector.detectForPreview(imageProxy, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    
                    if (mediaPipeResult != null) {
                        lastDetectionSource = FaceDetectionSource.MEDIAPIPE
                        Logger.d(TAG, "Face detected by MediaPipe in ${lastProcessTimeMs}ms")
                        DetectionResult(mediaPipeResult, lastDetectionSource)
                    } else {
                        Logger.d(TAG, "No face detected by MediaPipe (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                FaceDetectionEngineMode.INSIGHTFACE -> {
                    // [两阶段检测] InsightFace2D106Detector 内部封装了 Det10G + 2d106det
                    Logger.d(TAG, "[Diag] === INSIGHTFACE mode START ===")
                    val insightFace = ensureInsightFaceDetector()
                    if (insightFace == null) {
                        Logger.w(TAG, "[Diag] InsightFace detector not ready")
                        return@detect null
                    }
                    // 不需要传入 faceBounds，内部会自动使用 Det10G 检测
                    val rawInsightFaceResult = insightFace.detect(bitmap!!, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    if (rawInsightFaceResult != null) {
                        lastDetectionSource = FaceDetectionSource.INSIGHTFACE
                        val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.INSIGHTFACE)
                            as? InsightFaceAdapter
                            ?: return@detect null
                        val adaptedResult = adapter.adapt(rawInsightFaceResult, lensFacing).getOrNull()
                        if (adaptedResult != null) {
                            Logger.d(TAG, "[Diag] === INSIGHTFACE END: success ${lastProcessTimeMs}ms ===")
                            DetectionResult(adaptedResult, lastDetectionSource)
                        } else {
                            Logger.w(TAG, "[Diag] === INSIGHTFACE END: adaptation failed ===")
                            null
                        }
                    } else {
                        Logger.d(TAG, "[Diag] === INSIGHTFACE END: detection failed (${lastProcessTimeMs}ms) ===")
                        null
                    }
                }

            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Face detection failed in ${detectionEngineMode.name} mode", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * 对 Bitmap 直接进行人脸检测（用于拍照路径）
     */
    fun detectPhoto(bitmap: android.graphics.Bitmap, lensFacing: Int): DetectionResult? {
        val detector = mediaPipeDetector
        if (detector == null || !detector.isReady()) {
            Logger.w(TAG, "MediaPipe detector not ready for photo")
            return null
        }
        
        val mediaPipeResult = detector.detectForPhoto(bitmap, lensFacing)
        return if (mediaPipeResult != null) {
            DetectionResult(mediaPipeResult, FaceDetectionSource.MEDIAPIPE)
        } else {
            null
        }
    }

    private fun ensureInsightFaceDetector(): InsightFace2D106Detector? {
        val cached = insightFaceDetector
        if (cached != null && cached.isReady()) {
            return cached
        }
        return runCatching {
            InsightFace2D106Detector(appContext)
        }.onSuccess { detector ->
            insightFaceDetector = detector.takeIf { instance -> instance.isReady() }
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize InsightFace 2D106 detector", error)
        }.getOrNull()?.takeIf { detector -> detector.isReady() }
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
        mediaPipeDetector?.release()
        mediaPipeDetector = null
        insightFaceDetector?.release()
        insightFaceDetector = null
        lastDetectionSource = FaceDetectionSource.NONE
        Log.i(TAG, "FaceDetectorManager released")
    }

    data class DetectionResult(
        val landmarks106: FloatArray,
        val detectionSource: FaceDetectionSource
    )
}
