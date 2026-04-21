@file:Suppress("OPT_IN_USAGE_ERROR")

package com.picme.features.camera
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

import com.picme.R
import com.picme.beauty.api.BeautyPerfStats
import com.picme.core.common.Logger
import com.picme.di.BeautyEngineRuntimeState
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.camera.model.FilterType
import com.picme.features.camera.model.StyleFilter
import com.picme.features.camera.preview.core.FaceWarpParams
import com.picme.features.debug.LogOverlay
import com.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_FULL }
enum class BeautyPreviewStatus { ACTIVE, SKIPPED }

private const val PROVIDER_VIEW_BIND_TIMEOUT_MS = 5000L

object AspectRatio {
    const val RATIO_4_3 = 0
    const val RATIO_16_9 = 1
    const val RATIO_FULL = 2
}

/**
 * RD 人脸坐标转换函数 - 简化版（分离关注点）
 *
 * 【核心思路】将复杂的变换分解为三个独立步骤：
 * 1. 归一化：ML Kit 坐标 → [0,1] 归一化坐标
 * 2. 镜像处理：前置摄像头需要水平翻转
 * 3. 旋转补偿：根据设备旋转角度调整坐标系
 * 
 * 【关键发现】所有坐标系都使用左上角原点，X 向右，Y 向下
 * 因此只需要关注旋转和镜像，不需要考虑坐标轴翻转
 */
internal fun transformFaceCoordinate(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewView: PreviewView,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    // ========== Step 1: 归一化 ==========
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }
    
    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight
    
    Logger.d(
        "PicMe:Camera",
        "Step1 [归一化]: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
            "norm=($normX,$normY)"
    )
    
    // ========== Step 2: 镜像处理（前置摄像头） ==========
    // 在旋转之前先镜像 X 轴（传感器坐标系）
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }
    
    Logger.d(
        "PicMe:Camera",
        "Step2 [镜像]: lens=${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前" else "后"}, " +
            "norm=($normX,$normY), mirrored=($mirroredX,$normY)"
    )
    
    // ========== Step 3: 旋转补偿 ==========
    // 根据旋转角度调整坐标方向
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)      // 竖屏：不需要调整
        90 -> Pair(mirroredX, normY)     // 顺时针 90°: 不交换 XY
        180 -> Pair(1f - mirroredX, 1f - normY) // 倒立：XY 都翻转
        270 -> Pair(mirroredX, normY)    // 逆时针 90°: 不交换 XY
        else -> Pair(mirroredX, normY)
    }
    
    Logger.d(
        "PicMe:Camera",
        "Step3 [旋转补偿]: rot=$rotationDegrees, mirrored=($mirroredX,$normY), " +
            "adjusted=($adjustedX,$adjustedY)"
    )
    
    // ========== Step 4: 转换为像素坐标 ==========
    // 将归一化坐标转换为 PreviewView 的物理像素坐标
    val previewWidth = previewView.width.toFloat()
    val previewHeight = previewView.height.toFloat()
    
    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight
    
    Logger.d(
        "PicMe:Camera",
        "Step4 [像素转换]: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}, " +
            "screen=($screenX,$screenY)"
    )
    
    return Offset(screenX, screenY)
}

internal data class BeautyDebugState(
    val status: BeautyPreviewStatus,
    val fps: Float,
    val processingMs: Int,
    val delayMs: Int,
    val cpuUsage: Float,
    val nullFrames: Int,
    val persistedFallback: Boolean,
    val persistedFallbackReason: String?,
    val strategy: BeautyStrategy,
    val recoveryAvailableAtMs: Long,
    val providerRenderActive: Boolean
)

internal data class CameraPreviewUiState(
    val selectedFilter: FilterType,
    val selectedStyleFilter: StyleFilter,
    val facePoint: Offset?,
    val faceWarpParams: FaceWarpParams,
    val showFaceDebugOverlay: Boolean,
    val focusIndicatorAlpha: Float,
    val lastMedia: MediaAsset?,
    val zoomRatio: Float,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val captureMode: MediaType,
    val isRecording: Boolean,
    val isStable: Boolean,
    val showFilterSelector: Boolean,
    val showBeautySelector: Boolean,
    val showRatioSelector: Boolean,
    val showCameraInfo: Boolean,
    val showSceneSelector: Boolean,
    val showGridSelector: Boolean,
    val debugUiEnabled: Boolean,
    val showFacialRefinement: Boolean,
    val showMakeupAdjustment: Boolean,
    val activeMakeupEntry: MakeupEntry,
    val showBodyManagement: Boolean,
    val currentScene: ScenePreset,
    val currentGrid: GridType,
    val beautySettings: BeautySettings,
    val beautyDebugState: BeautyDebugState,
    val aspectRatio: Int,
    val lensFacing: Int,
    val exposureCompensation: Int,
    val exposureRange: IntRange,
    val whiteBalanceMode: Int
)

