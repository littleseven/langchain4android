package com.picme.features.camera

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
                android.util.Log.d("PicMe:Camera", "Face detection success: ${faces.size} faces found")
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox
                    val previewWidth = fallbackPreviewWidth
                    val previewHeight = fallbackPreviewHeight
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                    val screenPoint = transformFaceCoordinateSimple(
                        faceX = bounds.centerX().toFloat(),
                        faceY = bounds.centerY().toFloat(),
                        imageProxyWidth = imageProxy.width,
                        imageProxyHeight = imageProxy.height,
                        previewWidth = previewWidth,
                        previewHeight = previewHeight,
                        rotationDegrees = rotationDegrees,
                        lensFacing = lensFacing
                    )
                    onFacePointChanged(screenPoint)
                    onShowFocusIndicatorChanged(true)

                    val leftEyeLandmark = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                    val leftEyePoint = if (leftEyeLandmark != null) {
                        transformFaceCoordinateSimple(
                            faceX = leftEyeLandmark.x,
                            faceY = leftEyeLandmark.y,
                            imageProxyWidth = imageProxy.width,
                            imageProxyHeight = imageProxy.height,
                            previewWidth = previewWidth,
                            previewHeight = previewHeight,
                            rotationDegrees = rotationDegrees,
                            lensFacing = lensFacing
                        )
                    } else {
                        Offset(
                            screenPoint.x - bounds.width().toFloat() * 0.16f,
                            screenPoint.y - bounds.height().toFloat() * 0.10f
                        )
                    }

                    val rightEyePoint = if (rightEyeLandmark != null) {
                        transformFaceCoordinateSimple(
                            faceX = rightEyeLandmark.x,
                            faceY = rightEyeLandmark.y,
                            imageProxyWidth = imageProxy.width,
                            imageProxyHeight = imageProxy.height,
                            previewWidth = previewWidth,
                            previewHeight = previewHeight,
                            rotationDegrees = rotationDegrees,
                            lensFacing = lensFacing
                        )
                    } else {
                        Offset(
                            screenPoint.x + bounds.width().toFloat() * 0.16f,
                            screenPoint.y - bounds.height().toFloat() * 0.10f
                        )
                    }

                    val faceRadius = (
                        maxOf(bounds.width().toFloat() / imageProxy.width.toFloat(), 0.16f)
                    ).coerceIn(0.12f, 0.38f)

                    val contourPoints = face.getContour(FaceContour.FACE)?.points
                        ?.map { contourPoint ->
                            val mappedPoint = transformFaceCoordinateSimple(
                                faceX = contourPoint.x,
                                faceY = contourPoint.y,
                                imageProxyWidth = imageProxy.width,
                                imageProxyHeight = imageProxy.height,
                                previewWidth = previewWidth,
                                previewHeight = previewHeight,
                                rotationDegrees = rotationDegrees,
                                lensFacing = lensFacing
                            )
                            Offset(
                                x = (mappedPoint.x / previewWidth).coerceIn(0f, 1f),
                                y = (mappedPoint.y / previewHeight).coerceIn(0f, 1f)
                            )
                        }
                        ?: emptyList()

                    val leftEyeContourPoints = face.getContour(FaceContour.LEFT_EYE)?.points
                        ?.map { contourPoint ->
                            val mappedPoint = transformFaceCoordinateSimple(
                                faceX = contourPoint.x,
                                faceY = contourPoint.y,
                                imageProxyWidth = imageProxy.width,
                                imageProxyHeight = imageProxy.height,
                                previewWidth = previewWidth,
                                previewHeight = previewHeight,
                                rotationDegrees = rotationDegrees,
                                lensFacing = lensFacing
                            )
                            Offset(
                                x = (mappedPoint.x / previewWidth).coerceIn(0f, 1f),
                                y = (mappedPoint.y / previewHeight).coerceIn(0f, 1f)
                            )
                        }
                        ?: emptyList()

                    val rightEyeContourPoints = face.getContour(FaceContour.RIGHT_EYE)?.points
                        ?.map { contourPoint ->
                            val mappedPoint = transformFaceCoordinateSimple(
                                faceX = contourPoint.x,
                                faceY = contourPoint.y,
                                imageProxyWidth = imageProxy.width,
                                imageProxyHeight = imageProxy.height,
                                previewWidth = previewWidth,
                                previewHeight = previewHeight,
                                rotationDegrees = rotationDegrees,
                                lensFacing = lensFacing
                            )
                            Offset(
                                x = (mappedPoint.x / previewWidth).coerceIn(0f, 1f),
                                y = (mappedPoint.y / previewHeight).coerceIn(0f, 1f)
                            )
                        }
                        ?: emptyList()

                    val faceWarpParams = FaceWarpParams(
                        faceCenterX = (screenPoint.x / previewWidth).coerceIn(0f, 1f),
                        faceCenterY = (screenPoint.y / previewHeight).coerceIn(0f, 1f),
                        leftEyeX = (leftEyePoint.x / previewWidth).coerceIn(0f, 1f),
                        leftEyeY = (leftEyePoint.y / previewHeight).coerceIn(0f, 1f),
                        rightEyeX = (rightEyePoint.x / previewWidth).coerceIn(0f, 1f),
                        rightEyeY = (rightEyePoint.y / previewHeight).coerceIn(0f, 1f),
                        faceRadius = faceRadius,
                        hasFace = true,
                        contourPoints = contourPoints,
                        leftEyeContourPoints = leftEyeContourPoints,
                        rightEyeContourPoints = rightEyeContourPoints
                    )
                    onFaceWarpParamsChanged(faceWarpParams)
                    android.util.Log.d(
                        "PicMe:Camera",
                        "Face warp params updated: center=(${faceWarpParams.faceCenterX},${faceWarpParams.faceCenterY}), " +
                            "radius=${faceWarpParams.faceRadius}, hasFace=${faceWarpParams.hasFace}"
                    )
                } else {
                    onShowFocusIndicatorChanged(false)
                    onFaceWarpParamsChanged(FaceWarpParams())
                }
            }
            .addOnFailureListener { error ->
                android.util.Log.e("PicMe:Camera", "Face detection failed: ${error.message}", error)
            }
            .addOnCompleteListener {
                if (beautySettings.enabled && beautySettings.hasAnyEffect()) {
                    Logger.d("Camera", "Beauty enabled, will apply on capture")
                }
                imageProxy.close()
            }
    } catch (error: Exception) {
        Logger.e("Camera", "Face detection error", error)
        imageProxy.close()
    }
}

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
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight

    android.util.Log.d(
        "PicMe:Camera",
        "Step1 [归一化]: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, norm=($normX,$normY)"
    )

    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }

    android.util.Log.d(
        "PicMe:Camera",
        "Step2 [镜像]: lens=${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前" else "后"}, norm=($normX,$normY), mirrored=($mirroredX,$normY)"
    )

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)
        90 -> Pair(mirroredX, normY)
        180 -> Pair(1f - mirroredX, 1f - normY)
        270 -> Pair(mirroredX, normY)
        else -> Pair(mirroredX, normY)
    }

    android.util.Log.d(
        "PicMe:Camera",
        "Step3 [旋转补偿]: rot=$rotationDegrees, mirrored=($mirroredX,$normY), adjusted=($adjustedX,$adjustedY)"
    )

    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight

    android.util.Log.d(
        "PicMe:Camera",
        "Step4 [像素转换]: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}, screen=($screenX,$screenY)"
    )

    return Offset(screenX, screenY)
}

