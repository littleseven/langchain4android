package com.picme.features.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import com.picme.core.common.Logger
import com.picme.features.camera.preview.core.FaceWarpParams

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

/**
 * MediaPipe 版本的人脸分析帧处理
 * 使用 MediaPipe Face Landmarker 检测 468 点，映射为 106 点
 *
 * @param imageProxy CameraX ImageProxy
 * @param previewView 预览视图（用于调试UI坐标映射）
 * @param mediaPipeDetector MediaPipe 人脸检测器
 * @param lensFacing 镜头方向
 * @param onFacePointChanged 人脸中心点回调（屏幕坐标，用于聚焦指示器）
 * @param onFaceWarpParamsChanged FaceWarpParams 回调
 * @param onShowFocusIndicatorChanged 聚焦指示器显示回调
 */
@ExperimentalGetImage
internal fun handleImageAnalysisFrameMediaPipe(
    imageProxy: androidx.camera.core.ImageProxy,
    previewView: PreviewView,
    mediaPipeDetector: com.picme.features.camera.facedetect.MediaPipeFaceDetector,
    lensFacing: Int,
    onFacePointChanged: (Offset) -> Unit,
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit,
    isDualMode: Boolean = false,
    existingWarpParams: FaceWarpParams? = null
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val previewWidth = previewView.width.takeIf { width -> width > 0 }?.toFloat()
            ?: imageProxy.width.toFloat()
        val previewHeight = previewView.height.takeIf { height -> height > 0 }?.toFloat()
            ?: imageProxy.height.toFloat()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // MediaPipe 检测
        val landmarks106 = mediaPipeDetector.detect(imageProxy, lensFacing)

        if (landmarks106 != null) {
            // 缓存人脸检测结果供拍照使用
            FaceDetectionCache.updateLandmarks106(landmarks106)

            // 构建 FaceWarpParams
            val faceWarpParams = com.picme.features.camera.facedetect.Face106ToWarpParams.convert(landmarks106)

            // 人脸中心点（用于聚焦指示器）
            val screenPoint = Offset(
                x = faceWarpParams.faceCenterX * previewWidth,
                y = faceWarpParams.faceCenterY * previewHeight
            )
            onFacePointChanged(screenPoint)
            onShowFocusIndicatorChanged(true)

            // 保存大美丽原始点位用于调试对比
            val bigBeautyLandmarks = com.picme.features.camera.preview.core.GpuPixelLandmarks.fromFloatArray(landmarks106)

            if (isDualMode) {
                // 双模式：只返回 MediaPipe 的 bigBeautyLandmarks 部分
                // GPUPixel 的 gpuPixelLandmarks 由 CameraScreen 中的 onGpuPixelLandmarksDetected 回调单独更新
                val dualModeParams = FaceWarpParams(
                    bigBeautyLandmarks = bigBeautyLandmarks,
                    hasFace = faceWarpParams.hasFace
                )
                onFaceWarpParamsChanged(dualModeParams)
            } else {
                // 单模式：直接返回大美丽参数
                val warpParamsWithBigBeauty = faceWarpParams.copy(
                    bigBeautyLandmarks = bigBeautyLandmarks
                )
                onFaceWarpParamsChanged(warpParamsWithBigBeauty)
            }
        } else {
            if (!isDualMode) {
                onShowFocusIndicatorChanged(false)
                onFaceWarpParamsChanged(FaceWarpParams())
            }
        }

        imageProxy.close()
    } catch (error: Exception) {
        Logger.e("Camera", "MediaPipe face detection error", error)
        imageProxy.close()
    }
}

