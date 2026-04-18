package com.picme.features.camera.facedetect

import androidx.compose.ui.geometry.Offset
import com.picme.features.camera.preview.core.FaceContourData
import com.picme.features.camera.preview.core.FaceWarpParams

/**
 * 将 106 点 FloatArray 转换为 FaceWarpParams
 *
 * 106点索引定义（mars-face-kit 格式，与GPUPixel一致）：
 * - 0-32:   脸部轮廓 33点
 * - 33-37:  左眉 5点
 * - 38-42:  右眉 5点
 * - 43:     眉心
 * - 44-46:  鼻梁 3点
 * - 47-51:  鼻尖 5点
 * - 52-57:  左眼外轮廓 6点
 * - 58-63:  右眼外轮廓 6点
 * - 64-67:  左眉下辅助 4点
 * - 68-71:  右眉下辅助 4点
 * - 72-74:  左眼内/下 3点（74=左瞳孔）
 * - 75-77:  右眼内/下 3点（77=右瞳孔）
 * - 78-79:  鼻孔上 2点
 * - 80-83:  鼻孔 4点
 * - 84-95:  嘴巴外轮廓 12点
 * - 96-103: 嘴巴内轮廓 8点
 * - 104:    左瞳孔重复
 * - 105:    右瞳孔重复
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

        // 嘴巴关键点（mars-face-kit格式：外84-95 + 内96-103）
        val mouthCenter = calculateMouthCenter(landmarks106)
        val mouthLeft = getPoint(landmarks106, 84)   // 左嘴角外
        val mouthRight = getPoint(landmarks106, 90)  // 右嘴角外
        val upperLipCenter = getPoint(landmarks106, 87) // 上唇外中心
        val lowerLipCenter = getPoint(landmarks106, 93) // 下唇外中心

        // 脸部半径（使用轮廓点估算）
        val faceRadius = calculateFaceRadius(landmarks106, faceCenter)

        // 构建 contour 点列表（用于调试 UI 和 Shader）
        val contourPoints = extractContourPoints(landmarks106)
        val leftEyeContour = extractEyeContour(landmarks106, isLeft = true)
        val rightEyeContour = extractEyeContour(landmarks106, isLeft = false)
        val mouthContour = extractMouthContour(landmarks106)
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
            lipOuterContourPoints = mouthContour,
            lipInnerContourPoints = emptyList(), // 新规范嘴巴合并为18点
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
     * mars-face-kit格式：左眼 52-57(外6点)+72-74(内3点)，右眼 58-63(外6点)+75-77(内3点)
     */
    private fun calculateEyeCenter(landmarks: FloatArray, isLeft: Boolean): Offset {
        // 使用外轮廓+内轮廓计算眼睛中心（不含瞳孔）
        val outerStart = if (isLeft) 52 else 58
        val innerStart = if (isLeft) 72 else 75
        var sumX = 0f
        var sumY = 0f
        // 外轮廓6点
        for (i in outerStart until outerStart + 6) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        // 内轮廓2点（不含瞳孔：72+2=74, 75+2=77）
        for (i in innerStart until innerStart + 2) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return Offset(sumX / 8f, sumY / 8f)
    }

    /**
     * 计算嘴巴中心
     * mars-face-kit格式：嘴巴外84-95(12点) + 内96-103(8点)
     */
    private fun calculateMouthCenter(landmarks: FloatArray): Offset {
        var sumX = 0f
        var sumY = 0f
        // 外轮廓12点
        for (i in 84..95) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        // 内轮廓8点
        for (i in 96..103) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return Offset(sumX / 20f, sumY / 20f)
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
     * mars-face-kit格式：左眼 52-57(外6)+72-74(内3)，右眼 58-63(外6)+75-77(内3)
     */
    private fun extractEyeContour(landmarks: FloatArray, isLeft: Boolean): List<Offset> {
        val outerStart = if (isLeft) 52 else 58
        val innerStart = if (isLeft) 72 else 75
        val result = mutableListOf<Offset>()
        // 外轮廓6点
        for (i in outerStart until outerStart + 6) {
            result.add(getPoint(landmarks, i))
        }
        // 内轮廓3点（含瞳孔）
        for (i in innerStart until innerStart + 3) {
            result.add(getPoint(landmarks, i))
        }
        return result
    }

    /**
     * 提取嘴巴轮廓点
     * mars-face-kit格式：嘴巴外84-95(12点) + 内96-103(8点)
     */
    private fun extractMouthContour(landmarks: FloatArray): List<Offset> {
        val result = mutableListOf<Offset>()
        // 外轮廓12点
        for (i in 84..95) {
            result.add(getPoint(landmarks, i))
        }
        // 内轮廓8点
        for (i in 96..103) {
            result.add(getPoint(landmarks, i))
        }
        return result
    }

    /**
     * 提取脸颊轮廓点（使用脸部轮廓的子集）
     * 商汤规范：左脸颊 2-6，右脸颊 27-31（避开下巴区域）
     */
    private fun extractCheekContour(landmarks: FloatArray, isLeft: Boolean): List<Offset> {
        return if (isLeft) {
            // 左脸颊：轮廓点 2-6
            (2..6).map { getPoint(landmarks, it) }
        } else {
            // 右脸颊：轮廓点 27-31
            (27..31).map { getPoint(landmarks, it) }
        }
    }
}
