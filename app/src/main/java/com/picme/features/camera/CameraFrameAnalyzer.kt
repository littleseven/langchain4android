package com.picme.features.camera

import android.graphics.PointF
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceLandmark
import com.picme.core.common.Logger
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.preview.core.FaceWarpParams

private const val LIP_CONTOUR_POINT_COUNT = 20

@ExperimentalGetImage
internal fun handleImageAnalysisFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    previewView: PreviewView,
    faceDetector: FaceDetector,
    lensFacing: Int,
    beautySettings: BeautySettings,
    onFacePointChanged: (Offset) -> Unit,
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val fallbackPreviewWidth = previewView.width.takeIf { width -> width > 0 }?.toFloat()
            ?: imageProxy.width.toFloat()
        val fallbackPreviewHeight = previewView.height.takeIf { height -> height > 0 }?.toFloat()
            ?: imageProxy.height.toFloat()

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                // [方案 B 变种] 缓存人脸检测结果供拍照使用
                FaceDetectionCache.updateFaces(faces)

                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox
                    val previewWidth = fallbackPreviewWidth
                    val previewHeight = fallbackPreviewHeight
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                    // 使用纹理归一化坐标（0~1，基于相机输入帧旋转后尺寸）
                    // 这样 Shader 直接接收纹理坐标，与画面比例无关
                    val faceCenterNorm = transformFaceCoordinateToNormalized(
                        faceX = bounds.centerX().toFloat(),
                        faceY = bounds.centerY().toFloat(),
                        imageProxyWidth = imageProxy.width,
                        imageProxyHeight = imageProxy.height,
                        rotationDegrees = rotationDegrees,
                        lensFacing = lensFacing
                    )

                    // 调试UI和聚焦指示器仍需要屏幕像素坐标
                    val screenPoint = Offset(
                        x = faceCenterNorm.x * previewWidth,
                        y = faceCenterNorm.y * previewHeight
                    )
                    onFacePointChanged(screenPoint)
                    onShowFocusIndicatorChanged(true)

                    val leftEyeLandmark = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                    val leftEyeNorm = if (leftEyeLandmark != null) {
                        transformFaceCoordinateToNormalized(
                            faceX = leftEyeLandmark.x,
                            faceY = leftEyeLandmark.y,
                            imageProxyWidth = imageProxy.width,
                            imageProxyHeight = imageProxy.height,
                            rotationDegrees = rotationDegrees,
                            lensFacing = lensFacing
                        )
                    } else {
                        Offset(
                            x = (faceCenterNorm.x - bounds.width().toFloat() / imageProxy.width.toFloat() * 0.16f).coerceIn(0f, 1f),
                            y = (faceCenterNorm.y - bounds.height().toFloat() / imageProxy.height.toFloat() * 0.10f).coerceIn(0f, 1f)
                        )
                    }

                    val rightEyeNorm = if (rightEyeLandmark != null) {
                        transformFaceCoordinateToNormalized(
                            faceX = rightEyeLandmark.x,
                            faceY = rightEyeLandmark.y,
                            imageProxyWidth = imageProxy.width,
                            imageProxyHeight = imageProxy.height,
                            rotationDegrees = rotationDegrees,
                            lensFacing = lensFacing
                        )
                    } else {
                        Offset(
                            x = (faceCenterNorm.x + bounds.width().toFloat() / imageProxy.width.toFloat() * 0.16f).coerceIn(0f, 1f),
                            y = (faceCenterNorm.y - bounds.height().toFloat() / imageProxy.height.toFloat() * 0.10f).coerceIn(0f, 1f)
                        )
                    }

                    val mouthLeftLandmark = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                    val mouthRightLandmark = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
                    val mouthBottomLandmark = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

                    val upperLipTopPoints = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: emptyList()
                    val upperLipBottomPoints = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: emptyList()
                    val lowerLipTopPoints = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: emptyList()
                    val lowerLipBottomPoints = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: emptyList()
                    val allLipPoints = upperLipTopPoints + upperLipBottomPoints + lowerLipTopPoints + lowerLipBottomPoints

                    val upperLipFallback = if (mouthLeftLandmark != null && mouthRightLandmark != null) {
                        PointF(
                            (mouthLeftLandmark.x + mouthRightLandmark.x) * 0.5f,
                            (mouthLeftLandmark.y + mouthRightLandmark.y) * 0.5f
                        )
                    } else {
                        PointF(
                            bounds.centerX().toFloat(),
                            bounds.centerY().toFloat() + bounds.height().toFloat() * 0.18f
                        )
                    }
                    val lowerLipFallback = mouthBottomLandmark ?: PointF(
                        upperLipFallback.x,
                        upperLipFallback.y + bounds.height().toFloat() * 0.06f
                    )

                    val mouthLeftRaw = mouthLeftLandmark
                        ?: allLipPoints.minByOrNull { lipPoint -> lipPoint.x }
                        ?: PointF(
                            upperLipFallback.x - bounds.width().toFloat() * 0.12f,
                            upperLipFallback.y
                        )
                    val mouthRightRaw = mouthRightLandmark
                        ?: allLipPoints.maxByOrNull { lipPoint -> lipPoint.x }
                        ?: PointF(
                            upperLipFallback.x + bounds.width().toFloat() * 0.12f,
                            upperLipFallback.y
                        )

                    val upperLipCenterRaw = averagePoint(upperLipTopPoints + upperLipBottomPoints)
                        ?: upperLipFallback
                    val lowerLipCenterRaw = averagePoint(lowerLipTopPoints + lowerLipBottomPoints)
                        ?: lowerLipFallback
                    val mouthCenterRaw = PointF(
                        (upperLipCenterRaw.x + lowerLipCenterRaw.x) * 0.5f,
                        (upperLipCenterRaw.y + lowerLipCenterRaw.y) * 0.5f
                    )

                    fun mapLipPointToNorm(rawPoint: PointF): Offset {
                        return transformFaceCoordinateToNormalized(
                            faceX = rawPoint.x,
                            faceY = rawPoint.y,
                            imageProxyWidth = imageProxy.width,
                            imageProxyHeight = imageProxy.height,
                            rotationDegrees = rotationDegrees,
                            lensFacing = lensFacing
                        )
                    }

                    val mouthCenterNorm = mapLipPointToNorm(mouthCenterRaw)
                    val mouthLeftNorm = mapLipPointToNorm(mouthLeftRaw)
                    val mouthRightNorm = mapLipPointToNorm(mouthRightRaw)
                    val upperLipCenterNorm = mapLipPointToNorm(upperLipCenterRaw)
                    val lowerLipCenterNorm = mapLipPointToNorm(lowerLipCenterRaw)

                    val faceRadius = (
                        maxOf(bounds.width().toFloat() / imageProxy.width.toFloat(), 0.16f)
                    ).coerceIn(0.12f, 0.38f)

                    fun mapContourToNorm(points: List<PointF>?): List<Offset> {
                        return points?.map { contourPoint ->
                            transformFaceCoordinateToNormalized(
                                faceX = contourPoint.x,
                                faceY = contourPoint.y,
                                imageProxyWidth = imageProxy.width,
                                imageProxyHeight = imageProxy.height,
                                rotationDegrees = rotationDegrees,
                                lensFacing = lensFacing
                            )
                        } ?: emptyList()
                    }

                    val contourPoints = mapContourToNorm(face.getContour(FaceContour.FACE)?.points)
                    val leftEyeContourPoints = mapContourToNorm(face.getContour(FaceContour.LEFT_EYE)?.points)
                    val rightEyeContourPoints = mapContourToNorm(face.getContour(FaceContour.RIGHT_EYE)?.points)

                    val lipOuterContourRaw = if (upperLipTopPoints.isNotEmpty() && lowerLipBottomPoints.isNotEmpty()) {
                        upperLipTopPoints + lowerLipBottomPoints.reversed()
                    } else {
                        allLipPoints
                    }
                    val lipInnerContourRaw = if (upperLipBottomPoints.isNotEmpty() && lowerLipTopPoints.isNotEmpty()) {
                        upperLipBottomPoints + lowerLipTopPoints.reversed()
                    } else {
                        emptyList()
                    }

                    fun mapLipContourToNorm(rawPoints: List<PointF>): List<Offset> {
                        return resampleContourPoints(rawPoints).map { lipPoint ->
                            transformFaceCoordinateToNormalized(
                                faceX = lipPoint.x,
                                faceY = lipPoint.y,
                                imageProxyWidth = imageProxy.width,
                                imageProxyHeight = imageProxy.height,
                                rotationDegrees = rotationDegrees,
                                lensFacing = lensFacing
                            )
                        }
                    }

                    val lipOuterContourPoints = mapLipContourToNorm(lipOuterContourRaw)
                    val lipInnerContourPoints = mapLipContourToNorm(lipInnerContourRaw)

                    val allContours = com.picme.features.camera.preview.core.FaceContourData(
                        faceOval = mapContourToNorm(face.getContour(FaceContour.FACE)?.points),
                        leftEyebrowTop = mapContourToNorm(face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points),
                        leftEyebrowBottom = mapContourToNorm(face.getContour(FaceContour.LEFT_EYEBROW_BOTTOM)?.points),
                        rightEyebrowTop = mapContourToNorm(face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points),
                        rightEyebrowBottom = mapContourToNorm(face.getContour(FaceContour.RIGHT_EYEBROW_BOTTOM)?.points),
                        leftEye = mapContourToNorm(face.getContour(FaceContour.LEFT_EYE)?.points),
                        rightEye = mapContourToNorm(face.getContour(FaceContour.RIGHT_EYE)?.points),
                        upperLipTop = mapContourToNorm(face.getContour(FaceContour.UPPER_LIP_TOP)?.points),
                        upperLipBottom = mapContourToNorm(face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points),
                        lowerLipTop = mapContourToNorm(face.getContour(FaceContour.LOWER_LIP_TOP)?.points),
                        lowerLipBottom = mapContourToNorm(face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points),
                        noseBridge = mapContourToNorm(face.getContour(FaceContour.NOSE_BRIDGE)?.points),
                        noseBottom = mapContourToNorm(face.getContour(FaceContour.NOSE_BOTTOM)?.points),
                        leftCheek = mapContourToNorm(face.getContour(FaceContour.LEFT_CHEEK)?.points),
                        rightCheek = mapContourToNorm(face.getContour(FaceContour.RIGHT_CHEEK)?.points)
                    )

                    android.util.Log.d(
                        "PicMe:Camera",
                        "ML Kit 133点统计: total=${allContours.totalPointCount()}, " +
                        "faceOval=${allContours.faceOval.size}, " +
                        "leftEye=${allContours.leftEye.size}, " +
                        "rightEye=${allContours.rightEye.size}, " +
                        "leftEyebrow=${allContours.leftEyebrowTop.size + allContours.leftEyebrowBottom.size}, " +
                        "rightEyebrow=${allContours.rightEyebrowTop.size + allContours.rightEyebrowBottom.size}, " +
                        "nose=${allContours.noseBridge.size + allContours.noseBottom.size}, " +
                        "lips=${allContours.upperLipTop.size + allContours.upperLipBottom.size + allContours.lowerLipTop.size + allContours.lowerLipBottom.size}, " +
                        "cheeks=${allContours.leftCheek.size + allContours.rightCheek.size}"
                    )

                    val leftCheekContourPoints = mapContourToNorm(face.getContour(FaceContour.LEFT_CHEEK)?.points)
                    val rightCheekContourPoints = mapContourToNorm(face.getContour(FaceContour.RIGHT_CHEEK)?.points)

                    val faceWarpParams = FaceWarpParams(
                        faceCenterX = faceCenterNorm.x,
                        faceCenterY = faceCenterNorm.y,
                        leftEyeX = leftEyeNorm.x,
                        leftEyeY = leftEyeNorm.y,
                        rightEyeX = rightEyeNorm.x,
                        rightEyeY = rightEyeNorm.y,
                        mouthCenterX = mouthCenterNorm.x,
                        mouthCenterY = mouthCenterNorm.y,
                        mouthLeftX = mouthLeftNorm.x,
                        mouthLeftY = mouthLeftNorm.y,
                        mouthRightX = mouthRightNorm.x,
                        mouthRightY = mouthRightNorm.y,
                        upperLipCenterX = upperLipCenterNorm.x,
                        upperLipCenterY = upperLipCenterNorm.y,
                        lowerLipCenterX = lowerLipCenterNorm.x,
                        lowerLipCenterY = lowerLipCenterNorm.y,
                        faceRadius = faceRadius,
                        hasFace = true,
                        contourPoints = contourPoints,
                        leftEyeContourPoints = leftEyeContourPoints,
                        rightEyeContourPoints = rightEyeContourPoints,
                        lipOuterContourPoints = lipOuterContourPoints,
                        lipInnerContourPoints = lipInnerContourPoints,
                        leftCheekContourPoints = leftCheekContourPoints,
                        rightCheekContourPoints = rightCheekContourPoints,
                        allContours = allContours
                    )
                    onFaceWarpParamsChanged(faceWarpParams)
                } else {
                    onShowFocusIndicatorChanged(false)
                    onFaceWarpParamsChanged(FaceWarpParams())
                }
            }
            .addOnFailureListener { error ->
                android.util.Log.e("PicMe:Camera", "Face detection failed: ${error.message}", error)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } catch (error: Exception) {
        Logger.e("Camera", "Face detection error", error)
        imageProxy.close()
    }
}

