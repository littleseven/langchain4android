package com.picme.beauty.internal.facedetect

import android.graphics.PointF
import com.picme.beauty.api.facedetect.FaceContourData
import com.picme.beauty.api.facedetect.FaceDetectionSource
import com.picme.beauty.api.facedetect.FaceWarpParams
import com.picme.beauty.api.facedetect.GpuPixelLandmarks

/**
 * 将 106 点 FloatArray 转换为 FaceWarpParams
 *
 * 106点索引定义（字节火山引擎标准，画面视角，前置摄像头镜像后）：
 *   画面左侧 = 实际右脸，画面右侧 = 实际左脸
 *
 * - 0-32:   脸部轮廓 33点（开放曲线：右鬓角0 → 下巴16 → 左鬓角32）
 * - 33-37:  右眉上部 5点（画面左侧=实际右脸，从眉头到眉尾）
 * - 38-42:  左眉上部 5点（画面右侧=实际左脸，从眉头到眉尾）
 * - 43:     眉心
 * - 44-46:  鼻梁 3点（从上到下）
 * - 47-51:  鼻尖 5点（从左到右：左鼻翼上→鼻尖左→鼻尖中心→鼻尖右→右鼻翼上）
 * - 52-57:  右眼外轮廓 6点（画面左侧=实际右脸，从外角→内角顺时针）
 * - 58-63:  左眼外轮廓 6点（画面右侧=实际左脸，从外角→内角顺时针）
 * - 64-67:  右眉下部 4点（画面左侧，从眉头到眉尾）
 * - 68-71:  左眉下部 4点（画面右侧，从眉头到眉尾）
 * - 72-74:  右眼内/下 3点（72=右眼内角，73=右眼下眼睑中，74=右瞳孔）
 * - 75-77:  左眼内/下 3点（75=左眼内角，76=左眼下眼睑中，77=左瞳孔）
 * - 78-79:  山根 2点（鼻梁起点左右：78=山根左，79=山根右）
 * - 80-83:  鼻孔 4点（80=左鼻孔左，81=左鼻孔右，82=右鼻孔左，83=右鼻孔右）
 * - 84-95:  嘴巴外轮廓 12点（84=左嘴角，90=唇珠，94=右嘴角）
 * - 96-103: 嘴巴内轮廓 8点（96=左内角，100=右内角）
 * - 104:    右瞳孔（与74同位置）
 * - 105:    左瞳孔（与77同位置）
 */
object Face106ToWarpParams {

    const val POINT_COUNT = 106
    const val CONTOUR_POINT_COUNT = 33
    const val NON_CONTOUR_POINT_COUNT = 73

    fun convert(
        landmarks106: FloatArray,
        detectionSource: FaceDetectionSource = FaceDetectionSource.NONE
    ): FaceWarpParams {
        require(landmarks106.size >= POINT_COUNT * 2)

        val faceCenter = calculateFaceCenter(landmarks106)
        val leftEye = calculateEyeCenter(landmarks106, isLeft = true)
        val rightEye = calculateEyeCenter(landmarks106, isLeft = false)
        val mouthCenter = calculateMouthCenter(landmarks106)
        val mouthLeft = getPoint(landmarks106, 84)
        val mouthRight = getPoint(landmarks106, 90)
        val upperLipCenter = getPoint(landmarks106, 87)
        val lowerLipCenter = getPoint(landmarks106, 93)
        val faceRadius = calculateFaceRadius(landmarks106, faceCenter)

        val contourPoints = extractContourPoints(landmarks106)
        val leftEyeContour = extractEyeContour(landmarks106, isLeft = true)
        val rightEyeContour = extractEyeContour(landmarks106, isLeft = false)
        val mouthContour = extractMouthContour(landmarks106)
        val leftCheekContour = extractCheekContour(landmarks106, isLeft = true)
        val rightCheekContour = extractCheekContour(landmarks106, isLeft = false)

        val bigBeautyLandmarks = GpuPixelLandmarks.fromFloatArray(landmarks106)

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
            lipInnerContourPoints = emptyList(),
            leftCheekContourPoints = leftCheekContour,
            rightCheekContourPoints = rightCheekContour,
            allContours = FaceContourData(),
            bigBeautyLandmarks = bigBeautyLandmarks,
            detectionSource = detectionSource
        )
    }

    private fun getPoint(landmarks: FloatArray, index: Int): PointF {
        return PointF(landmarks[index * 2], landmarks[index * 2 + 1])
    }

    private fun calculateFaceCenter(landmarks: FloatArray): PointF {
        var sumX = 0f
        var sumY = 0f
        for (i in 0..32) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return PointF(sumX / 33f, sumY / 33f)
    }

    private fun calculateEyeCenter(landmarks: FloatArray, isLeft: Boolean): PointF {
        val outerStart = if (isLeft) 52 else 58
        val innerStart = if (isLeft) 72 else 75
        var sumX = 0f
        var sumY = 0f
        for (i in outerStart until outerStart + 6) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        for (i in innerStart until innerStart + 2) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return PointF(sumX / 8f, sumY / 8f)
    }

    private fun calculateMouthCenter(landmarks: FloatArray): PointF {
        var sumX = 0f
        var sumY = 0f
        for (i in 84..95) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        for (i in 96..103) {
            sumX += landmarks[i * 2]
            sumY += landmarks[i * 2 + 1]
        }
        return PointF(sumX / 20f, sumY / 20f)
    }

    private fun calculateFaceRadius(landmarks: FloatArray, center: PointF): Float {
        var maxDist = 0f
        for (i in 0..32) {
            val dx = landmarks[i * 2] - center.x
            val dy = landmarks[i * 2 + 1] - center.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            maxDist = kotlin.math.max(maxDist, dist)
        }
        return maxDist.coerceIn(0.12f, 0.45f)
    }

    private fun extractContourPoints(landmarks: FloatArray): List<PointF> {
        return (0..32).map { getPoint(landmarks, it) }
    }

    private fun extractEyeContour(landmarks: FloatArray, isLeft: Boolean): List<PointF> {
        val outerStart = if (isLeft) 52 else 58
        val innerStart = if (isLeft) 72 else 75
        val result = mutableListOf<PointF>()
        for (i in outerStart until outerStart + 6) {
            result.add(getPoint(landmarks, i))
        }
        for (i in innerStart until innerStart + 3) {
            result.add(getPoint(landmarks, i))
        }
        return result
    }

    private fun extractMouthContour(landmarks: FloatArray): List<PointF> {
        val result = mutableListOf<PointF>()
        for (i in 84..95) {
            result.add(getPoint(landmarks, i))
        }
        for (i in 96..103) {
            result.add(getPoint(landmarks, i))
        }
        return result
    }

    private fun extractCheekContour(landmarks: FloatArray, isLeft: Boolean): List<PointF> {
        return if (isLeft) {
            (2..6).map { getPoint(landmarks, it) }
        } else {
            (27..31).map { getPoint(landmarks, it) }
        }
    }
}
