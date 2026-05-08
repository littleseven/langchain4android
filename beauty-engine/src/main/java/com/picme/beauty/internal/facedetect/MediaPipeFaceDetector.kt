package com.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.beauty.internal.facedetect.adapter.MediaPipe468Adapter
import com.picme.beauty.api.facedetect.FaceDetectionSource

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

    init {
        // [修复] 捕获 UnsatisfiedLinkError，支持模拟器 x86_64 架构（MediaPipe 不提供 x86_64 库）
        try {
            initialize()
        } catch (linkError: UnsatisfiedLinkError) {
            Log.w(TAG, "MediaPipe native library not found (x86_64 simulator?), disabling MediaPipe detection")
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected error initializing MediaPipe", error)
        }
    }

    fun isReady(): Boolean = videoLandmarker != null || imageLandmarker != null

    /**
     * 预览路径检测（VIDEO 模式）
     *
     * @param bitmap 输入 Bitmap
     * @param rotationDegrees 图像旋转角度
     * @param lensFacing 镜头方向
     * @return 106 点归一化坐标，未检测到返回 null
     */
    fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FloatArray? {
        val landmarker = videoLandmarker ?: return null

        val processedBitmap = if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        return try {
            val mpImage = BitmapImageBuilder(processedBitmap).build()
            val result = landmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis())

            if (result.faceLandmarks().isEmpty()) {
                if (processedBitmap !== bitmap) processedBitmap.recycle()
                return null
            }

            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: run {
                    if (processedBitmap !== bitmap) processedBitmap.recycle()
                    return null
                }

            val adaptedResult = adapter.adapt(result.faceLandmarks()[0], lensFacing).getOrNull()
            if (processedBitmap !== bitmap) processedBitmap.recycle()
            adaptedResult
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe preview detection failed", e)
            if (processedBitmap !== bitmap) processedBitmap.recycle()
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
        // 初始化预览路径 FaceLandmarker（VIDEO 模式）
        initializeVideoLandmarker()
        
        // 初始化拍照路径 FaceLandmarker（IMAGE 模式）
        initializeImageLandmarker()
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