private fun averagePoint(points: List<PointF>): PointF? {
    if (points.isEmpty()) {
        return null
    }

    val sumX = points.sumOf { point -> point.x.toDouble() }.toFloat()
    val sumY = points.sumOf { point -> point.y.toDouble() }.toFloat()
    return PointF(sumX / points.size, sumY / points.size)
}

private fun resampleContourPoints(points: List<PointF>): List<PointF> {
    if (points.size < 2 || LIP_CONTOUR_POINT_COUNT <= 1) {
        return points
    }

    val source = points + points.first()

    val cumulative = ArrayList<Float>(source.size)
    cumulative.add(0f)
    for (index in 1 until source.size) {
        val dx = source[index].x - source[index - 1].x
        val dy = source[index].y - source[index - 1].y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        cumulative.add(cumulative.last() + distance)
    }

    val totalLength = cumulative.last()
    if (totalLength <= 0.0001f) {
        return List(LIP_CONTOUR_POINT_COUNT) { points.first() }
    }

    val sampleCount = LIP_CONTOUR_POINT_COUNT
    val step = totalLength / sampleCount

    val result = ArrayList<PointF>(sampleCount)
    var segmentIndex = 1
    for (sampleIndex in 0 until sampleCount) {
        val targetDistance = sampleIndex * step
        while (segmentIndex < cumulative.size - 1 && cumulative[segmentIndex] < targetDistance) {
            segmentIndex++
        }

        val prevDistance = cumulative[segmentIndex - 1]
        val nextDistance = cumulative[segmentIndex]
        val distanceRange = (nextDistance - prevDistance).coerceAtLeast(0.0001f)
        val t = ((targetDistance - prevDistance) / distanceRange).coerceIn(0f, 1f)

        val start = source[segmentIndex - 1]
        val end = source[segmentIndex]
        result.add(
            PointF(
                start.x + (end.x - start.x) * t,
                start.y + (end.y - start.y) * t
            )
        )
    }

    return result
}

