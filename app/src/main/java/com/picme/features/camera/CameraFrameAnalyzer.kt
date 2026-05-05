package com.picme.features.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import com.picme.core.common.Logger
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.features.camera.facedetect.Face106ToWarpParams
import com.picme.features.camera.facedetect.FaceDetectorManager
import com.picme.features.camera.preview.core.FaceWarpParams

/**
 * InsightFace 性能优化: 帧跳过计数器
 * 每 N 帧检测一次人脸,降低 ONNX 推理频率
 */
private object FaceDetectionFrameCounter {
    private var counter = 0
    private const val DETECTION_INTERVAL = 5  // 每 5 帧检测一次 (30fps -> 6fps)
    
    fun shouldDetect(): Boolean {
        counter++
        return counter % DETECTION_INTERVAL == 0
    }
    
    fun reset() {
        counter = 0
    }
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

/**
 * MediaPipe 版本的人脸分析帧处理
 * 使用 MediaPipe Face Landmarker 检测 468 点，映射为 106 点
 *
 * @param imageProxy CameraX ImageProxy
 * @param previewView 预览视图（用于调试UI坐标映射）
 * @param faceDetectorManager 人脸检测管理器（支持多引擎）
 * @param lensFacing 镜头方向
 * @param onFacePointChanged 人脸中心点回调（屏幕坐标，用于聚焦指示器）
 * @param onFaceWarpParamsChanged FaceWarpParams 回调
 * @param onShowFocusIndicatorChanged 聚焦指示器显示回调
 */
@ExperimentalGetImage
internal fun handleImageAnalysisFrameMediaPipe(
    imageProxy: androidx.camera.core.ImageProxy,
    previewView: PreviewView,
    faceDetectorManager: FaceDetectorManager,
    lensFacing: Int,
    detectionEngineMode: FaceDetectionEngineMode,
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

        // [方案1] InsightFace 性能优化: 降低检测频率
        val isInsightFaceMode = detectionEngineMode == FaceDetectionEngineMode.INSIGHTFACE
        val shouldSkipDetection = isInsightFaceMode && 
                                   !FaceDetectionFrameCounter.shouldDetect() && 
                                   existingWarpParams?.hasFace == true
        
        if (shouldSkipDetection) {
            // 跳过检测,复用上一帧的结果
            Logger.d("Camera", "[Perf] Skip frame, reuse previous detection")
            onFaceWarpParamsChanged(existingWarpParams)
            imageProxy.close()
            return
        }

        // 人脸检测（MediaPipe / InsightFace / AUTO）
        val detectionResult = faceDetectorManager.detect(imageProxy, lensFacing)

        if (detectionResult != null) {
            val landmarks106 = detectionResult.landmarks106
            // 缓存人脸检测结果供拍照使用
            FaceDetectionCache.updateLandmarks106(landmarks106)

            // 构建 FaceWarpParams
            val faceWarpParams = Face106ToWarpParams.convert(
                landmarks106 = landmarks106,
                detectionSource = detectionResult.detectionSource
            ).copy(
                requestedDetectionEngineMode = detectionEngineMode,
                roiRect = detectionResult.roiRect  // [新增] 传递 ROI
            )

            // 人脸中心点（用于聚焦指示器）
            val screenPoint = Offset(
                x = faceWarpParams.faceCenterX * previewWidth,
                y = faceWarpParams.faceCenterY * previewHeight
            )
            onFacePointChanged(screenPoint)
            onShowFocusIndicatorChanged(true)

            if (isDualMode) {
                // 双模式下仍需保留主分析流的完整美颜参数，
                // 保留已有的人脸关键点，避免把轮廓/中心点清空后触发预览黑屏。
                onFaceWarpParamsChanged(faceWarpParams)
            } else {
                // 单模式：直接返回大美丽参数
                onFaceWarpParamsChanged(faceWarpParams)
            }
        } else {
            if (!isDualMode) {
                onShowFocusIndicatorChanged(false)
                onFaceWarpParamsChanged(
                    FaceWarpParams(requestedDetectionEngineMode = detectionEngineMode)
                )
            }
        }

        imageProxy.close()
    } catch (error: Exception) {
        Logger.e("Camera", "MediaPipe face detection error", error)
        imageProxy.close()
    }
}

