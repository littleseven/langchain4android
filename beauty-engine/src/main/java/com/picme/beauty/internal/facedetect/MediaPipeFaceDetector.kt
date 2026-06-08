package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.beauty.api.Logger
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.beauty.internal.facedetect.adapter.MediaPipe468Adapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MediaPipe 人脸检测器
 *
 * 职责：
 * - 管理 MediaPipe FaceLandmarker 实例（预览 VIDEO 模式 + 拍照 IMAGE 模式）
 * - 提供统一的人脸关键点检测接口
 * - 处理坐标适配和镜像
 */
class MediaPipeFaceDetector(context: Context) {

    companion object {
        private const val TAG = "MediaPipeDetector"
        private const val MODEL_PATH = "mediapipe/face_landmarker.task"
    }

    private val appContext: Context = context.applicationContext

    // 预览路径：VIDEO 模式（需要时间戳）
    @Volatile
    internal var videoLandmarker: FaceLandmarker? = null

    // 拍照路径：IMAGE 模式（无时间戳限制）
    @Volatile
    private var imageLandmarker: FaceLandmarker? = null

    // [Crash Fix] MediaPipe native 初始化必须在主线程执行，否则 Graph.nativeStartRunningGraph 会 SIGBUS
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitializing = false

    init {
        initScope.launch {
            try {
                initialize()
            } catch (linkError: UnsatisfiedLinkError) {
                Logger.w("MediaPipeDetector", "MediaPipe native library not found (x86_64 simulator?), disabling MediaPipe detection")
            } catch (error: Exception) {
                Logger.e("MediaPipeDetector", "Unexpected error initializing MediaPipe", error)
            }
        }
    }

    fun isReady(): Boolean {
        // [ANR 修复] 如果正在初始化中，返回 false，等待异步初始化完成
        if (isInitializing) {
            Logger.d("MediaPipeDetector", "MediaPipe still initializing, not ready yet")
            return false
        }
        return videoLandmarker != null || imageLandmarker != null
    }

