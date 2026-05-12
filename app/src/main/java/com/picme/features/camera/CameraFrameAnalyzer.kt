package com.picme.features.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import com.picme.core.common.Logger
import com.picme.beauty.api.FrameId
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.FaceWarpParams
import com.picme.beauty.internal.facedetect.Face106ToWarpParams
import com.picme.beauty.internal.framesync.FrameSyncBridge
import com.picme.beauty.internal.framesync.FrameSyncManager
import com.picme.features.camera.facedetect.ImageUtils

/**
 * InsightFace 性能优化: 智能帧跳过管理器
 * 平衡性能与响应速度,避免人脸跟随延迟
 */
private object FaceDetectionFrameCounter {
    private var counter = 0
    private const val DETECTION_INTERVAL = 3  // [优化] 每 3 帧检测一次 (30fps -> 10fps)
    
    // 运动检测: 记录上次人脸中心点
    private var lastFaceCenterX: Float = -1f
    private var lastFaceCenterY: Float = -1f
    private const val MOTION_THRESHOLD = 0.05f  // 归一化坐标变化超过 5% 视为快速移动
    
    /**
     * 判断是否应该执行人脸检测
     * @param currentFaceCenter 当前人脸中心(归一化坐标),如果为 null 表示未检测到人脸
     * @return true=需要检测, false=可以跳过
     */
    fun shouldDetect(currentFaceCenter: Offset? = null): Boolean {
        counter++
        
        // 规则1: 每隔 N 帧必须检测一次
        if (counter % DETECTION_INTERVAL == 0) {
            return true
        }
        
        // 规则2: 如果从未检测到人脸,需要检测
        if (currentFaceCenter == null || lastFaceCenterX < 0) {
            return true
        }
        
        // 规则3: 检测快速移动,立即重新检测
        val deltaX = kotlin.math.abs(currentFaceCenter.x - lastFaceCenterX)
        val deltaY = kotlin.math.abs(currentFaceCenter.y - lastFaceCenterY)
        if (deltaX > MOTION_THRESHOLD || deltaY > MOTION_THRESHOLD) {
            Logger.d("Camera", "[Perf] Fast motion detected (dx=$deltaX, dy=$deltaY), force re-detect")
            return true
        }
        
        return false
    }
    
    /**
     * 更新人脸中心点记录
     */
    fun updateLastFaceCenter(x: Float, y: Float) {
        lastFaceCenterX = x
        lastFaceCenterY = y
    }
    
    fun reset() {
        counter = 0
        lastFaceCenterX = -1f
        lastFaceCenterY = -1f
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
 * [帧同步] 停止全局 FaceDetectionWorker
 * （供 CameraPreviewRenderer.release() 调用，兼容清理残留 Worker）
 */
internal fun stopFaceDetectionWorker() {
    // Phase 1 已移除异步检测 Worker，此处保留空实现供外部兼容调用
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
    faceDetector: FaceDetector,
    lensFacing: Int,
    detectionEngineMode: EngineType,
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

        // [方案1优化] InsightFace 性能优化: 智能帧跳过 + 运动检测
        val isInsightFaceMode = detectionEngineMode == EngineType.INSIGHTFACE

        // 获取上次的人脸中心点用于运动检测
        val lastFaceCenter = existingWarpParams?.let {
            Offset(it.faceCenterX, it.faceCenterY)
        }

        val shouldSkipDetection = isInsightFaceMode &&
                                   !FaceDetectionFrameCounter.shouldDetect(lastFaceCenter) &&
                                   existingWarpParams?.hasFace == true

        if (shouldSkipDetection) {
            // 跳过检测,复用上一帧的结果
            Logger.d("Camera", "[Perf] Skip frame")
            onFaceWarpParamsChanged(existingWarpParams)
            imageProxy.close()
            return
        }

        // [帧同步] 同步检测路径：直接检测并将结果存入 FrameSyncManager
        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        // [帧同步 CR-P0-1] 获取渲染线程的最新 FrameId，确保检测-渲染使用同一套 ID
        val frameId = FrameSyncBridge.getLatestFrameId().takeIf { it != FrameId.INVALID } ?: FrameId.next()

        val detectionResult = faceDetector.detect(bitmap, 0, lensFacing)
        // [帧同步 CR-P0-3] 回收 bitmap，避免内存泄漏
        bitmap.recycle()

        if (detectionResult != null) {
            val landmarks106 = detectionResult.landmarks106
            FaceDetectionCache.updateLandmarks106(landmarks106)

            // [帧同步 CR-P0-2] 同步结果也存入 FrameSyncManager，供渲染线程查询
            FrameSyncManager.getInstance().storeResult(
                FrameSyncManager.DetectionResult(
                    frameId = frameId,
                    landmarks106 = landmarks106.clone(),
                    detectionSource = detectionResult.detectionSource,
                    detectionLatencyMs = 0L
                )
            )

            val faceWarpParams = Face106ToWarpParams.convert(
                landmarks106 = landmarks106,
                detectionSource = detectionResult.detectionSource
            ).copy(
                requestedDetectionEngineMode = detectionEngineMode,
                roiRect = detectionResult.roiRect
            )

            FaceDetectionFrameCounter.updateLastFaceCenter(
                faceWarpParams.faceCenterX,
                faceWarpParams.faceCenterY
            )

            val screenPoint = Offset(
                x = faceWarpParams.faceCenterX * previewWidth,
                y = faceWarpParams.faceCenterY * previewHeight
            )
            onFacePointChanged(screenPoint)
            onShowFocusIndicatorChanged(true)

            if (isDualMode) {
                onFaceWarpParamsChanged(faceWarpParams)
            } else {
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

