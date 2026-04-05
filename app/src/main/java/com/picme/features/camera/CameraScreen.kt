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
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.picme.R
import com.picme.core.common.Logger
import com.picme.data.preferences.BeautyStrategy
import com.picme.di.BeautyEngineRuntimeState
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.camera.model.FilterType
import com.picme.features.camera.preview.core.FaceWarpParams
import com.picme.features.camera.preview.core.rememberPreviewStrategyBundle
import com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode
import com.picme.features.debug.LogOverlay
import com.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_FULL }
enum class BeautyPreviewStatus { ACTIVE, SKIPPED }

private const val PROVIDER_VIEW_BIND_TIMEOUT_MS = 1800L

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
    val pixelFreeLinkMode: PixelFreePreviewLinkMode?,
    val pixelFreeLinkReason: String?
)

internal data class CameraPreviewUiState(
    val selectedFilter: FilterType,
    val facePoint: Offset?,
    val faceWarpParams: FaceWarpParams,
    val showFaceDebugOverlay: Boolean,
    val focusIndicatorAlpha: Float,
    val lastMedia: MediaAsset?,
    val zoomRatio: Float,
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
    val onBeautySettingsChanged: (BeautySettings) -> Unit,
    val onRatioSelected: (Int) -> Unit,
    val onDismissPanels: () -> Unit
)

