package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.picme.core.common.Logger
import com.picme.features.camera.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.picme.features.camera.facedetect.adapter.MediaPipe468Adapter
import com.picme.features.camera.preview.core.FaceDetectionSource

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
     * @param imageProxy CameraX ImageProxy
     * @param lensFacing 镜头方向
     * @return 106 点归一化坐标，未检测到返回 null
     */
    fun detectForPreview(imageProxy: ImageProxy, lensFacing: Int): FloatArray? {
        val landmarker = videoLandmarker ?: return null
        val bitmap = imageProxyToBitmap(imageProxy) ?: return null
        
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis())
            
            if (result.faceLandmarks().isEmpty()) {
                bitmap.recycle()
                return null
            }
            
            val adapter = FaceLandmarkAdapterRegistry.getAdapter(FaceDetectionSource.MEDIAPIPE)
                as? MediaPipe468Adapter
                ?: run {
                    bitmap.recycle()
                    return null
                }
            
            val adaptedResult = adapter.adapt(result.faceLandmarks()[0], lensFacing).getOrNull()
            bitmap.recycle()
            adaptedResult
        } catch (e: Exception) {
            Logger.e(TAG, "MediaPipe preview detection failed", e)
            bitmap.recycle()
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
            Logger.e(TAG, "MediaPipe photo detection failed", e)
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

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            // [关键修复] 处理 rowStride padding
            val yRowStride = imageProxy.planes[0].rowStride
            val uRowStride = imageProxy.planes[1].rowStride
            val vRowStride = imageProxy.planes[2].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride
            
            val width = imageProxy.width
            val height = imageProxy.height
            val uvWidth = width / 2
            val uvHeight = height / 2
            
            // Y 平面大小（不含 padding）
            val yPlaneSize = width * height
            // UV 平面大小（不含 padding）
            val uvPlaneSize = uvWidth * uvHeight
            
            val nv21 = ByteArray(yPlaneSize + uvPlaneSize * 2)
            
            // 复制 Y plane（逐行，跳过 rowStride padding）
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                val dstPos = row * width
                yBuffer.position(srcPos)
                yBuffer.get(nv21, dstPos, width)
            }
            
            // 复制 UV planes
            val uvOffset = yPlaneSize
            
            if (uvPixelStride == 2) {
                // NV21 格式：VU 交错
                // planes[1] 和 planes[2] 可能分别指向 U 和 V 的起始位置
                for (row in 0 until uvHeight) {
                    val uSrcPos = row * uRowStride
                    val vSrcPos = row * vRowStride
                    val dstPos = uvOffset + row * uvWidth * 2
                    
                    for (col in 0 until uvWidth) {
                        nv21[dstPos + col * 2] = vBuffer.get(vSrcPos + col * 2)
                        nv21[dstPos + col * 2 + 1] = uBuffer.get(uSrcPos + col * 2)
                    }
                }
            } else {
                // I420 格式：U 和 V 是独立的平面
                // 需要转换为 NV21 (Y + VU 交错)
                for (row in 0 until uvHeight) {
                    val uSrcPos = row * uRowStride
                    val vSrcPos = row * vRowStride
                    val dstPos = uvOffset + row * uvWidth * 2
                    
                    for (col in 0 until uvWidth) {
                        nv21[dstPos + col * 2] = vBuffer.get(vSrcPos + col)
                        nv21[dstPos + col * 2 + 1] = uBuffer.get(uSrcPos + col)
                    }
                }
            }

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width,
                height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
                100,
                out
            )
            val imageBytes = out.toByteArray()
            var bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            // 处理旋转
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }
}
