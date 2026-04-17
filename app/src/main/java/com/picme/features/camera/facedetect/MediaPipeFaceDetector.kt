package com.picme.features.camera.facedetect

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.picme.core.common.Logger
import java.nio.ByteBuffer

/**
 * MediaPipe Face Landmarker 封装器
 *
 * 功能：
 * 1. 检测人脸 468 个 3D 关键点
 * 2. 将 468 点映射为与 GPUPixel 兼容的 106 点格式
 * 3. 输出归一化坐标（0.0 ~ 1.0）
 *
 * @since 2026-04 替代 ML Kit Face Detection（大美丽模式）
 */
class MediaPipeFaceDetector(context: Context) {

    companion object {
        private const val TAG = "PicMe:MediaPipeFace"

        // MediaPipe 468 → 106 点映射表（基于 Face++ / GPUPixel 106 点规范）
        // 106点索引顺序：轮廓33点 + 左眉10点 + 右眉10点 + 左眼11点 + 右眼11点 + 鼻子13点 + 嘴巴外9点 + 嘴巴内9点
        val MEDIAPIPE_TO_106_MAPPING = intArrayOf(
            // === 脸部轮廓 33 点 (0-32) ===
            10,  // 0  - 右脸颊起点
            338, // 1
            297, // 2
            332, // 3
            284, // 4
            251, // 5
            389, // 6
            356, // 7
            454, // 8
            323, // 9
            361, // 10
            288, // 11
            397, // 12
            365, // 13
            379, // 14
            378, // 15
            400, // 16
            377, // 17
            152, // 18 - 下巴
            148, // 19
            176, // 20
            149, // 21
            150, // 22
            136, // 23
            172, // 24
            58,  // 25
            132, // 26
            93,  // 27
            234, // 28
            127, // 29
            162, // 30
            21,  // 31
            54,  // 32 - 左脸颊起点

            // === 左眉 10 点 (33-42) ===
            105, 46, 53, 52, 65,  // 上眉 5 点
            55, 107, 66, 70, 63,  // 下眉 5 点

            // === 右眉 10 点 (43-52) ===
            334, 276, 283, 282, 295,  // 上眉 5 点
            285, 336, 296, 300, 293,  // 下眉 5 点

            // === 左眼 11 点 (53-63) ===
            33, 246, 161, 160, 159,  // 上眼睑 5 点
            158, 157, 173, 133, 155, // 下眼睑 5 点
            468,                     // 瞳孔中心 (虹膜)

            // === 右眼 11 点 (64-74) ===
            362, 398, 384, 385, 386, // 上眼睑 5 点
            387, 388, 466, 263, 382, // 下眼睑 5 点
            473,                     // 瞳孔中心 (虹膜)

            // === 鼻子 13 点 (75-87) ===
            6, 197, 195, 5, 4,       // 鼻梁 5 点
            1, 19, 94, 2, 164,       // 鼻尖区域 5 点
            98, 97, 327,             // 鼻翼 3 点

            // === 嘴巴外轮廓 9 点 (88-96) ===
            61, 185, 40, 39, 37,     // 上唇 5 点
            0, 267, 269, 270,        // 下唇 4 点

            // === 嘴巴内轮廓 9 点 (97-105) ===
            78, 191, 80, 81, 82,     // 内上唇 5 点
            13, 312, 311, 310        // 内下唇 4 点
        )

        const val POINT_COUNT = 106
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var lastProcessTimeMs: Long = 0

    init {
        initialize(context)
    }

    private fun initialize(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("face_landmarker.task")
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
            Log.i(TAG, "MediaPipe FaceLandmarker initialized (GPU delegate)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker with GPU, fallback to CPU", e)
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath("face_landmarker.task")
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
                Log.i(TAG, "MediaPipe FaceLandmarker initialized (CPU delegate)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize FaceLandmarker", e2)
            }
        }
    }

    /**
     * 处理相机帧，检测人脸并返回 106 点归一化坐标
     *
     * @param imageProxy CameraX ImageProxy
     * @param rotationDegrees 图像旋转角度
     * @return 106 点归一化坐标列表（FloatArray，偶数索引=x，奇数索引=y），无人脸返回 null
     */
    fun detect(imageProxy: ImageProxy, rotationDegrees: Int): FloatArray? {
        val landmarker = faceLandmarker ?: return null

        val startTime = SystemClock.elapsedRealtime()

        return try {
            val bitmap = imageProxyToBitmap(imageProxy) ?: return null
            val mpImage = BitmapImageBuilder(bitmap).build()

            val result = landmarker.detectForVideo(mpImage, SystemClock.uptimeMillis())

            val processTime = SystemClock.elapsedRealtime() - startTime
            lastProcessTimeMs = processTime

            if (result.faceLandmarks().isEmpty()) {
                Logger.d(TAG, "No face detected (${processTime}ms)")
                return null
            }

            val landmarks = result.faceLandmarks()[0]
            val points106 = convert468To106(landmarks, rotationDegrees)

            Logger.d(TAG, "Face detected: 106 points in ${processTime}ms")
            points106

        } catch (e: Exception) {
            Logger.e(TAG, "Face detection failed", e)
            null
        }
    }

    /**
     * 将 MediaPipe 468 点转换为 106 点 FloatArray
     *
     * @param landmarks MediaPipe 468 个 NormalizedLandmark
     * @param rotationDegrees 图像旋转角度，用于坐标变换
     * @return FloatArray(212) = [x0,y0, x1,y1, ..., x105,y105]
     */
    private fun convert468To106(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        rotationDegrees: Int
    ): FloatArray {
        val result = FloatArray(POINT_COUNT * 2)

        for (i in 0 until POINT_COUNT) {
            val mpIndex = MEDIAPIPE_TO_106_MAPPING[i]
            if (mpIndex < landmarks.size) {
                val landmark = landmarks[mpIndex]
                val (x, y) = when (rotationDegrees) {
                    90 -> Pair(1f - landmark.y(), landmark.x())
                    180 -> Pair(1f - landmark.x(), 1f - landmark.y())
                    270 -> Pair(landmark.y(), 1f - landmark.x())
                    else -> Pair(landmark.x(), landmark.y())
                }
                result[i * 2] = x.coerceIn(0f, 1f)
                result[i * 2 + 1] = y.coerceIn(0f, 1f)
            }
        }

        return result
    }

    /**
     * 将 ImageProxy 转换为 Bitmap（MediaPipe 需要 Bitmap 输入）
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): android.graphics.Bitmap? {
        val image = imageProxy.image ?: return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

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
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
            100,
            out
        )
        val jpegBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        // 根据旋转角度旋转 Bitmap
        return if (imageProxy.imageInfo.rotationDegrees != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
        } else {
            bitmap
        }
    }

    /**
     * 获取最近一次检测耗时
     */
    fun getLastProcessTimeMs(): Long = lastProcessTimeMs

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
        Log.i(TAG, "MediaPipeFaceDetector released")
    }
}
