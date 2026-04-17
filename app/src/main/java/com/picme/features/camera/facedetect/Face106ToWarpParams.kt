package com.picme.features.camera.facedetect

import androidx.compose.ui.geometry.Offset
import com.picme.features.camera.preview.core.FaceContourData
import com.picme.features.camera.preview.core.FaceWarpParams

/**
 * 将 106 点 FloatArray 转换为 FaceWarpParams
 *
 * 106点索引定义（与 GPUPixel / Face++ 兼容）：
 * - 0-32: 脸部轮廓 33点
 * - 33-42: 左眉 10点
 * - 43-52: 右眉 10点
 * - 53-63: 左眼 11点（含瞳孔）
 * - 64-74: 右眼 11点（含瞳孔）
 * - 75-87: 鼻子 13点
 * - 88-96: 嘴巴外轮廓 9点
 * - 97-105: 嘴巴内轮廓 9点
 */
object Face106ToWarpParams {

    /**
     * 从 106 点 landmarks 构建 FaceWarpParams
     *
     * @param landmarks106 FloatArray(212) = [x0,y0, x1,y1, ..., x105,y105]
     * @return FaceWarpParams 供 BeautyRenderer 使用
     */
    fun convert(landmarks106: FloatArray): FaceWarpParams {
        require(landmarks106.size >= MediaPipeFaceDetector.POINT_COUNT * 2)

        // 脸部中心（使用轮廓点计算）
        val faceCenter = calculateFaceCenter(landmarks106)

        // 左右眼中心
        val leftEye = calculateEyeCenter(landmarks106, isLeft = true)
        val rightEye = calculateEyeCenter(landmarks106, isLeft = false)

        // 嘴巴关键点
        val mouthCenter = calculateMouthCenter(landmarks106)
        val mouthLeft = getPoint(landmarks106, 88)  // 嘴巴外轮廓左角
        val mouthRight = getPoint(landmarks106, 92) // 嘴巴外轮廓右角
        val upperLipCenter = getPoint(landmarks106, 90) // 上唇中心
        val lowerLipCenter = getPoint(landmarks106, 94) // 下唇中心

        // 脸部半径（使用轮廓点估算）
        val faceRadius = calculateFaceRadius(landmarks106, faceCenter)

        // 构建 contour 点列表（用于调试 UI 和 Shader）
        val contourPoints = extractContourPoints(landmarks106)
        val leftEyeContour = extractEyeContour(landmarks106, isLeft = true)
        val rightEyeContour = extractEyeContour(landmarks106, isLeft = false)
        val lipOuterContour = extractLipOuterContour(landmarks106)
        val lipInnerContour = extractLipInnerContour(landmarks106)
        val leftCheekContour = extractCheekContour(landmarks106, isLeft = true)
        val rightCheekContour = extractCheekContour(landmarks106, isLeft = false)

        return FaceWarpParams(
            faceCenterX = faceCenter.x,
            faceCenterY = faceCenter.y,
            leftEyeX = leftEye.x,
            leftEyeY = leftEye.y,
            rightEyeX = rightEye.x,
            rightEyeY = rightEye.y,
            mouthCenterX = mouthCenter.x,
            mouthCenterY = mouthCenter.y,
            mouthLeftX = mouthLeft.x,
            mouthLeftY = mouthLeft.y,
            mouthRightX = mouthRight.x,
            mouthRightY = mouthRight.y,
            upperLipCenterX = upperLipCenter.x,
            upperLipCenterY = upperLipCenter.y,
            lowerLipCenterX = lowerLipCenter.x,
            lowerLipCenterY = lowerLipCenter.y,
            faceRadius = faceRadius,
            hasFace = true,
            contourPoints = contourPoints,
            leftEyeContourPoints = leftEyeContour,
            rightEyeContourPoints = rightEyeContour,
            lipOuterContourPoints = lipOuterContour,
            lipInnerContourPoints = lipInnerContour,
            leftCheekContourPoints = leftCheekContour,
            rightCheekContourPoints = rightCheekContour,
            allContours = FaceContourData(), // 106点模式不使用 ML Kit 的 contour 数据
            gpuPixelLandmarks = com.picme.features.camera.preview.core.GpuPixelLandmarks.fromFloatArray(landmarks106)
        )
    }

    private fun getPoint(landmarks: FloatArray, index: Int): Offset {
        return Offset(landmarks[index * 2], landmarks[index * 2 + 1])
    }

    /**
     * 计算脸部中心（使用轮廓点平均）
     */
    private fun calculateFaceCenter(landmarks: FloatArray): Offset {
        var sumX = 0f
        var sumY = 0f
        // 使用轮廓点 0-32
        for (i in 0..32) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return Offset(sumX / 33f, sumY / 33f)
    }

    /**
     * 计算眼睛中心
     */
    private fun calculateEyeCenter(landmarks: FloatArray, isLeft: Boolean): Offset {
        val startIdx = if (isLeft) 53 else 64
        var sumX = 0f
        var sumY = 0f
        // 使用眼睑点（不含瞳孔）
        for (i in startIdx until startIdx + 10) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return Offset(sumX / 10f, sumY / 10f)
    }

    /**
     * 计算嘴巴中心
     */
    private fun calculateMouthCenter(landmarks: FloatArray): Offset {
        var sumX = 0f
        var sumY = 0f
        // 使用嘴巴外轮廓 88-96
        for (i in 88..96) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return Offset(sumX / 9f, sumY / 9f)
    }

    /**
     * 估算脸部半径
     */
    private fun calculateFaceRadius(landmarks: FloatArray, center: Offset): Float {
        var maxDist = 0f
        // 使用轮廓点计算最大距离
        for (i in 0..32) {
            val dx = landmarks[i * 2] - center.x
            val dy = landmarks[i * 2 + 1] - center.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            maxDist = kotlin.math.max(maxDist, dist)
        }
        return maxDist.coerceIn(0.12f, 0.45f)
    }

    /**
     * 提取脸部轮廓点（用于调试 UI）
     */
    private fun extractContourPoints(landmarks: FloatArray): List<Offset> {
        return (0..32).map { getPoint(landmarks, it) }
    }

    /**
     * 提取眼睛轮廓点
     */
    private fun extractEyeContour(landmarks: FloatArray, isLeft: Boolean): List<Offset> {
        val startIdx = if (isLeft) 53 else 64
        return (startIdx until startIdx + 10).map { getPoint(landmarks, it) }
    }

    /**
     * 提取嘴唇外轮廓点
     */
    private fun extractLipOuterContour(landmarks: FloatArray): List<Offset> {
        return (88..96).map { getPoint(landmarks, it) }
    }

    /**
     * 提取嘴唇内轮廓点
     */
    private fun extractLipInnerContour(landmarks: FloatArray): List<Offset> {
        return (97..105).map { getPoint(landmarks, it) }
    }

    /**
     * 提取脸颊轮廓点（使用脸部轮廓的子集）
     */
    private fun extractCheekContour(landmarks: FloatArray, isLeft: Boolean): List<Offset> {
        return if (isLeft) {
            // 左脸颊：轮廓点 27-32
            (27..32).map { getPoint(landmarks, it) }
        } else {
            // 右脸颊：轮廓点 0-5
            (0..5).map { getPoint(landmarks, it) }
        }
    }
}
