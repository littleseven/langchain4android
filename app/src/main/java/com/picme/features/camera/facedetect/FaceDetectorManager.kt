package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.core.common.Logger
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.features.camera.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.features.camera.facedetect.adapter.GpuPixelAdapter
import com.picme.features.camera.facedetect.adapter.InsightFaceAdapter
import com.picme.features.camera.facedetect.adapter.MediaPipe468Adapter
import com.picme.features.camera.preview.core.FaceDetectionSource
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import java.nio.ByteBuffer

/**
 * MediaPipe Face Landmarker 封装器
 *
 * 功能：
 * 1. 检测人脸 468 个 3D 关键点
 * 2. 将 468 点映射为与 GPUPixel/字节火山引擎兼容的 106 点格式
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
    private val detectionEngineMode: FaceDetectionEngineMode = FaceDetectionEngineMode.INSIGHTFACE
) {

    companion object {
        private const val TAG = "PicMe:MediaPipeFace"

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73
    }

    private val appContext: Context = context.applicationContext
    private var faceLandmarker: FaceLandmarker? = null
    // [修复] 拍照路径使用独立的 FaceLandmarker 实例（IMAGE 模式），
    // 避免与预览路径共享 VIDEO 模式实例导致时间戳序列冲突。
    private var photoFaceLandmarker: FaceLandmarker? = null
    private var insightFaceDetector: InsightFace2D106Detector? = null
    private var gpupixelFaceDetector: FaceDetector? = null
    private var lastProcessTimeMs: Long = 0
    private var lastDetectionSource: FaceDetectionSource = FaceDetectionSource.NONE

    // MediaPipe FaceLandmarker 不是线程安全的，预览线程和拍照线程可能并发访问
    private val mediaPipeLock = Any()

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        // 1) 初始化预览路径 FaceLandmarker（VIDEO 模式，需要时间戳）
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("mediapipe/face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setRunningMode(RunningMode.VIDEO)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe FaceLandmarker initialized (GPU delegate, VIDEO mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker with GPU, fallback to CPU", e)
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath("mediapipe/face_landmarker.task")
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(false)
                    .setRunningMode(RunningMode.VIDEO)
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(context, options)
                Log.i(TAG, "MediaPipe FaceLandmarker initialized (CPU delegate, VIDEO mode)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize FaceLandmarker", e2)
            }
        }

        // 2) 初始化拍照路径 FaceLandmarker（IMAGE 模式，无时间戳限制）
        try {
            val photoBaseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("mediapipe/face_landmarker.task")
                .build()
            val photoOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(photoBaseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            photoFaceLandmarker = FaceLandmarker.createFromOptions(context, photoOptions)
            Log.i(TAG, "MediaPipe FaceLandmarker initialized (GPU delegate, IMAGE mode for photo)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize photo FaceLandmarker with GPU, fallback to CPU", e)
            try {
                val photoBaseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath("mediapipe/face_landmarker.task")
                    .build()
                val photoOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(photoBaseOptions)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(false)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                photoFaceLandmarker = FaceLandmarker.createFromOptions(context, photoOptions)
                Log.i(TAG, "MediaPipe FaceLandmarker initialized (CPU delegate, IMAGE mode for photo)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize photo FaceLandmarker", e2)
            }
        }
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
        val bitmap = imageProxyToBitmap(imageProxy) ?: return null
        lastDetectionSource = FaceDetectionSource.NONE

        return try {
            when (detectionEngineMode) {
                FaceDetectionEngineMode.MEDIAPIPE -> {
                    val mediaPipeResult = detectWithMediaPipe(bitmap, lensFacing)
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
                    // 先用 MediaPipe 获取人脸框，为 InsightFace 提供 ROI（替代已移除的 ML Kit）
                    var faceBounds: android.graphics.Rect? = null
                    try {
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val mpResult = faceLandmarker?.detectForVideo(mpImage, SystemClock.uptimeMillis())
                        mpResult?.faceLandmarks()?.firstOrNull()?.let { landmarks ->
                            var minX = 1f
                            var maxX = 0f
                            var minY = 1f
                            var maxY = 0f
                            landmarks.forEach { landmark ->
                                val x = landmark.x()
                                val y = landmark.y()
                                if (x < minX) minX = x
                                if (x > maxX) maxX = x
                                if (y < minY) minY = y
                                if (y > maxY) maxY = y
                            }
                            faceBounds = android.graphics.Rect(
                                (minX * bitmap.width).toInt(),
                                (minY * bitmap.height).toInt(),
                                (maxX * bitmap.width).toInt(),
                                (maxY * bitmap.height).toInt()
                            )
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "MediaPipe face bounds detection failed for InsightFace ROI", e)
                    }

                    val rawInsightFaceResult = ensureInsightFaceDetector()?.detect(bitmap, lensFacing, faceBounds)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    if (rawInsightFaceResult != null) {
                        lastDetectionSource = FaceDetectionSource.INSIGHTFACE
                        // 将 InsightFace 原始 106 点映射为统一 106 标准
                        val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.INSIGHTFACE)
                            as? InsightFaceAdapter
                            ?: return@detect null
                        val adaptedResult = adapter.adapt(rawInsightFaceResult, lensFacing).getOrNull()
                        if (adaptedResult != null) {
                            Logger.d(TAG, "Face detected by InsightFace in ${lastProcessTimeMs}ms (adapted)")
                            DetectionResult(adaptedResult, lastDetectionSource)
                        } else {
                            Logger.w(TAG, "InsightFace detection succeeded but adaptation failed")
                            null
                        }
                    } else {
                        Logger.d(TAG, "No face detected by InsightFace (${lastProcessTimeMs}ms)")
                        null
                    }
                }

                FaceDetectionEngineMode.GPUPIXEL -> {
                    val gpupixelResult = detectWithGpupixel(bitmap, lensFacing)
                    lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
                    if (gpupixelResult != null) {
                        lastDetectionSource = FaceDetectionSource.GPUPIXEL
                        Logger.d(TAG, "Face detected by GPUPixel in ${lastProcessTimeMs}ms")
                        DetectionResult(gpupixelResult, lastDetectionSource)
                    } else {
                        Logger.d(TAG, "No face detected by GPUPixel (${lastProcessTimeMs}ms)")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            lastProcessTimeMs = SystemClock.elapsedRealtime() - startTime
            Logger.e(TAG, "Face detection failed in ${detectionEngineMode.name} mode", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 将 ImageProxy 转换为 Bitmap（MediaPipe 需要 Bitmap 输入）
     *
     * 使用标准 YUV_420_888 → RGBA 转换，然后创建 Bitmap。
     * 正确处理旋转和前置摄像头镜像。
     */
    @ExperimentalGetImage
    private fun imageProxyToBitmap(imageProxy: ImageProxy): android.graphics.Bitmap? {
        val image = imageProxy.image ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 使用 GPUPixel 的 Native 方法将 YUV 转换为 RGBA ByteBuffer
        val rgbaBuffer: java.nio.ByteBuffer = try {
            val buffers = com.pixpark.gpupixel.GPUPixel.YUV_420_888toI420AndRGBA(image, rotationDegrees)
                ?: return null
            // buffers[3] 是 RGBA
            buffers[3]
        } catch (e: Exception) {
            Log.e(TAG, "YUV to RGBA conversion failed", e)
            return null
        }

        // 计算旋转后的尺寸
        val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
            90, 270 -> Pair(height, width)
            else -> Pair(width, height)
        }

        // 从 RGBA ByteBuffer 创建 Bitmap
        val bitmap = android.graphics.Bitmap.createBitmap(rotatedWidth, rotatedHeight, android.graphics.Bitmap.Config.ARGB_8888)
        rgbaBuffer.rewind()
        bitmap.copyPixelsFromBuffer(rgbaBuffer)

        return bitmap
    }

    /**
     * 对 Bitmap 直接进行人脸检测（用于拍照路径）
     * 优先使用 MediaPipe（独立 IMAGE 模式实例，无时间戳冲突），如果失败则回退到 GPUPixel
     */
    fun detectPhoto(bitmap: android.graphics.Bitmap, lensFacing: Int): DetectionResult? {
        // 尝试 MediaPipe（使用独立的 IMAGE 模式实例）
        val mediaPipeResult = detectWithMediaPipeForPhoto(bitmap, lensFacing)
        if (mediaPipeResult != null) {
            return DetectionResult(mediaPipeResult, FaceDetectionSource.MEDIAPIPE)
        }
        // 回退到 GPUPixel
        val gpupixelResult = detectWithGpupixel(bitmap, lensFacing)
        if (gpupixelResult != null) {
            return DetectionResult(gpupixelResult, FaceDetectionSource.GPUPIXEL)
        }
        return null
    }

    /**
     * 预览路径：使用 VIDEO 模式 FaceLandmarker（共享实例，需要单调递增时间戳）
     */
    private fun detectWithMediaPipe(bitmap: android.graphics.Bitmap, lensFacing: Int): FloatArray? {
        val landmarker = faceLandmarker ?: return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = synchronized(mediaPipeLock) {
            landmarker.detectForVideo(mpImage, SystemClock.uptimeMillis())
        }
        if (result.faceLandmarks().isEmpty()) {
            return null
        }
        val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
            as? MediaPipe468Adapter
            ?: return null
        return adapter.adapt(result.faceLandmarks()[0], lensFacing).getOrNull()
    }

    /**
     * 拍照路径：使用独立的 IMAGE 模式 FaceLandmarker 实例
     * IMAGE 模式无时间戳限制，从根本上避免与预览路径的时间戳冲突。
     */
    private fun detectWithMediaPipeForPhoto(bitmap: android.graphics.Bitmap, lensFacing: Int): FloatArray? {
        val landmarker = photoFaceLandmarker ?: return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(mpImage)
        if (result.faceLandmarks().isEmpty()) {
            return null
        }
        val adapter: MediaPipe468Adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
            as? MediaPipe468Adapter
            ?: return null
        return adapter.adapt(result.faceLandmarks()[0], lensFacing).getOrNull()
    }

    private fun detectWithGpupixel(bitmap: android.graphics.Bitmap, lensFacing: Int): FloatArray? {
        val detector = ensureGpupixelFaceDetector() ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val rgbaLandmarks = detector.detect(
            buildDirectColorBuffer(pixels, FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA),
            bitmap.width,
            bitmap.height,
            bitmap.width * 4,
            FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
            FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
        )
        val rawLandmarks: FloatArray = if (rgbaLandmarks.isNotEmpty()) {
            rgbaLandmarks
        } else {
            detector.detect(
                buildDirectColorBuffer(pixels, FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA),
                bitmap.width,
                bitmap.height,
                bitmap.width * 4,
                FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA
            )
        }
        if (rawLandmarks.isEmpty()) {
            return null
        }

        val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.GPUPIXEL)
            ?: return null
        return adapter.adapt(rawLandmarks, lensFacing).getOrNull()
    }

    private fun buildDirectColorBuffer(pixels: IntArray, frameType: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(pixels.size * 4)
        for (pixel in pixels) {
            when (frameType) {
                FaceDetector.GPUPIXEL_FRAME_TYPE_BGRA -> {
                    buffer.put((pixel and 0xFF).toByte())
                    buffer.put((pixel shr 8 and 0xFF).toByte())
                    buffer.put((pixel shr 16 and 0xFF).toByte())
                    buffer.put((pixel shr 24 and 0xFF).toByte())
                }

                else -> {
                    buffer.put((pixel shr 16 and 0xFF).toByte())
                    buffer.put((pixel shr 8 and 0xFF).toByte())
                    buffer.put((pixel and 0xFF).toByte())
                    buffer.put((pixel shr 24 and 0xFF).toByte())
                }
            }
        }
        buffer.flip()
        return buffer
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

    private fun ensureGpupixelFaceDetector(): FaceDetector? {
        val cached = gpupixelFaceDetector
        if (cached != null) {
            return cached
        }
        return runCatching {
            GPUPixel.Init(appContext)
            FaceDetector.Create()
        }.onSuccess { detector ->
            gpupixelFaceDetector = detector
        }.onFailure { error ->
            Logger.e(TAG, "Failed to initialize GPUPixel face detector", error)
        }.getOrNull()
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
        faceLandmarker?.close()
        faceLandmarker = null
        insightFaceDetector?.release()
        insightFaceDetector = null
        gpupixelFaceDetector?.destroy()
        gpupixelFaceDetector = null
        lastDetectionSource = FaceDetectionSource.NONE
        Log.i(TAG, "FaceDetectorManager released")
    }

    data class DetectionResult(
        val landmarks106: FloatArray,
        val detectionSource: FaceDetectionSource
    )
}
