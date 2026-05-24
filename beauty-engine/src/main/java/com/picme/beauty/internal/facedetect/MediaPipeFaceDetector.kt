package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.beauty.internal.facedetect.adapter.MediaPipe468Adapter
import com.picme.beauty.api.facedetect.FaceDetectionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        private const val TAG = "PicMe:MediaPipeDetector"
        private const val MODEL_PATH = "mediapipe/face_landmarker.task"
    }

    private val appContext: Context = context.applicationContext

    // 预览路径：VIDEO 模式（需要时间戳）
    internal var videoLandmarker: FaceLandmarker? = null

    // 拍照路径：IMAGE 模式（无时间戳限制）
    private var imageLandmarker: FaceLandmarker? = null

    // [ANR 修复] 使用协程异步初始化，避免主线程阻塞
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitializing = false

    init {
        // [ANR 修复] 异步延迟初始化，不阻塞主线程
        initScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    initialize()
                }
            } catch (linkError: UnsatisfiedLinkError) {
                Log.w(TAG, "MediaPipe native library not found (x86_64 simulator?), disabling MediaPipe detection")
            } catch (error: Exception) {
                Log.e(TAG, "Unexpected error initializing MediaPipe", error)
            }
        }
    }

    fun isReady(): Boolean {
        // [ANR 修复] 如果正在初始化中，返回 false，等待异步初始化完成
        if (isInitializing) {
            Log.d(TAG, "MediaPipe still initializing, not ready yet")
            return false
        }
        return videoLandmarker != null || imageLandmarker != null
    }

    /**
     * 预览路径检测（VIDEO 模式）—— Bitmap 输入
     *
     * @param bitmap 输入 Bitmap（已旋转到正确方向）
     * @param rotationDegrees 图像旋转角度（保留参数兼容，但内部不再二次旋转）
     * @param lensFacing 镜头方向
     * @return 106 点归一化坐标，未检测到返回 null
     */
    fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FloatArray? {
        val landmarker = videoLandmarker ?: return null

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis())

            if (result.faceLandmarks().isEmpty()) {
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: return null

            adapter.adapt(result.faceLandmarks()[0], lensFacing, rotationDegrees).getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe preview detection failed", e)
            null
        }
    }

    /**
     * [GPU 检测优化 Phase 2] 预览路径检测（VIDEO 模式）—— 直接 YUV Image 输入
     *
     * 跳过 CPU 端的 Bitmap 生成，MediaPipe 直接从 YUV_420_888 数据进行 GPU 推理。
     * 坐标旋转在适配层（MediaPipe468Adapter）完成。
     *
     * @param mediaImage CameraX ImageProxy.image（YUV_420_888）
     * @param rotationDegrees 图像旋转角度（0/90/180/270），用于结果坐标旋转
     * @param lensFacing 镜头方向
     * @return 106 点归一化坐标，未检测到返回 null
     */
    fun detect(mediaImage: android.media.Image, rotationDegrees: Int, lensFacing: Int): FloatArray? {
        val landmarker = videoLandmarker ?: return null

        return try {
            val mpImage = MediaImageBuilder(mediaImage).build()
            val result = landmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis())

            if (result.faceLandmarks().isEmpty()) {
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: return null

            adapter.adapt(result.faceLandmarks()[0], lensFacing, rotationDegrees).getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe direct image detection failed", e)
            null
        }
    }

    /**
     * 拍照路径检测（IMAGE 模式）
     *
     * @param bitmap 输入图像
     * @param lensFacing 镜头方向
     * @return 106 点归一化坐标，未检测到返回 null
     */
    fun detectForPhoto(bitmap: Bitmap, lensFacing: Int): FloatArray? {
        val landmarker = imageLandmarker ?: return null

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.faceLandmarks().isEmpty()) {
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: return null

            adapter.adapt(result.faceLandmarks()[0], lensFacing).getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe photo detection failed", e)
            null
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        videoLandmarker?.close()
        videoLandmarker = null
        imageLandmarker?.close()
        imageLandmarker = null
        Log.i(TAG, "MediaPipeFaceDetector released")
    }

    private fun initialize() {
        isInitializing = true
        val startTime = SystemClock.elapsedRealtime()

        // 初始化预览路径 FaceLandmarker（VIDEO 模式）
        initializeVideoLandmarker()

        // 初始化拍照路径 FaceLandmarker（IMAGE 模式）
        initializeImageLandmarker()

        val elapsed = SystemClock.elapsedRealtime() - startTime
        Log.i(TAG, "MediaPipe initialization completed in ${elapsed}ms")
        isInitializing = false
    }

    private fun initializeVideoLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
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
            Log.i(TAG, "MediaPipe FaceLandmarker initialized (GPU, VIDEO mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize with GPU, fallback to CPU", e)
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
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
                Log.i(TAG, "MediaPipe FaceLandmarker initialized (CPU, VIDEO mode)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize video landmarker", e2)
            }
        }
    }

    private fun initializeImageLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
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
            Log.i(TAG, "MediaPipe FaceLandmarker initialized (GPU, IMAGE mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize photo landmarker with GPU, fallback to CPU", e)
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
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
                Log.i(TAG, "MediaPipe FaceLandmarker initialized (CPU, IMAGE mode)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize image landmarker", e2)
            }
        }
    }


}
