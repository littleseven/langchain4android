package com.picme.features.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import android.os.SystemClock
import com.picme.core.common.Logger
import com.picme.beauty.api.FrameId
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.FaceWarpParams
import com.picme.beauty.internal.facedetect.Face106ToWarpParams
import com.picme.beauty.internal.framesync.FrameSyncBridge
import com.picme.beauty.internal.framesync.FrameSyncManager
import com.picme.features.camera.facedetect.ImageUtils
import androidx.camera.core.ImageProxy
import com.picme.domain.model.FaceDetectIntervalProfile

/**
 * InsightFace 性能优化: 智能帧跳过管理器
 * 平衡性能与响应速度,避免人脸跟随延迟
 */
private object FaceDetectionFrameCounter {
    // [默认均衡档] 用户未配置时默认使用 BALANCED。
    private const val BALANCED_DETECTION_INTERVAL = 3
    private const val BALANCED_MIN_INTERVAL_MS = 100L
    private const val BALANCED_MOTION_THRESHOLD = 0.05f
    private const val BALANCED_MAX_CONSECUTIVE_MOTION = 2

    private var counter = 0
    private var detectionIntervalFrames = BALANCED_DETECTION_INTERVAL
    private var minDetectionIntervalMs = BALANCED_MIN_INTERVAL_MS
    private var motionThreshold = BALANCED_MOTION_THRESHOLD
    private var maxConsecutiveMotion = BALANCED_MAX_CONSECUTIVE_MOTION

    private var adaptiveEnabled = true
    private var activeProfile = FaceDetectIntervalProfile.BALANCED

    // [过热修复] 时间冷却：最小检测间隔，防止检测线程持续满载导致发热
    private var lastDetectTimeMs: Long = 0L

    // [过热修复] 检测进行中标志，防止 Handler 队列积压导致重复检测排队
    @Volatile
    private var isDetecting = false

    // 运动检测: 记录上次人脸中心点
    private var lastFaceCenterX: Float = -1f
    private var lastFaceCenterY: Float = -1f

    // [过热修复] 连续运动检测计数器，防止快移场景下每帧都触发检测
    private var consecutiveMotionDetects = 0
    private var lastDetectionTriggeredByMotion = false

    // [常量定义] 人脸关键点索引（106 点模型）
    internal const val LANDMARK_106_INDEX_X = 48
    internal const val LANDMARK_106_INDEX_Y = 97

    fun applyPolicy(enabled: Boolean, profile: FaceDetectIntervalProfile) {
        val targetProfile = if (enabled) profile else FaceDetectIntervalProfile.BALANCED
        if (adaptiveEnabled == enabled && activeProfile == targetProfile) {
            return
        }

        adaptiveEnabled = enabled
        activeProfile = targetProfile

        when (targetProfile) {
            FaceDetectIntervalProfile.CONSERVATIVE -> {
                detectionIntervalFrames = 5
                minDetectionIntervalMs = 160L
                motionThreshold = 0.09f
                maxConsecutiveMotion = 1
            }
            FaceDetectIntervalProfile.BALANCED -> {
                detectionIntervalFrames = BALANCED_DETECTION_INTERVAL
                minDetectionIntervalMs = BALANCED_MIN_INTERVAL_MS
                motionThreshold = BALANCED_MOTION_THRESHOLD
                maxConsecutiveMotion = BALANCED_MAX_CONSECUTIVE_MOTION
            }
            FaceDetectIntervalProfile.AGGRESSIVE -> {
                detectionIntervalFrames = 2
                minDetectionIntervalMs = 70L
                motionThreshold = 0.035f
                maxConsecutiveMotion = 3
            }
        }

        Logger.i(
            "Camera",
            "Face detection policy applied: adaptive=$enabled, profile=$targetProfile, " +
                "interval=$detectionIntervalFrames, minIntervalMs=$minDetectionIntervalMs, " +
                "motionThreshold=$motionThreshold, maxMotion=$maxConsecutiveMotion"
        )
    }

