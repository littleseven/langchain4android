package com.picme.features.camera.preview.core

import androidx.compose.ui.geometry.Offset
import com.picme.domain.model.FaceDetectionEngineMode

/**
 * 存储所有人脸 Contour 点的数据类（用于调试）
 */
data class FaceContourData(
    val faceOval: List<Offset> = emptyList(),      // 脸部轮廓 ~36点
    val leftEyebrowTop: List<Offset> = emptyList(),
    val leftEyebrowBottom: List<Offset> = emptyList(),
    val rightEyebrowTop: List<Offset> = emptyList(),
    val rightEyebrowBottom: List<Offset> = emptyList(),
    val leftEye: List<Offset> = emptyList(),       // 左眼轮廓
    val rightEye: List<Offset> = emptyList(),      // 右眼轮廓
    val upperLipTop: List<Offset> = emptyList(),
    val upperLipBottom: List<Offset> = emptyList(),
    val lowerLipTop: List<Offset> = emptyList(),
    val lowerLipBottom: List<Offset> = emptyList(),
    val noseBridge: List<Offset> = emptyList(),
    val noseBottom: List<Offset> = emptyList(),
    val leftCheek: List<Offset> = emptyList(),
    val rightCheek: List<Offset> = emptyList()
) {
    /**
     * 获取所有 Contour 点的总数（应等于 133）
     */
    fun totalPointCount(): Int {
        return faceOval.size + leftEyebrowTop.size + leftEyebrowBottom.size +
            rightEyebrowTop.size + rightEyebrowBottom.size +
            leftEye.size + rightEye.size +
            upperLipTop.size + upperLipBottom.size +
            lowerLipTop.size + lowerLipBottom.size +
            noseBridge.size + noseBottom.size +
            leftCheek.size + rightCheek.size
    }
}

enum class FaceDetectionSource {
    NONE,
    MEDIAPIPE,
    INSIGHTFACE
}

data class FaceWarpParams(
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val leftEyeX: Float = 0.4f,
    val leftEyeY: Float = 0.45f,
    val rightEyeX: Float = 0.6f,
    val rightEyeY: Float = 0.45f,
    val mouthCenterX: Float = 0.5f,
    val mouthCenterY: Float = 0.62f,
    val mouthLeftX: Float = 0.42f,
    val mouthLeftY: Float = 0.62f,
    val mouthRightX: Float = 0.58f,
    val mouthRightY: Float = 0.62f,
    val upperLipCenterX: Float = 0.5f,
    val upperLipCenterY: Float = 0.60f,
    val lowerLipCenterX: Float = 0.5f,
    val lowerLipCenterY: Float = 0.66f,
    val faceRadius: Float = 0.18f,
    val hasFace: Boolean = false,
    val contourPoints: List<Offset> = emptyList(),
    val leftEyeContourPoints: List<Offset> = emptyList(),
    val rightEyeContourPoints: List<Offset> = emptyList(),
    val lipOuterContourPoints: List<Offset> = emptyList(),
    val lipInnerContourPoints: List<Offset> = emptyList(),
    // 新增：脸颊 contour 点（用于精确腮红定位，ML Kit 133 点）
    val leftCheekContourPoints: List<Offset> = emptyList(),
    val rightCheekContourPoints: List<Offset> = emptyList(),
    // 新增：完整的 133 点 Contour 数据（用于调试，ML Kit 模式）
    val allContours: FaceContourData = FaceContourData(),
    // 新增：MediaPipe 106 点原始数据（用于调试对比）
    val bigBeautyLandmarks: GpuPixelLandmarks = GpuPixelLandmarks(),
    // 当前帧的人脸检测来源（调试浮层/日志使用）
    val detectionSource: FaceDetectionSource = FaceDetectionSource.NONE,
    // 设置页选择的人脸检测算法引擎
    val requestedDetectionEngineMode: FaceDetectionEngineMode = FaceDetectionEngineMode.MEDIAPIPE,
    // [新增] ROI 区域 (归一化坐标: left, top, right, bottom)
    val roiRect: android.graphics.RectF? = null
)

/**
 * 人脸 106 点数据结构（通用格式）
 *
 * 注：返回 111 个点，但前 106 个是标准 Face++ 106 点规范
 * 我们只使用标准的 106 点，忽略额外的 5 个辅助点
 */
data class GpuPixelLandmarks(
    val points: List<Offset> = emptyList(),  // 106 个点 (x,y) 归一化坐标
    val hasFace: Boolean = false
) {
    companion object {
        /**
         * 从 float 数组创建（格式：[x0,y0,x1,y1,...]）
         * 只取前 106 个点，忽略额外的 5 个辅助点
         */
        fun fromFloatArray(landmarks: FloatArray?): GpuPixelLandmarks {
            if (landmarks == null || landmarks.isEmpty()) {
                return GpuPixelLandmarks()
            }
            val points = mutableListOf<Offset>()
            val count = minOf(landmarks.size / 2, 106)
            for (i in 0 until count) {
                val x = landmarks[i * 2]
                val y = landmarks[i * 2 + 1]
                points.add(Offset(x, y))
            }
            return GpuPixelLandmarks(points = points, hasFace = true)
        }
    }
}