private fun buildCameraPreviewUiState(
    selectedFilter: FilterType,
    facePoint: Offset?,
    faceWarpParams: FaceWarpParams,
    showFaceDebugOverlay: Boolean,
    focusIndicatorAlpha: Float,
    lastMedia: MediaAsset?,
    zoomRatio: Float,
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
        facePoint = facePoint,
        faceWarpParams = faceWarpParams,
        showFaceDebugOverlay = showFaceDebugOverlay,
        focusIndicatorAlpha = focusIndicatorAlpha,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
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
    onNavigateToDebug: () -> Unit,
    lensFacing: Int,
    onLensFacingChanged: (Int) -> Unit,
    onActualLensFacingChanged: (Int) -> Unit,
    panelState: CameraPanelState,
    showCameraInfo: Boolean,
    onShowCameraInfoChanged: (Boolean) -> Unit,
    debugUiEnabled: Boolean,
    onLogOverlayToggleRequested: () -> Unit,
    showFaceDebugOverlay: Boolean,
    onShowFaceDebugOverlayChanged: (Boolean) -> Unit,
    cameraControl: CameraControl?,
    onCurrentSceneChanged: (ScenePreset) -> Unit,
    onCurrentGridChanged: (GridType) -> Unit,
    onNavigateToGallery: () -> Unit,
    onCaptureClick: () -> Unit,
    onCaptureModeChanged: (MediaType) -> Unit,
    onSelectedFilterChanged: (FilterType) -> Unit,
    onBeautySettingsChanged: (BeautySettings) -> Unit,
    onAspectRatioChanged: (Int) -> Unit
): CameraPreviewActions {
    return CameraPreviewActions(
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = onNavigateToDebug,
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
        onToggleCameraInfo = {
            onShowCameraInfoChanged(!showCameraInfo)
        },
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
        onToggleLogs = {
            if (debugUiEnabled) {
                onLogOverlayToggleRequested()
            }
        },
        onToggleFaceDebugOverlay = {
            onShowFaceDebugOverlayChanged(!showFaceDebugOverlay)
        },
        onToggleFacialRefinement = panelState::toggleFacialRefinement,
        onToggleMakeupAdjustment = panelState::toggleMakeupAdjustment,
        onToggleBodyManagement = panelState::toggleBodyManagement,
        onZoomPresetClick = { ratio -> cameraControl?.setZoomRatio(ratio) },
        onExposureChange = { exposure -> cameraControl?.setExposureCompensationIndex(exposure) },
        onWhiteBalanceChange = { _ -> },
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
        onBeautySettingsChanged = { settings -> onBeautySettingsChanged(settings) },
        onRatioSelected = { ratio ->
            onAspectRatioChanged(ratio)
            panelState.showRatioSelector = false
        },
        onDismissPanels = panelState::closeAllPanels
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@ExperimentalGetImage
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
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
            onNavigateToDebug = onNavigateToDebug
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
    onNavigateToDebug: () -> Unit
) {
    val context = LocalContext.current
    val runtimeContext = rememberCameraRuntimeContext(context)
    val imageProcessor = runtimeContext.imageProcessor
    val userPreferencesRepository = runtimeContext.userPreferencesRepository
    val coroutineScope = runtimeContext.coroutineScope
    val beautyStrategy = runtimeContext.beautyStrategy
    val debugUiEnabled = runtimeContext.debugUiEnabled
    val faceLandmarkModeEnabled = runtimeContext.faceLandmarkModeEnabled
    val rPlanRecoveryAvailableAtMs = runtimeContext.rPlanRecoveryAvailableAtMs
    val lifecycleOwner = runtimeContext.lifecycleOwner
    LaunchedEffect(beautyStrategy) {
        val fallbackReason = BeautyEngineRuntimeState.consumeRPlanFallbackReason()
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

    val previewRuntimeViews = rememberPreviewRuntimeViews(
        context = context,
        aspectRatio = aspectRatio,
        beautyStrategy = beautyStrategy
    )
    val previewView = previewRuntimeViews.previewView
    val pixelFreeView = previewRuntimeViews.pixelFreeView
    val rPlanPreviewProvider = previewRuntimeViews.rPlanPreviewProvider

    val recoveryState = rememberRPlanRecoveryState(
        beautyStrategy = beautyStrategy,
        rPlanRecoveryAvailableAtMs = rPlanRecoveryAvailableAtMs,
        userPreferencesRepository = userPreferencesRepository,
        coroutineScope = coroutineScope
    )
    val persistedRPlanFallback = recoveryState.persistedFallback
    val persistedRPlanFallbackReason = recoveryState.persistedFallbackReason

    var pixelFreeLinkMode by remember { mutableStateOf<PixelFreePreviewLinkMode?>(null) }
    var pixelFreeLinkReason by remember { mutableStateOf<String?>(null) }

    val previewStrategyBundle = rememberPreviewStrategyBundle(
        beautyStrategy = beautyStrategy,
        previewView = previewView,
        pixelFreeView = pixelFreeView,
        rPlanPreviewProvider = rPlanPreviewProvider,
        onRPlanWarmUpFallback = recoveryState.onRPlanWarmUpFallback,
        onPixelFreePreviewLinkModeChanged = { mode -> pixelFreeLinkMode = mode },
        onPixelFreePreviewLinkReasonChanged = { reason -> pixelFreeLinkReason = reason }
    )
    val activePreviewStrategy = previewStrategyBundle.activeStrategy

    var useProviderRenderView by remember { mutableStateOf(false) }

    val bindPreviewSurfaceProvider: (Preview) -> Unit = { previewUseCase ->
        useProviderRenderView = activePreviewStrategy.bindPreview(previewUseCase, aspectRatio)
    }

    LaunchedEffect(useProviderRenderView, beautyStrategy, previewRebindSignal, pixelFreeLinkMode) {
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        val shouldCheckProviderReady = when (beautyStrategy) {
            BeautyStrategy.R_PLAN -> true
            BeautyStrategy.PIXEL_FREE -> pixelFreeLinkMode == PixelFreePreviewLinkMode.PROVIDER
        }
        if (!shouldCheckProviderReady) {
            return@LaunchedEffect
        }

        delay(PROVIDER_VIEW_BIND_TIMEOUT_MS)
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        if (rPlanPreviewProvider?.isReady() != true) {
            Logger.w(
                "Camera",
                "Provider view bind timeout, fallback to PreviewView and request rebind"
            )
            useProviderRenderView = false
            previewRebindSignal += 1
        }
    }

    var faceWarpParams by remember { mutableStateOf(FaceWarpParams()) }

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

    LaunchedEffect(faceWarpParams, beautyStrategy) {
        activePreviewStrategy.applyFaceWarpParams(faceWarpParams)
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
    var showCameraInfo by remember { mutableStateOf(false) }
    var showLogOverlay by remember { mutableStateOf(false) }
    var showFaceDebugOverlay by remember { mutableStateOf(false) }

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
    
    var facePoint by remember { mutableStateOf(Offset.Zero) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    val focusIndicatorAlpha = remember { Animatable(0f) }

    val faceDetector = remember(faceLandmarkModeEnabled, showFaceDebugOverlay) {
        val optionsBuilder = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)

        val useContour = showFaceDebugOverlay || !faceLandmarkModeEnabled
        optionsBuilder.setContourMode(
            if (useContour) {
                FaceDetectorOptions.CONTOUR_MODE_ALL
            } else {
                FaceDetectorOptions.CONTOUR_MODE_NONE
            }
        )

        Logger.i(
            "Camera",
            "Face detector mode=${if (useContour) "CONTOUR" else "LANDMARK"}, debugOverlay=$showFaceDebugOverlay"
        )
        FaceDetection.getClient(optionsBuilder.build())
    }

    val mediaAssets by viewModel.allMedia.collectAsState()
    val lastMedia = mediaAssets.firstOrNull()
    val beautyPreviewStatus = if (beautySettings.enabled && beautySettings.hasAnyEffect()) {
        BeautyPreviewStatus.ACTIVE
    } else {
        BeautyPreviewStatus.SKIPPED
    }
    var renderPerfStats by remember {
        mutableStateOf(com.picme.core.image.CameraPreviewRenderer.PerfStats())
    }

    LaunchedEffect(beautyStrategy, useProviderRenderView, previewRebindSignal) {
        while (isActive) {
            renderPerfStats = if (beautyStrategy == BeautyStrategy.R_PLAN && useProviderRenderView) {
                rPlanPreviewProvider?.getPerfStats() ?: com.picme.core.image.CameraPreviewRenderer.PerfStats()
            } else {
                com.picme.core.image.CameraPreviewRenderer.PerfStats()
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
    
    // RD 监听美颜参数变化（暂时注释，后续实现）
    // LaunchedEffect(beautySettings) {
    //     pixelFreeView.setSmoothingStrength(beautySettings.smoothing.toFloat())
    //     pixelFreeView.setWhiteningStrength(beautySettings.whitening.toFloat())
    //     pixelFreeView.setSlimFaceStrength(beautySettings.slimFace.toFloat())
    //     pixelFreeView.setBigEyesStrength(beautySettings.bigEyes.toFloat())
    //     Logger.d("Camera", "PixelFree beauty updated: smoothing=${beautySettings.smoothing}, whitening=${beautySettings.whitening}")
    // }
    
    // 仅在 PixelFree 策略下执行 PixelFree 初始化日志。
    LaunchedEffect(beautyStrategy, pixelFreeView) {
        if (beautyStrategy != BeautyStrategy.PIXEL_FREE || pixelFreeView == null) {
            return@LaunchedEffect
        }

        delay(500)
        Logger.d("Camera", "PixelFree initialization requested")
    }

    LaunchedEffect(lensFacing, captureMode, aspectRatio, beautyStrategy, previewRebindSignal, faceDetector) {
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
            faceDetector = faceDetector,
            beautySettings = beautySettings,
            videoCapture = videoCapture,
            onImageCaptureChanged = { capture -> imageCapture = capture },
            onCameraControlChanged = { control -> cameraControl = control },
            onZoomRatioChanged = { ratio -> zoomRatio = ratio },
            onActualLensFacingChanged = { ignoredLensFacing ->
                Logger.d("Camera", "Actual lens changed: $ignoredLensFacing")
            },
            onFacePointChanged = { point -> facePoint = point },
            onFaceWarpParamsChanged = { params -> faceWarpParams = params },
            onShowFocusIndicatorChanged = { show -> showFocusIndicator = show }
        )
    }

    // 第二阶段瘦身后：实时预览在 runRealtimeBeautyPreviewLoop 中统一处理。

    LaunchedEffect(showFocusIndicator) {
        focusIndicatorAlpha.animateTo(
            if (showFocusIndicator) 1f else 0f,
            animationSpec = tween(if (showFocusIndicator) 200 else 500)
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

    DisposableEffect(faceDetector) {
        onDispose {
            faceDetector.close()
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
                        rPlanPreviewProvider?.getView() ?: run {
                            rPlanPreviewProvider?.initialize()
                            rPlanPreviewProvider?.getView()
                        }
                    }.getOrNull()

                    val targetView = if (useProviderRenderView) {
                        val providerView = when (activePreviewStrategy.strategy) {
                            BeautyStrategy.R_PLAN -> runtimeProviderView
                            BeautyStrategy.PIXEL_FREE -> when (pixelFreeLinkMode) {
                                PixelFreePreviewLinkMode.PROVIDER -> runtimeProviderView
                                PixelFreePreviewLinkMode.RAW -> pixelFreeView
                                PixelFreePreviewLinkMode.PREVIEW_FALLBACK -> previewView
                                null -> pixelFreeView
                            }
                        }
                        val requiresProviderView =
                            activePreviewStrategy.strategy == BeautyStrategy.R_PLAN ||
                                (activePreviewStrategy.strategy == BeautyStrategy.PIXEL_FREE &&
                                    pixelFreeLinkMode == PixelFreePreviewLinkMode.PROVIDER)

                        android.util.Log.d(
                            "PicMe:Camera",
                            "AndroidView update: useProviderRenderView=true, strategy=${activePreviewStrategy.strategy}, pixelFreeLinkMode=$pixelFreeLinkMode, providerView=$providerView"
                        )

                        if (requiresProviderView && providerView == null) {
                            Logger.w("Camera", "Provider render view missing, fallback to PreviewView and request rebind")
                            if (activePreviewStrategy.strategy == BeautyStrategy.PIXEL_FREE) {
                                pixelFreeLinkMode = PixelFreePreviewLinkMode.PREVIEW_FALLBACK
                                pixelFreeLinkReason = "provider view is null"
                            }
                            useProviderRenderView = false
                            previewRebindSignal += 1
                            previewView
                        } else {
                            providerView ?: previewView
                        }
                    } else {
                        android.util.Log.d("PicMe:Camera", "AndroidView update: useProviderRenderView=false, using previewView")
                        previewView
                    }

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
        facePoint = facePoint,
        faceWarpParams = faceWarpParams,
        showFaceDebugOverlay = showFaceDebugOverlay,
        focusIndicatorAlpha = focusIndicatorAlpha.value,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        panelState = panelState,
        showCameraInfo = showCameraInfo,
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
            persistedFallback = persistedRPlanFallback,
            persistedFallbackReason = persistedRPlanFallbackReason,
            strategy = beautyStrategy,
            recoveryAvailableAtMs = rPlanRecoveryAvailableAtMs,
            pixelFreeLinkMode = pixelFreeLinkMode,
            pixelFreeLinkReason = pixelFreeLinkReason
        ),
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = 0,
        exposureRange = -2..2,
        whiteBalanceMode = 0
    ),
    actions = buildCameraPreviewActions(
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = onNavigateToDebug,
        lensFacing = lensFacing,
        onLensFacingChanged = { updatedLensFacing -> lensFacing = updatedLensFacing },
        onActualLensFacingChanged = { updatedLensFacing ->
            Logger.d("Camera", "Action lens sync: $updatedLensFacing")
        },
        panelState = panelState,
        showCameraInfo = showCameraInfo,
        onShowCameraInfoChanged = { showInfo -> showCameraInfo = showInfo },
        debugUiEnabled = debugUiEnabled,
        onLogOverlayToggleRequested = { showLogOverlay = !showLogOverlay },
        showFaceDebugOverlay = showFaceDebugOverlay,
        onShowFaceDebugOverlayChanged = { showDebug -> showFaceDebugOverlay = showDebug },
        cameraControl = cameraControl,
        onCurrentSceneChanged = { scene -> currentScene = scene },
        onCurrentGridChanged = { grid -> currentGrid = grid },
        onNavigateToGallery = onNavigateToGallery,
        onCaptureClick = {
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
                onRecordingChanged = { updated -> recording = updated },
                onIsRecordingChanged = { recordingFlag -> isRecording = recordingFlag }
            )
        },
        onCaptureModeChanged = { mode -> captureMode = mode },
        onSelectedFilterChanged = { filter -> selectedFilter = filter },
        onBeautySettingsChanged = { updatedSettings ->
            beautySettings = resolveNextBeautySettings(
                currentSettings = beautySettings,
                updatedSettings = updatedSettings
            )
        },
        onAspectRatioChanged = { ratio -> aspectRatio = ratio }
    )
)

        if (debugUiEnabled && showLogOverlay) {
            LogOverlay(onDismiss = { showLogOverlay = false })
        }

}