    /**
     * 判断是否应该执行人脸检测
     * @param currentFaceCenter 当前人脸中心(归一化坐标),如果为 null 表示未检测到人脸
     * @return true=需要检测, false=可以跳过
     */
    fun shouldDetect(currentFaceCenter: Offset? = null): Boolean {
        counter++

        // [过热修复-1] 时间冷却：距离上次检测不足 minDetectionIntervalMs 时直接跳过
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectTimeMs < minDetectionIntervalMs) {
            return false
        }

        // [过热修复-2] 检测进行中时跳过，防止 Handler 队列积压导致背靠背检测
        if (isDetecting) {
            Logger.dThrottled("Camera", "skip_detecting", "[Perf] Skip frame: detection in progress")
            return false
        }

        // 规则1: 每隔 N 帧必须检测一次
        if (counter % detectionIntervalFrames == 0) {
            lastDetectionTriggeredByMotion = false
            return true
        }

        // 规则2: 如果从未检测到人脸,需要检测（但受时间冷却约束）
        if (currentFaceCenter == null || lastFaceCenterX < 0) {
            lastDetectionTriggeredByMotion = false
            return true
        }

        // 规则3: 检测快速移动,但限制连续运动检测次数防止过热
        val deltaX = kotlin.math.abs(currentFaceCenter.x - lastFaceCenterX)
        val deltaY = kotlin.math.abs(currentFaceCenter.y - lastFaceCenterY)
        if (deltaX > motionThreshold || deltaY > motionThreshold) {
            if (consecutiveMotionDetects < maxConsecutiveMotion) {
                Logger.d("Camera", "[Perf] Fast motion detected (dx=$deltaX, dy=$deltaY), force re-detect")
                lastDetectionTriggeredByMotion = true
                return true
            }
            // 超过连续运动检测上限，退回间隔模式冷却
            lastDetectionTriggeredByMotion = false
            return false
        }

        // 运动停止时重置连续运动计数
        consecutiveMotionDetects = 0
        lastDetectionTriggeredByMotion = false