internal data class CameraPreviewActions(
    val onNavigateToSettings: () -> Unit,
    val onNavigateToDebug: () -> Unit,
    val onNavigateToFaceLandmarkDebug: () -> Unit,
    val onFlipCamera: () -> Unit,
    val onToggleBeauty: () -> Unit,
    val onToggleFilter: () -> Unit,
    val onToggleRatio: () -> Unit,
    val onToggleCameraInfo: () -> Unit,
    val onToggleScene: () -> Unit,
    val onToggleGrid: () -> Unit,
    val onToggleLogs: () -> Unit,
    val onToggleFaceDebugOverlay: () -> Unit,
    val onToggleFacialRefinement: () -> Unit,
    val onToggleMakeupAdjustment: () -> Unit,
    val onToggleLipColor: () -> Unit,
    val onToggleBlush: () -> Unit,
    val onToggleEyebrow: () -> Unit,
    val onToggleBodyManagement: () -> Unit,
    val onZoomPresetClick: (Float) -> Unit,
    val onExposureChange: (Int) -> Unit,
    val onWhiteBalanceChange: (Int) -> Unit,
    val onSceneSelected: (ScenePreset) -> Unit,
    val onGridSelected: (GridType) -> Unit,
    val onGalleryClick: () -> Unit,
    val onCaptureClick: () -> Unit,
    val onModeChange: (MediaType) -> Unit,
    val onFilterSelected: (FilterType) -> Unit,
    val onStyleFilterSelected: (StyleFilter) -> Unit,
    val onBeautySettingsChanged: (BeautySettings) -> Unit,
    val onRatioSelected: (Int) -> Unit,
    val onDismissPanels: () -> Unit
)

private fun buildCameraPreviewUiState(
    selectedFilter: FilterType,
    selectedStyleFilter: StyleFilter,
    facePoint: Offset?,
    faceWarpParams: FaceWarpParams,
    showFaceDebugOverlay: Boolean,
    focusIndicatorAlpha: Float,
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    isStable: Boolean,
    panelState: CameraPanelState,
    showCameraInfo: Boolean,
    debugUiEnabled: Boolean,
    currentScene: ScenePreset,
    currentGrid: GridType,
    beautySettings: BeautySettings,
    beautyDebugState: BeautyDebugState,
    aspectRatio: Int,
    lensFacing: Int,
    exposureCompensation: Int,
    exposureRange: IntRange,
    whiteBalanceMode: Int
): CameraPreviewUiState {
    return CameraPreviewUiState(
        selectedFilter = selectedFilter,
        selectedStyleFilter = selectedStyleFilter,
        facePoint = facePoint,
        faceWarpParams = faceWarpParams,
        showFaceDebugOverlay = showFaceDebugOverlay,
        focusIndicatorAlpha = focusIndicatorAlpha,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        minZoomRatio = minZoomRatio,
        maxZoomRatio = maxZoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        showFilterSelector = panelState.showFilterSelector,
        showBeautySelector = panelState.showBeautySelector,
        showRatioSelector = panelState.showRatioSelector,
        showCameraInfo = showCameraInfo,
        showSceneSelector = panelState.showSceneSelector,
        showGridSelector = panelState.showGridSelector,
        debugUiEnabled = debugUiEnabled,
        showFacialRefinement = panelState.showFacialRefinement,
        showMakeupAdjustment = panelState.showMakeupAdjustment,
        activeMakeupEntry = panelState.activeMakeupEntry,
        showBodyManagement = panelState.showBodyManagement,
        currentScene = currentScene,
        currentGrid = currentGrid,
        beautySettings = beautySettings,
        beautyDebugState = beautyDebugState,
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = exposureCompensation,
        exposureRange = exposureRange,
        whiteBalanceMode = whiteBalanceMode
    )
}