    /**
     * 预览路径检测（VIDEO 模式）—— Image 零拷贝输入
     *
     * MediaPipe MediaImageBuilder 仅支持 RGBA_8888 格式的 android.media.Image。
     * 需配合 CameraX ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 使用。
     * 若设备不支持 RGBA 输出（降级到 YUV），调用方应 catch UnsupportedOperationException
     * 并降级到 detect(Bitmap) 路径。
     *
     * 此路径由 FaceDetectorManager.detectFromImage() 调用，
     * 是 MediaPipe 统一管线的首选路径。
     */
    fun detect(mediaImage: Image, rotationDegrees: Int, lensFacing: Int): FloatArray? {
        val landmarker = videoLandmarker ?: return null

        val mpImage = MediaImageBuilder(mediaImage).build()
        return try {
            val result = landmarker.detectForVideo(mpImage, SystemClock.uptimeMillis())

            if (result.faceLandmarks().isEmpty()) {
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: return null

            adapter.adapt(result.faceLandmarks()[0], lensFacing, rotationDegrees).getOrNull()
        } catch (e: Exception) {
            Logger.e("MediaPipeDetector", "MediaPipe preview detection (Image) failed", e)
            if (e is RuntimeException && e.message?.contains("native") == true) {
                handleNativeCrash()
            }
            null
        } finally {
            runCatching { mpImage.close() }
        }
    }

    /**
     * 预览路径检测（VIDEO 模式）—— Bitmap 输入（降级路径）
     */
    fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FloatArray? {
        val landmarker = videoLandmarker ?: return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            val result = landmarker.detectForVideo(mpImage, SystemClock.uptimeMillis())

            if (result.faceLandmarks().isEmpty()) {
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: return null

            adapter.adapt(result.faceLandmarks()[0], lensFacing, rotationDegrees).getOrNull()
        } catch (e: Exception) {
            Logger.e("MediaPipeDetector", "MediaPipe preview detection failed", e)
            // 如果检测过程中发生崩溃（通常是 GPU 驱动问题），尝试释放并标记不可用
            if (e is RuntimeException && e.message?.contains("native") == true) {
                handleNativeCrash()
            }
            null
        } finally {
            runCatching { mpImage.close() }
        }
    }

    /**
     * 拍照路径检测（IMAGE 模式）
     */
    fun detectForPhoto(bitmap: Bitmap, lensFacing: Int): FloatArray? {
        val landmarker = imageLandmarker ?: return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            val result = landmarker.detect(mpImage)

            if (result.faceLandmarks().isEmpty()) {
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: return null

            adapter.adapt(result.faceLandmarks()[0], lensFacing).getOrNull()
        } catch (e: Exception) {
            Logger.e("MediaPipeDetector", "MediaPipe photo detection failed", e)
            null
        } finally {
            runCatching { mpImage.close() }
        }
    }

    private fun handleNativeCrash() {
        Logger.e("MediaPipeDetector", "MediaPipe native layer error detected, disabling GPU acceleration")
        release()
        // 可以在此处标记下一次启动不使用 GPU
    }

    /**
     * 释放资源
     */
    fun release() {
        synchronized(this) {
            try {
                videoLandmarker?.close()
            } catch (e: Exception) {
                Logger.w("MediaPipeDetector", "Error closing video landmarker", e)
            }
            videoLandmarker = null
            
            try {
                imageLandmarker?.close()
            } catch (e: Exception) {
                Logger.w("MediaPipeDetector", "Error closing image landmarker", e)
            }
            imageLandmarker = null
        }
        Logger.i("MediaPipeDetector", "MediaPipeFaceDetector released")
    }

    private fun initialize() {
        isInitializing = true
        val startTime = SystemClock.elapsedRealtime()

        // 初始化预览路径 FaceLandmarker（VIDEO 模式）
        initializeVideoLandmarker()

        // 初始化拍照路径 FaceLandmarker（IMAGE 模式）
        initializeImageLandmarker()

        val elapsed = SystemClock.elapsedRealtime() - startTime
        Logger.i("MediaPipeDetector", "MediaPipe initialization completed in ${elapsed}ms")
        isInitializing = false
    }

    private fun initializeVideoLandmarker() {
        // [GPU First] 优先尝试 GPU delegate，失败自动降级 CPU
        val delegateOrder = listOf(Delegate.GPU, Delegate.CPU)
        for (delegate in delegateOrder) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(delegate)
                    .setModelAssetPath(MODEL_PATH)
                    .build()

                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinFaceDetectionConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .setMinFacePresenceConfidence(0.4f)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(false)
                    .setRunningMode(RunningMode.VIDEO)
                    .build()

                videoLandmarker = FaceLandmarker.createFromOptions(appContext, options)
                Logger.i("MediaPipeDetector", "MediaPipe FaceLandmarker initialized (${delegate.name}, VIDEO mode)")
                return
            } catch (e: Exception) {
                val isLast = delegate == delegateOrder.last()
                if (isLast) {
                    Logger.e("MediaPipeDetector", "Failed to initialize video landmarker with all delegates", e)
                } else {
                    Logger.w("MediaPipeDetector", "GPU delegate failed for video landmarker, falling back to CPU", e)
                }
            }
        }
    }

    private fun initializeImageLandmarker() {
        // [GPU First] 优先尝试 GPU delegate，失败自动降级 CPU
        val delegateOrder = listOf(Delegate.GPU, Delegate.CPU)
        for (delegate in delegateOrder) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(delegate)
                    .setModelAssetPath(MODEL_PATH)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(false)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                imageLandmarker = FaceLandmarker.createFromOptions(appContext, options)
                Logger.i("MediaPipeDetector", "MediaPipe FaceLandmarker initialized (${delegate.name}, IMAGE mode)")
                return
            } catch (e: Exception) {
                val isLast = delegate == delegateOrder.last()
                if (isLast) {
                    Logger.e("MediaPipeDetector", "Failed to initialize image landmarker with all delegates", e)
                } else {
                    Logger.w("MediaPipeDetector", "GPU delegate failed for image landmarker, falling back to CPU", e)
                }
            }
        }
    }
}