        return false
    }

    /**
     * 标记检测开始（由分析线程在 YUV 转换前调用）
     */
    fun markDetectionStart() {
        isDetecting = true
    }

    /**
     * 标记检测完成（由分析线程在推理和坐标转换完成后调用）
     */
    fun markDetectionComplete() {
        isDetecting = false
        lastDetectTimeMs = SystemClock.elapsedRealtime()
        if (lastDetectionTriggeredByMotion) {
            consecutiveMotionDetects++
        } else {
            consecutiveMotionDetects = 0
        }
        lastDetectionTriggeredByMotion = false
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
        lastDetectTimeMs = 0L
        isDetecting = false
        consecutiveMotionDetects = 0
        lastDetectionTriggeredByMotion = false

        // 重置回默认均衡档
        adaptiveEnabled = true
        activeProfile = FaceDetectIntervalProfile.BALANCED
        detectionIntervalFrames = BALANCED_DETECTION_INTERVAL
        minDetectionIntervalMs = BALANCED_MIN_INTERVAL_MS
        motionThreshold = BALANCED_MOTION_THRESHOLD
        maxConsecutiveMotion = BALANCED_MAX_CONSECUTIVE_MOTION
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
 * @param adaptiveFaceDetectionIntervalEnabled 是否启用动态检测间隔
 * @param faceDetectIntervalProfile 动态检测间隔档位（默认均衡）
 * @param beautyEnabled 美颜是否启用，未启用时跳过人脸检测以节省性能
 */
@ExperimentalGetImage
internal fun handleImageAnalysisFrameMediaPipe(
    imageProxy: ImageProxy,
    previewView: PreviewView,
    faceDetector: FaceDetector,
    lensFacing: Int,
    detectionEngineMode: EngineType,
    adaptiveFaceDetectionIntervalEnabled: Boolean = true,
    faceDetectIntervalProfile: FaceDetectIntervalProfile = FaceDetectIntervalProfile.BALANCED,
    showFaceDebugOverlay: Boolean = false,
    onFacePointChanged: (Offset) -> Unit,
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit,
    existingWarpParams: FaceWarpParams? = null,
    beautyEnabled: Boolean = false
) {
    try {
        // 美颜未启用时，跳过人脸检测
        if (!beautyEnabled) {
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            return
        }

        val previewWidth = previewView.width.takeIf { width -> width > 0 }?.toFloat()
            ?: imageProxy.width.toFloat()
        val previewHeight = previewView.height.takeIf { height -> height > 0 }?.toFloat()
            ?: imageProxy.height.toFloat()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 根据设置应用检测策略（默认均衡档）
        FaceDetectionFrameCounter.applyPolicy(
            enabled = adaptiveFaceDetectionIntervalEnabled,
            profile = faceDetectIntervalProfile
        )

        // 获取上次的人脸中心点用于运动检测
        val lastFaceCenter = existingWarpParams?.let {
            Offset(it.faceCenterX, it.faceCenterY)
        }

        val shouldDetect = FaceDetectionFrameCounter.shouldDetect(lastFaceCenter)
        val shouldSkipDetection = !shouldDetect && existingWarpParams?.hasFace == true

        // [帧同步 CR-P0-3] 使用 ImageProxy 时间戳精确查询对应的 FrameId。
        // ImageProxy.imageInfo.timestamp 与 SurfaceTexture.timestamp 共享相机硬件时间基准。
        // 通过 FrameSyncBridge.getFrameIdByTimestamp() 精确关联检测输入帧与渲染帧，
        // 避免检测结果绑定到错误的 FrameId 导致 query() 时预测补偿产生甩飞。
        val imageTimestampNs = imageProxy.imageInfo.timestamp
        val frameIdByTimestamp = FrameSyncBridge.getFrameIdByTimestamp(imageTimestampNs)
        val frameId = if (frameIdByTimestamp != FrameId.INVALID) {
            frameIdByTimestamp
        } else {
            // 回退：时间戳映射未就绪时（启动期），使用最新 FrameId
            FrameSyncBridge.getLatestFrameId().takeIf { it != FrameId.INVALID } ?: FrameId.next()
        }

        if (shouldSkipDetection) {
            // 跳过检测,复用上一帧的结果
            Logger.dThrottled("Camera", "skip_frame", "[Perf] Skip frame")
            onFaceWarpParamsChanged(existingWarpParams)

            // [帧同步关键修复] 跳帧时也必须向 FrameSyncManager 存储结果，
            // 否则渲染线程查询这些帧时会得到 MISSING，导致妆容闪烁/消失。
            // 使用缓存的最新 landmarks 和当前 frameId 存储"复用结果"。
            val cachedLandmarks = FaceDetectionCache.getCachedLandmarks106()
            if (cachedLandmarks != null && existingWarpParams?.hasFace == true) {
                FrameSyncManager.getInstance().storeResult(
                    FrameSyncManager.DetectionResult(
                        frameId = frameId,
                        landmarks106 = cachedLandmarks,
                        detectionSource = existingWarpParams.detectionSource,
                        detectionLatencyMs = 0L
                    )
                )
                Logger.dThrottled("Camera", "stored_skip", "[FrameSync] Stored skip-frame result for frameId=$frameId")
            }
            return
        }

        // [过热修复] 标记检测开始，防止后续帧排队检测
        FaceDetectionFrameCounter.markDetectionStart()

        val detectionStartMs = System.currentTimeMillis()

        // [GPU 检测优化] YUV→Bitmap 零 JPEG 路径
        val yuvStart = SystemClock.elapsedRealtime()
        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
        val yuvElapsed = SystemClock.elapsedRealtime() - yuvStart
        if (bitmap == null) {
            // [过热修复] Bitmap 转换失败也需标记检测完成，防止 isDetecting 永久卡住
            FaceDetectionFrameCounter.markDetectionComplete()
            return
        }
        Logger.dThrottled("Camera", "yuv_bitmap", "[Perf] YUV→Bitmap: ${yuvElapsed}ms, size=${bitmap.width}x${bitmap.height}")
        // [性能优化] 不要 recycle！ImageUtils 内部复用此 Bitmap，recycle 会导致每帧重新分配
        val detectionResult = faceDetector.detect(bitmap, 0, lensFacing)

        if (detectionResult != null) {
            val landmarks106 = detectionResult.landmarks106
            FaceDetectionCache.updateLandmarks106(landmarks106)

            // [调试信息] 显示检测器类型和 GPU 状态
            if (showFaceDebugOverlay) {
                val detectorInfo = buildString {
                    append("ROI: ${detectionResult.roiDetectorName} ")
                    append(if (detectionResult.useGpuForRoi) "GPU✓" else "CPU")
                    append(" | Landmark: ${detectionResult.landmarkDetectorName} ")
                    append(if (detectionResult.useGpuForLandmark) "GPU✓" else "CPU")
                }
                // 从 landmarks 计算人脸中心点
                val centerX = landmarks106[FaceDetectionFrameCounter.LANDMARK_106_INDEX_X] // 索引 96 (第 49 个点 x 坐标)
                val centerY = landmarks106[FaceDetectionFrameCounter.LANDMARK_106_INDEX_Y] // 索引 97 (第 49 个点 y 坐标)
                Logger.d("Camera", "[Debug] $detectorInfo, faceCenter=($centerX, $centerY)")
            }

            // [帧同步 CR-P0-2] 同步结果也存入 FrameSyncManager，供渲染线程查询
            val detectionLatencyMs = System.currentTimeMillis() - detectionStartMs
            FrameSyncManager.getInstance().storeResult(
                FrameSyncManager.DetectionResult(
                    frameId = frameId,
                    landmarks106 = landmarks106.clone(),
                    detectionSource = detectionResult.detectionSource,
                    detectionLatencyMs = detectionLatencyMs
                )
            )
            Logger.dThrottled("Camera", "stored_result", "[FrameSync] Stored result for frameId=$frameId, landmarks=${landmarks106.size}")

            val convertStart = SystemClock.elapsedRealtime()
            val faceWarpParams = Face106ToWarpParams.convert(
                landmarks106 = landmarks106,
                detectionSource = detectionResult.detectionSource
            ).copy(
                requestedDetectionEngineMode = detectionEngineMode,
                roiRect = detectionResult.roiRect,
                roiDetectorName = detectionResult.roiDetectorName,
                useGpuForRoi = detectionResult.useGpuForRoi,
                landmarkDetectorName = detectionResult.landmarkDetectorName,
                useGpuForLandmark = detectionResult.useGpuForLandmark
            )
            val convertElapsed = SystemClock.elapsedRealtime() - convertStart
            Logger.dThrottled("Camera", "convert_time", "[Perf] Face106ToWarpParams.convert: ${convertElapsed}ms")

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

            onFaceWarpParamsChanged(faceWarpParams)

            // [过热修复] 标记检测完成，更新时间戳和运动计数
            FaceDetectionFrameCounter.markDetectionComplete()
        } else {
            onShowFocusIndicatorChanged(false)
            onFaceWarpParamsChanged(
                FaceWarpParams(requestedDetectionEngineMode = detectionEngineMode)
            )

            // [过热修复] 检测完成（即使未检测到人脸也更新冷却时间）
            FaceDetectionFrameCounter.markDetectionComplete()
        }
    } catch (error: Exception) {
        Logger.e("Camera", "MediaPipe face detection error", error)
        // [过热修复] 异常时重置检测状态，防止 isDetecting 永久卡住
        FaceDetectionFrameCounter.markDetectionComplete()
    } finally {
        imageProxy.close()
    }
}