private fun buildCameraPreviewActions(
    onNavigateToSettings: () -> Unit,
    onNavigateToFaceLandmarkDebug: () -> Unit,
    lensFacing: Int,
    onLensFacingChanged: (Int) -> Unit,
    onActualLensFacingChanged: (Int) -> Unit,
    panelState: CameraPanelState,
    cameraControl: CameraControl?,
    onCurrentSceneChanged: (ScenePreset) -> Unit,
    onCurrentGridChanged: (GridType) -> Unit,
    onNavigateToGallery: () -> Unit,
    onCaptureClick: () -> Unit,
    onCaptureModeChanged: (MediaType) -> Unit,
    onSelectedFilterChanged: (FilterType) -> Unit,
    onStyleFilterSelected: (StyleFilter) -> Unit,
    onBeautySettingsChanged: (BeautySettings) -> Unit,
    onAspectRatioChanged: (Int) -> Unit,
    onExposureCompensationChanged: (Int) -> Unit,
    onWhiteBalanceModeChanged: (Int) -> Unit
): CameraPreviewActions {
    return CameraPreviewActions(
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = {}, // 已废弃,保留空实现以兼容
        onNavigateToFaceLandmarkDebug = onNavigateToFaceLandmarkDebug,
        onFlipCamera = {
            val nextLens = nextLensFacing(lensFacing)
            onLensFacingChanged(nextLens)
            onActualLensFacingChanged(nextLens)
        },
        onToggleBeauty = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showBeautySelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showBeautySelector = isVisible }
            )
        },
        onToggleFilter = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showFilterSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showFilterSelector = isVisible }
            )
        },
        onToggleRatio = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showRatioSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showRatioSelector = isVisible }
            )
        },
        onToggleCameraInfo = {}, // 已废弃,由设置页控制
        onToggleScene = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showSceneSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showSceneSelector = isVisible }
            )
        },
        onToggleGrid = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showGridSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showGridSelector = isVisible }
            )
        },
        onToggleLogs = {}, // 已废弃,由设置页控制
        onToggleFaceDebugOverlay = {}, // 已废弃,由设置页控制
        onToggleFacialRefinement = panelState::toggleFacialRefinement,
        onToggleMakeupAdjustment = panelState::toggleMakeupAdjustment,
        onToggleLipColor = { panelState.openMakeupEntry(MakeupEntry.LIP_COLOR) },
        onToggleBlush = { panelState.openMakeupEntry(MakeupEntry.BLUSH) },
        onToggleEyebrow = { panelState.openMakeupEntry(MakeupEntry.EYEBROW) },
        onToggleBodyManagement = panelState::toggleBodyManagement,
        onZoomPresetClick = { ratio -> cameraControl?.setZoomRatio(ratio) },
        onExposureChange = { exposure -> 
            onExposureCompensationChanged(exposure)
            cameraControl?.setExposureCompensationIndex(exposure)
            android.util.Log.d("ProMode", "Local state updated: exposure=$exposure")
        },
        onWhiteBalanceChange = { wb ->
            onWhiteBalanceModeChanged(wb)
            android.util.Log.d("ProMode", "White balance updated: $wb")
        },
        onSceneSelected = { scene ->
            onCurrentSceneChanged(scene)
            panelState.showSceneSelector = false
        },
        onGridSelected = { grid ->
            onCurrentGridChanged(grid)
            panelState.showGridSelector = false
        },
        onGalleryClick = onNavigateToGallery,
        onCaptureClick = onCaptureClick,
        onModeChange = { mode -> onCaptureModeChanged(mode) },
        onFilterSelected = { filter -> onSelectedFilterChanged(filter) },
        onStyleFilterSelected = { style -> onStyleFilterSelected(style) },
        onBeautySettingsChanged = { settings -> onBeautySettingsChanged(settings) },
        onRatioSelected = { ratio ->
            onAspectRatioChanged(ratio)
            panelState.showRatioSelector = false
        },
        onDismissPanels = panelState::closeAllPanels
    )
}

private data class PreviewTargetDecision(
    val targetView: View,
    val scheduleProviderFallback: Boolean
)

private fun resolvePreviewTargetView(
    useProviderRenderView: Boolean,
    activeStrategy: BeautyStrategy,
    runtimeProviderView: View?,
    previewView: View
): PreviewTargetDecision {
    if (!useProviderRenderView) {
        return PreviewTargetDecision(
            targetView = previewView,
            scheduleProviderFallback = false
        )
    }

    val providerView = when (activeStrategy) {
        BeautyStrategy.BIG_BEAUTY -> runtimeProviderView
        BeautyStrategy.GPUPIXEL -> runtimeProviderView
    }

    val requiresProviderView = activeStrategy == BeautyStrategy.BIG_BEAUTY || activeStrategy == BeautyStrategy.GPUPIXEL

    if (requiresProviderView && providerView == null) {
        return PreviewTargetDecision(
            targetView = previewView,
            scheduleProviderFallback = true
        )
    }

    return PreviewTargetDecision(
        targetView = providerView ?: previewView,
        scheduleProviderFallback = false
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@ExperimentalGetImage
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFaceLandmarkDebug: () -> Unit,
    viewModel: MediaViewModel
) {
    // RD 沉浸式模式：隐藏系统栏
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)

        // 隐藏状态栏和导航栏
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        // 设置沉浸式模式，滑动边缘时显示系统栏
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        Logger.d("Camera", "Immersive mode enabled")

        onDispose {
            // 恢复系统栏显示
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            Logger.d("Camera", "Immersive mode disabled")
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    if (permissionsState.allPermissionsGranted) {
        CameraContent(
            viewModel = viewModel,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToFaceLandmarkDebug = onNavigateToFaceLandmarkDebug
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text(stringResource(R.string.grant_permissions))
            }
        }
    }
}