/**
 * 将 ML Kit 人脸坐标转换为纹理归一化坐标（0~1），直接对应 Shader 纹理坐标系。
 *
 * 纹理坐标系定义：
 * - (0,0) = 纹理左上角
 * - (1,1) = 纹理右下角
 * - 基于相机输入帧旋转后的尺寸（与 Shader 纹理一致）
 *
 * @return Offset(x, y) 其中 x 和 y 都在 [0, 1] 范围内
 */
internal fun transformFaceCoordinateToNormalized(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight

    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)
        90 -> Pair(mirroredX, normY)
        180 -> Pair(1f - mirroredX, 1f - normY)
        270 -> Pair(mirroredX, normY)
        else -> Pair(mirroredX, normY)
    }

    return Offset(
        x = adjustedX.coerceIn(0f, 1f),
        y = adjustedY.coerceIn(0f, 1f)
    )
}

/**
 * 将 ML Kit 人脸坐标转换为 PreviewView 屏幕像素坐标（用于调试UI绘制）。
 *
 * @deprecated 新代码应优先使用 [transformFaceCoordinateToNormalized]，
 *             调试UI绘制时自行将归一化坐标映射到屏幕。
 */
internal fun transformFaceCoordinateSimple(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val normPoint = transformFaceCoordinateToNormalized(
        faceX = faceX,
        faceY = faceY,
        imageProxyWidth = imageProxyWidth,
        imageProxyHeight = imageProxyHeight,
        rotationDegrees = rotationDegrees,
        lensFacing = lensFacing
    )
    return Offset(
        x = normPoint.x * previewWidth,
        y = normPoint.y * previewHeight
    )
}