@SuppressLint("MissingPermission", "UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@ExperimentalGetImage
@Composable
fun CameraContent(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFaceLandmarkDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val runtimeContext = rememberCameraRuntimeContext(context)
    val imageProcessor = runtimeContext.imageProcessor
    val userPreferencesRepository = runtimeContext.userPreferencesRepository
    val coroutineScope = runtimeContext.coroutineScope
    val beautyStrategy = runtimeContext.beautyStrategy
    val debugUiEnabled = runtimeContext.debugUiEnabled
    val showCameraInfoInPreview = runtimeContext.showCameraInfoInPreview
    val showFaceDebugOverlay = runtimeContext.showFaceDebugOverlay
    val showLogOverlay = runtimeContext.showLogOverlay
    val faceLandmarkModeEnabled = runtimeContext.faceLandmarkModeEnabled
    val glRecoveryAvailableAtMs = runtimeContext.glRecoveryAvailableAtMs
    val lifecycleOwner = runtimeContext.lifecycleOwner
    LaunchedEffect(beautyStrategy) {
        val fallbackReason = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()
        if (fallbackReason != null) {
            Logger.w(
                "Camera",
                "Beauty engine fallback active: strategy=${beautyStrategy.name}, reason=$fallbackReason"
            )
        } else {
            Logger.i("Camera", "Beauty engine strategy active: ${beautyStrategy.name}")
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val shutterSound = remember { MediaActionSound() }
    
    var previewRebindSignal by remember { mutableIntStateOf(0) }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings(enabled = true)) }
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }
    var exposureCompensation by remember { mutableIntStateOf(0) }
    var whiteBalanceMode by remember { mutableIntStateOf(0) }

    var faceWarpParams by remember { mutableStateOf(FaceWarpParams()) }
    var previewFaceWarpParams by remember { mutableStateOf(FaceWarpParams()) }
    var lastFaceWarpDetectedAtMs by remember { mutableStateOf(0L) }

    // GPUPixel 106 点回调：更新 faceWarpParams 用于调试 UI 显示
    val onGpuPixelLandmarksDetected: (FloatArray?) -> Unit = { landmarks ->
        if (beautyStrategy == BeautyStrategy.GPUPIXEL) {
            if (landmarks != null && landmarks.isNotEmpty()) {
                // GPUPixel 返回的坐标未做前置摄像头镜像，需要与 MediaPipe 保持一致
                val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                val mirroredLandmarks = if (isFrontCamera) {
                    // 前置摄像头：水平镜像 x 坐标
                    FloatArray(landmarks.size).apply {
                        for (i in landmarks.indices step 2) {
                            this[i] = 1f - landmarks[i]     // x = 1 - x
                            this[i + 1] = landmarks[i + 1]   // y 不变
                        }
                    }
                } else {
                    landmarks
                }

                // 使用 Face106ToWarpParams 转换，确保与 MediaPipe 模式一致
                val newParams = com.picme.features.camera.facedetect.Face106ToWarpParams.convert(mirroredLandmarks)
                // 保存 GPUPixel 原始点位用于调试对比
                // 双模式下保留已有的 bigBeautyLandmarks（由 MediaPipe 检测提供）
                val existingBigBeauty = faceWarpParams.bigBeautyLandmarks
                val existingHasFace = faceWarpParams.hasFace
                faceWarpParams = newParams.copy(
                    gpuPixelLandmarks = com.picme.features.camera.preview.core.GpuPixelLandmarks.fromFloatArray(mirroredLandmarks),
                    bigBeautyLandmarks = existingBigBeauty,
                    hasFace = existingHasFace || newParams.hasFace
                )
            } else {
                // 双模式下保留 bigBeautyLandmarks
                val existingBigBeauty = faceWarpParams.bigBeautyLandmarks
                val existingHasFace = faceWarpParams.hasFace
                faceWarpParams = FaceWarpParams(
                    bigBeautyLandmarks = existingBigBeauty,
                    hasFace = existingHasFace
                )
            }
        }
    }

    val previewRuntimeViews = rememberPreviewRuntimeViews(
        context = context,
        aspectRatio = aspectRatio,
        beautyStrategy = beautyStrategy,
        onGpuPixelLandmarksDetected = onGpuPixelLandmarksDetected
    )
    val previewView = previewRuntimeViews.previewView
    val glPreviewProvider = previewRuntimeViews.glPreviewProvider

    val recoveryState = rememberGlRecoveryState(
        beautyStrategy = beautyStrategy,
        glRecoveryAvailableAtMs = glRecoveryAvailableAtMs,
        userPreferencesRepository = userPreferencesRepository,
        coroutineScope = coroutineScope
    )
    val persistedGlFallback = recoveryState.persistedFallback
    val persistedGlFallbackReason = recoveryState.persistedFallbackReason

    val previewStrategyBundle = rememberPreviewStrategyBundle(
        beautyStrategy = beautyStrategy,
        previewView = previewView,
        glPreviewProvider = glPreviewProvider,
        onGlWarmUpFallback = recoveryState.onGlWarmUpFallback
    )
    val activePreviewStrategy = previewStrategyBundle.activeStrategy

    var useProviderRenderView by remember(beautyStrategy) {
        mutableStateOf(beautyStrategy == BeautyStrategy.GPUPIXEL)
    }
    var lipRealtimeRecoveryRequested by remember { mutableStateOf(false) }
    var lastLipPreviewRebindRequestMs by remember { mutableStateOf(0L) }

    val bindPreviewSurfaceProvider: (Preview) -> Unit = { previewUseCase ->
        useProviderRenderView = activePreviewStrategy.bindPreview(previewUseCase, aspectRatio)
    }

    LaunchedEffect(useProviderRenderView, beautyStrategy, previewRebindSignal) {
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        if (beautyStrategy != BeautyStrategy.BIG_BEAUTY && beautyStrategy != BeautyStrategy.GPUPIXEL) {
            return@LaunchedEffect
        }

        delay(PROVIDER_VIEW_BIND_TIMEOUT_MS)
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        if (glPreviewProvider?.isReady() != true) {
            Logger.w(
                "Camera",
                "Provider view bind timeout, fallback to PreviewView and request rebind"
            )
            useProviderRenderView = false
            previewRebindSignal += 1
        }
    }

    // RD 快路径：参数变更立即下发到当前预览引擎，保证滑杆跟手性。
    LaunchedEffect(beautySettings, beautyStrategy) {
        val settings = beautySettings
        activePreviewStrategy.applyBeautySettings(settings)

        Logger.d(
            "Camera",
            "Beauty params updated: engine=${activePreviewStrategy.strategy.name}, smoothing=${settings.smoothing}, " +
                "whitening=${settings.whitening}, bigEyes=${settings.bigEyes}, slimFace=${settings.slimFace}"
        )
    }

    LaunchedEffect(faceWarpParams, beautyStrategy, beautySettings.enabled, beautySettings.lipColor) {
        val lipRealtimeRequired = beautySettings.enabled && beautySettings.lipColor > 0f
        val nowMs = System.currentTimeMillis()

        val nextPreviewParams = when {
            faceWarpParams.hasFace -> {
                lastFaceWarpDetectedAtMs = nowMs
                faceWarpParams
            }
            lipRealtimeRequired && previewFaceWarpParams.hasFace &&
                nowMs - lastFaceWarpDetectedAtMs <= 320L -> {
                previewFaceWarpParams
            }
            else -> faceWarpParams
        }

        if (nextPreviewParams != previewFaceWarpParams) {
            previewFaceWarpParams = nextPreviewParams
        }

        activePreviewStrategy.applyFaceWarpParams(nextPreviewParams)
    }

    LaunchedEffect(
        beautyStrategy,
        beautySettings.lipColor,
        beautySettings.enabled,
        useProviderRenderView
    ) {
        val lipRealtimeRequired = beautySettings.enabled && beautySettings.lipColor > 0f
        if (!lipRealtimeRequired) {
            lipRealtimeRecoveryRequested = false
            return@LaunchedEffect
        }

        if (useProviderRenderView) {
            lipRealtimeRecoveryRequested = false
            return@LaunchedEffect
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLipPreviewRebindRequestMs >= 1500L) {
            lastLipPreviewRebindRequestMs = nowMs
            Logger.w(
                "Camera",
                "Lip realtime preview unavailable, request provider rebind: strategy=${beautyStrategy.name}"
            )
            previewRebindSignal += 1
        }
    }

    // RD 监听比例变化，动态调整 ScaleType
    LaunchedEffect(aspectRatio) {
        previewView.scaleType = when (aspectRatio) {
            AspectRatio.RATIO_FULL -> PreviewView.ScaleType.FILL_CENTER
            else -> PreviewView.ScaleType.FIT_CENTER
        }
        Logger.d("Camera",
            "ScaleType updated to ${previewView.scaleType} for aspectRatio=$aspectRatio"
        )
    }

    val panelState = rememberCameraPanelState()
    val logOverlayScope = rememberCoroutineScope()
    // 移除本地 state,改用设置页配置
    // var showCameraInfo by remember { mutableStateOf(false) }
    // var showLogOverlay by remember { mutableStateOf(false) }
    // var showFaceDebugOverlay by remember { mutableStateOf(false) }

    var currentScene by remember { mutableStateOf(ScenePreset.NONE) }
    var currentGrid by remember { mutableStateOf(GridType.NONE) }
    
    var isStable by remember { mutableStateOf(true) }
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    val g = sqrt(x * x + y * y + z * z)
                    val diff = abs(g - SensorManager.GRAVITY_EARTH)
                    isStable = diff < 0.5f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
            shutterSound.release()
        }
    }

    // RD ImageCapture需要在LaunchedEffect中创建，以便1:1模式可以正确配置ViewPort
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }
    
    var facePoint by remember { mutableStateOf(Offset.Zero) }
    var isFaceLocked by remember { mutableStateOf(false) }
    var lastAutoFocusAtMs by remember { mutableStateOf(0L) }
    var lastFocusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusIndicatorAlpha = remember { Animatable(0f) }

    val mediaAssets by viewModel.allMedia.collectAsState()
    val lastMedia = mediaAssets.firstOrNull()
    val beautyPreviewStatus = if (beautySettings.enabled && beautySettings.hasAnyEffect()) {
        BeautyPreviewStatus.ACTIVE
    } else {
        BeautyPreviewStatus.SKIPPED
    }
    var renderPerfStats by remember {
        mutableStateOf(BeautyPerfStats())
    }

    LaunchedEffect(beautyStrategy, useProviderRenderView, previewRebindSignal) {
        while (isActive) {
            renderPerfStats = if ((beautyStrategy == BeautyStrategy.BIG_BEAUTY || beautyStrategy == BeautyStrategy.GPUPIXEL) && useProviderRenderView) {
                glPreviewProvider?.getPerfStats() ?: BeautyPerfStats()
            } else {
                BeautyPerfStats()
            }
            delay(250)
        }
    }

    LaunchedEffect(currentScene) {
        cameraControl?.let { control ->
            Logger.i("Camera", "Applying scene: $currentScene")
            when (currentScene) {
                ScenePreset.NIGHT -> {
                    control.setExposureCompensationIndex(1)
                }
                ScenePreset.MOON -> {
                    control.setExposureCompensationIndex(-2)
                    control.setZoomRatio(3.2f)
                }
                ScenePreset.NONE -> {
                    control.setExposureCompensationIndex(0)
                }
            }
        }
    }
    


    LaunchedEffect(lensFacing, captureMode, aspectRatio, beautyStrategy, previewRebindSignal) {
        bindCameraUseCases(
            context = context,
            lifecycleOwner = lifecycleOwner,
            cameraProviderFuture = cameraProviderFuture,
            lensFacing = lensFacing,
            captureMode = captureMode,
            aspectRatio = aspectRatio,
            previewView = previewView,
            bindPreviewSurfaceProvider = bindPreviewSurfaceProvider,
            cameraExecutor = cameraExecutor,
            beautySettings = beautySettings,
            beautyStrategy = beautyStrategy,
            videoCapture = videoCapture,
            gpupixelProvider = glPreviewProvider as? com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider,
            onImageCaptureChanged = { capture -> imageCapture = capture },
            onCameraControlChanged = { control -> cameraControl = control },
            onZoomRatioChanged = { ratio -> zoomRatio = ratio },
            onZoomRangeChanged = { minZoom, maxZoom ->
                minZoomRatio = minZoom
                maxZoomRatio = maxZoom
            },
            onActualLensFacingChanged = { ignoredLensFacing ->
                Logger.d("Camera", "Actual lens changed: $ignoredLensFacing")
            },
            onFacePointChanged = { point -> facePoint = point },
            onFaceWarpParamsChanged = { params ->
                if (beautyStrategy == BeautyStrategy.GPUPIXEL) {
                    // 双模式：合并 MediaPipe 的 bigBeautyLandmarks 到现有的 GPUPixel 参数中
                    val existingGpuPixel = faceWarpParams.gpuPixelLandmarks
                    val existingHasFace = faceWarpParams.hasFace
                    val existingBigBeauty = faceWarpParams.bigBeautyLandmarks
                    
                    // 判断当前回调是 MediaPipe 还是 GPUPixel 数据
                    val isMediaPipeCallback = params.bigBeautyLandmarks.hasFace && params.bigBeautyLandmarks.points.isNotEmpty()
                    val isGpuPixelCallback = params.gpuPixelLandmarks.hasFace && params.gpuPixelLandmarks.points.isNotEmpty()
                    
                    Logger.d("Camera", "onFaceWarpParamsChanged: isMediaPipe=$isMediaPipeCallback, isGpuPixel=$isGpuPixelCallback, " +
                        "existingBB=${existingBigBeauty.hasFace}, existingGP=${existingGpuPixel.hasFace}")
                    
                    faceWarpParams = when {
                        // MediaPipe 回调：保留已有的 GPUPixel 数据
                        isMediaPipeCallback && !isGpuPixelCallback -> {
                            Logger.d("Camera", "Merging MediaPipe data, GP points=${existingGpuPixel.points.size}")
                            faceWarpParams.copy(
                                bigBeautyLandmarks = params.bigBeautyLandmarks,
                                hasFace = existingHasFace || params.hasFace
                            )
                        }
                        // GPUPixel 回调：保留已有的 MediaPipe 数据
                        isGpuPixelCallback && !isMediaPipeCallback -> {
                            Logger.d("Camera", "Merging GPUPixel data, BB points=${existingBigBeauty.points.size}")
                            faceWarpParams.copy(
                                gpuPixelLandmarks = params.gpuPixelLandmarks,
                                hasFace = existingHasFace || params.hasFace
                            )
                        }
                        // 其他情况：直接更新
                        else -> {
                            Logger.d("Camera", "Direct update: params.hasFace=${params.hasFace}")
                            params
                        }
                    }
                } else {
                    faceWarpParams = params
                }
            },
            onShowFocusIndicatorChanged = { show ->
                isFaceLocked = show
            }
        )
    }

    // 第二阶段瘦身后：实时预览在 runRealtimeBeautyPreviewLoop 中统一处理。

    LaunchedEffect(cameraControl, previewView.width, previewView.height, lensFacing) {
        val control = cameraControl ?: return@LaunchedEffect
        if (previewView.width <= 0 || previewView.height <= 0) {
            return@LaunchedEffect
        }

        delay(320)
        val centerPoint = previewView.meteringPointFactory.createPoint(
            previewView.width * 0.5f,
            previewView.height * 0.5f
        )
        val centerAction = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        runCatching {
            control.startFocusAndMetering(centerAction)
        }.onSuccess {
            lastAutoFocusAtMs = System.currentTimeMillis()
            lastFocusPoint = Offset(previewView.width * 0.5f, previewView.height * 0.5f)
            Logger.d("Camera", "Initial autofocus triggered at preview center")
        }.onFailure { error ->
            Logger.w("Camera", "Initial autofocus request failed", error)
        }
    }

    LaunchedEffect(isFaceLocked, facePoint, cameraControl, previewView.width, previewView.height) {
        val control = cameraControl ?: return@LaunchedEffect
        if (!isFaceLocked || previewView.width <= 0 || previewView.height <= 0) {
            return@LaunchedEffect
        }

        val nowMs = System.currentTimeMillis()
        val minIntervalMs = 1100L
        if (nowMs - lastAutoFocusAtMs < minIntervalMs) {
            return@LaunchedEffect
        }

        val clampedX = facePoint.x.coerceIn(0f, previewView.width.toFloat())
        val clampedY = facePoint.y.coerceIn(0f, previewView.height.toFloat())
        val nextPoint = Offset(clampedX, clampedY)
        val previousPoint = lastFocusPoint
        val distance = if (previousPoint == null) {
            Float.MAX_VALUE
        } else {
            kotlin.math.hypot(nextPoint.x - previousPoint.x, nextPoint.y - previousPoint.y)
        }
        if (distance < 42f) {
            return@LaunchedEffect
        }

        val meteringPoint = previewView.meteringPointFactory.createPoint(clampedX, clampedY)
        val focusAction = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        runCatching {
            control.startFocusAndMetering(focusAction)
        }.onSuccess {
            lastAutoFocusAtMs = nowMs
            lastFocusPoint = nextPoint
            Logger.d("Camera", "Autofocus triggered: x=$clampedX, y=$clampedY, distance=$distance")
        }.onFailure { error ->
            Logger.w("Camera", "Autofocus request failed", error)
        }
    }

    LaunchedEffect(isFaceLocked, isStable) {
        if (!isFaceLocked) {
            focusIndicatorAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220)
            )
            return@LaunchedEffect
        }

        if (!isStable) {
            focusIndicatorAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 160)
            )
            return@LaunchedEffect
        }

        delay(320)
        focusIndicatorAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 420)
        )
    }

    DisposableEffect(activePreviewStrategy) {
        onDispose {
            activePreviewStrategy.release()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

CameraPreviewContent(
    previewView = {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    android.util.Log.d("PicMe:Camera", "AndroidView factory creating FrameLayout")
                    FrameLayout(context)
                },
                modifier = Modifier.fillMaxSize(),
                update = { container ->
                    val runtimeProviderView = runCatching {
                        glPreviewProvider?.getView() ?: run {
                            glPreviewProvider?.initialize()
                            glPreviewProvider?.getView()
                        }
                    }.getOrNull()

                    val targetDecision = resolvePreviewTargetView(
                        useProviderRenderView = useProviderRenderView,
                        activeStrategy = activePreviewStrategy.strategy,
                        runtimeProviderView = runtimeProviderView,
                        previewView = previewView
                    )

                    android.util.Log.d(
                        "PicMe:Camera",
                        "AndroidView update: useProviderRenderView=$useProviderRenderView, strategy=${activePreviewStrategy.strategy}, " +
                            "targetView=${targetDecision.targetView}"
                    )

                    if (targetDecision.scheduleProviderFallback) {
                        Logger.w("Camera", "Provider render view missing, fallback to PreviewView and request rebind")
                        container.post {
                            if (useProviderRenderView) {
                                useProviderRenderView = false
                                previewRebindSignal += 1
                            }
                        }
                    }

                    val targetView = targetDecision.targetView

                    if (targetView.parent !== container) {
                        android.util.Log.d("PicMe:Camera", "AndroidView update: targetView.parent=${targetView.parent}, container=$container, adding view")
                        (targetView.parent as? ViewGroup)?.removeView(targetView)
                        container.removeAllViews()
                        container.addView(
                            targetView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    } else {
                        android.util.Log.d("PicMe:Camera", "AndroidView update: targetView already in container, skip adding")
                    }
                }
            )

        }
    },
    uiState = buildCameraPreviewUiState(
        selectedFilter = selectedFilter,
        selectedStyleFilter = beautySettings.styleFilter,
        facePoint = facePoint,
        faceWarpParams = faceWarpParams,
        showFaceDebugOverlay = showFaceDebugOverlay,
        focusIndicatorAlpha = focusIndicatorAlpha.value,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        minZoomRatio = minZoomRatio,
        maxZoomRatio = maxZoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        panelState = panelState,
        showCameraInfo = showCameraInfoInPreview,
        debugUiEnabled = debugUiEnabled,
        currentScene = currentScene,
        currentGrid = currentGrid,
        beautySettings = beautySettings,
        beautyDebugState = BeautyDebugState(
            status = beautyPreviewStatus,
            fps = renderPerfStats.fps,
            processingMs = renderPerfStats.processingMs,
            delayMs = renderPerfStats.delayMs,
            cpuUsage = renderPerfStats.cpuUsage,
            nullFrames = renderPerfStats.nullFrames,
            persistedFallback = persistedGlFallback,
            persistedFallbackReason = persistedGlFallbackReason,
            strategy = beautyStrategy,
            recoveryAvailableAtMs = glRecoveryAvailableAtMs,

            providerRenderActive = useProviderRenderView
        ),
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = exposureCompensation,
        exposureRange = -2..2,
        whiteBalanceMode = whiteBalanceMode
    ),
    actions = buildCameraPreviewActions(
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToFaceLandmarkDebug = onNavigateToFaceLandmarkDebug,
        lensFacing = lensFacing,
        onLensFacingChanged = { updatedLensFacing -> lensFacing = updatedLensFacing },
        onActualLensFacingChanged = { updatedLensFacing ->
            Logger.d("Camera", "Action lens sync: $updatedLensFacing")
        },
        panelState = panelState,
        cameraControl = cameraControl,
        onCurrentSceneChanged = { scene -> currentScene = scene },
        onCurrentGridChanged = { grid -> currentGrid = grid },
        onNavigateToGallery = onNavigateToGallery,
        onCaptureClick = {
            val provider = glPreviewProvider as? com.picme.beauty.gpupixel.GpupixelBeautyPreviewProvider
            android.util.Log.d(
                "PicMe:Camera",
                "onCaptureClick: beautyStrategy=$beautyStrategy, glPreviewProviderType=${glPreviewProvider?.javaClass?.simpleName}, castResult=${provider != null}"
            )
            handleCaptureClick(
                context = context,
                captureMode = captureMode,
                isRecording = isRecording,
                recording = recording,
                videoCapture = videoCapture,
                viewModel = viewModel,
                imageCapture = imageCapture,
                imageProcessor = imageProcessor,
                selectedFilter = selectedFilter,
                beautySettings = beautySettings,
                lensFacing = lensFacing,
                cachedFaces = emptyList(),
                beautyStrategy = beautyStrategy,
                gpupixelProvider = provider,
                onRecordingChanged = { updated -> recording = updated },
                onIsRecordingChanged = { recordingFlag -> isRecording = recordingFlag }
            )
        },
        onCaptureModeChanged = { mode -> captureMode = mode },
        onSelectedFilterChanged = { filter ->
            selectedFilter = filter
            // 同步到 beautySettings，确保预览引擎能收到色调矩阵变化
            beautySettings = beautySettings.copy(colorFilter = filter)
        },
        onStyleFilterSelected = { style ->
            beautySettings = beautySettings.copy(styleFilter = style)
        },
        onBeautySettingsChanged = { updatedSettings ->
            beautySettings = resolveNextBeautySettings(
                currentSettings = beautySettings,
                updatedSettings = updatedSettings
            )
        },
        onAspectRatioChanged = { ratio -> aspectRatio = ratio },
        onExposureCompensationChanged = { exposure -> exposureCompensation = exposure },
        onWhiteBalanceModeChanged = { wb -> whiteBalanceMode = wb }
    )
)

        if (debugUiEnabled && showLogOverlay) {
            LogOverlay(onDismiss = { 
                logOverlayScope.launch {
                    userPreferencesRepository.updateShowLogOverlay(false)
                }
            })
        }

}


