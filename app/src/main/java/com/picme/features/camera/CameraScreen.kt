package com.picme.features.camera
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.picme.PicMeApplication
import com.picme.R
import com.picme.core.common.Logger
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy
import com.picme.data.preferences.FaceDetectIntervalProfile
import com.picme.di.BeautyEngineRuntimeState
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.domain.usecase.OcrUseCase
import com.picme.features.camera.components.BeautySelector
import com.picme.features.camera.components.BodyManagementSelector
import com.picme.features.camera.components.CameraBottomControls
import com.picme.features.camera.components.CameraLeftControls
import com.picme.features.camera.components.CameraOverlays
import com.picme.features.camera.components.CameraRightControls
import com.picme.features.camera.components.ControlPanel
import com.picme.features.camera.components.DocumentDetectionOverlay
import com.picme.features.camera.components.FacialRefinementSelector
import com.picme.features.camera.components.FilterSelector
import com.picme.features.camera.components.GridSelector
import com.picme.features.camera.components.MakeupAdjustmentSelector
import com.picme.features.camera.components.ProModeControls
import com.picme.features.camera.components.RatioSelector
import com.picme.features.camera.components.SceneSelector
import com.picme.features.camera.model.FilterType
import com.picme.features.debug.LogOverlay
import com.picme.features.gallery.MediaViewModel
import com.picme.features.gallery.MediaViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_FULL }
enum class BeautyPreviewStatus { ACTIVE, SKIPPED }

private const val R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L

private interface BeautyPreviewEngineStrategy {
    val strategy: BeautyStrategy

    fun bindPreview(previewUseCase: Preview)

    fun applyBeautySettings(settings: BeautySettings)

    fun release()
}

private class PixelFreePreviewStrategy(
    private val previewView: PreviewView,
    private val pixelFreeView: PixelFreeGLSurfaceView
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.PIXEL_FREE

    override fun bindPreview(previewUseCase: Preview) {
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        Logger.i("Camera", "Preview connected via PreviewView surface provider")
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        if (!settings.enabled || !settings.hasAnyEffect()) {
            return
        }

        pixelFreeView.queueEvent {
            pixelFreeView.setSmoothingStrength(settings.smoothing / 100f)
            pixelFreeView.setWhiteningStrength(settings.whitening / 100f)
            pixelFreeView.setBigEyesStrength((settings.bigEyes / 100f * 1.35f).coerceIn(0f, 1f))
            pixelFreeView.setSlimFaceStrength(settings.slimFace / 100f)
        }
    }

    override fun release() = Unit
}

private class RPlanPreviewStrategy(
    private val previewView: PreviewView,
    private val rPlanPreviewProvider: RPlanBeautyPreviewProvider,
    private val onWarmUpFallback: (String) -> Unit
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.R_PLAN

    override fun bindPreview(previewUseCase: Preview) {
        // R_PLAN 仍在逐步替换阶段，预览显示先固定走稳定链路。
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)

        try {
            rPlanPreviewProvider.initialize()
            Logger.i("Camera", "Preview connected via PreviewView, R Plan pipeline warmed up")
        } catch (error: Throwable) {
            Logger.w("Camera", "R Plan warm-up failed, fallback strategy persisted", error)
            onWarmUpFallback(error.message ?: "warm-up error")
        }
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        try {
            rPlanPreviewProvider.initialize()
            rPlanPreviewProvider.updateFilters(settings)
        } catch (error: Throwable) {
            Logger.w("Camera", "R Plan update failed, keep preview on PreviewView", error)
        }
    }

    override fun release() {
        rPlanPreviewProvider.release()
    }
}

private data class PreviewStrategyBundle(
    val rPlanStrategy: BeautyPreviewEngineStrategy,
    val pixelFreeStrategy: BeautyPreviewEngineStrategy,
    val activeStrategy: BeautyPreviewEngineStrategy
)

@Composable
private fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    pixelFreeView: PixelFreeGLSurfaceView,
    rPlanPreviewProvider: RPlanBeautyPreviewProvider,
    onRPlanWarmUpFallback: (String) -> Unit
): PreviewStrategyBundle {
    val rPlanStrategy = remember(previewView, rPlanPreviewProvider) {
        RPlanPreviewStrategy(
            previewView = previewView,
            rPlanPreviewProvider = rPlanPreviewProvider,
            onWarmUpFallback = onRPlanWarmUpFallback
        )
    }
    val pixelFreeStrategy = remember(previewView, pixelFreeView) {
        PixelFreePreviewStrategy(
            previewView = previewView,
            pixelFreeView = pixelFreeView
        )
    }

    val activeStrategy = when (beautyStrategy) {
        BeautyStrategy.R_PLAN -> rPlanStrategy
        BeautyStrategy.PIXEL_FREE -> pixelFreeStrategy
    }

    return PreviewStrategyBundle(
        rPlanStrategy = rPlanStrategy,
        pixelFreeStrategy = pixelFreeStrategy,
        activeStrategy = activeStrategy
    )
}

object AspectRatio {
    const val RATIO_4_3 = 0
    const val RATIO_16_9 = 1
    const val RATIO_FULL = 2
}

/**
 * [RD] 人脸坐标转换函数 - 简化版（分离关注点）
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

/**
 * 四元组数据类，用于返回多个值
 */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private data class BeautyDebugState(
    val status: BeautyPreviewStatus,
    val fps: Float,
    val processingMs: Int,
    val delayMs: Int,
    val cpuUsage: Float,
    val nullFrames: Int,
    val persistedFallback: Boolean,
    val persistedFallbackReason: String?,
    val strategy: BeautyStrategy,
    val recoveryAvailableAtMs: Long
)

private data class CameraPreviewUiState(
    val selectedFilter: FilterType,
    val facePoint: Offset?,
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

private data class CameraPreviewActions(
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

@OptIn(ExperimentalPermissionsApi::class)
@ExperimentalGetImage
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    viewModel: MediaViewModel = viewModel(
        factory = MediaViewModelFactory(
            LocalContext.current,
            (LocalContext.current.applicationContext as PicMeApplication).repository,
            OcrUseCase()
        )
    )
) {
    // [RD] 沉浸式模式：隐藏系统栏
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
    val app = context.applicationContext as PicMeApplication
    val imageProcessor = app.container.imageProcessor
    val userPreferencesRepository = app.container.userPreferencesRepository
    val coroutineScope = rememberCoroutineScope()
    val beautyStrategy by userPreferencesRepository.beautyStrategyFlow.collectAsState(
        initial = BeautyStrategy.R_PLAN
    )
    val debugUiEnabled by userPreferencesRepository.debugUiEnabledFlow.collectAsState(initial = true)
    val faceLandmarkModeEnabled by userPreferencesRepository.faceDetectionLandmarkModeFlow.collectAsState(initial = true)
    val adaptiveFaceDetectIntervalEnabled by userPreferencesRepository.adaptiveFaceDetectionIntervalEnabledFlow.collectAsState(initial = true)
    val faceDetectIntervalProfile by userPreferencesRepository.faceDetectIntervalProfileFlow.collectAsState(
        initial = FaceDetectIntervalProfile.BALANCED
    )
    val rPlanRecoveryAvailableAtMs by userPreferencesRepository.rPlanRecoveryAvailableAtFlow.collectAsState(initial = 0L)
    val lifecycleOwner = LocalLifecycleOwner.current
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
    
    // [RD] 监听 SurfaceTexture 准备状态
    var surfaceTextureReady by remember { mutableStateOf(false) }
    
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings(enabled = true)) }
    var debouncedBeautySettings by remember { mutableStateOf(beautySettings) }
    val effectiveBeautySettings by rememberUpdatedState(newValue = debouncedBeautySettings)
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }

    // [RD] 使用 PreviewView 替代 TextureView
    // 优势：
    // 1. CameraX 官方推荐，自动处理旋转和缩放
    // 2. 支持 ScaleType 配置（FIT_CENTER, FILL_CENTER 等）
    // 3. 后续可以通过 PreviewView.getBitmap() 获取帧数据实现美颜
    // 4. 或者通过 Preview.SurfaceProvider 自定义 Surface 接入 PixelFree SDK
    val previewView = remember {
        PreviewView(context).apply {
            // [关键配置] 根据比例模式设置 ScaleType
            // - FIT_CENTER: 保持比例，画面完整显示（可能有黑边）
            // - FILL_CENTER: 裁剪填充，铺满屏幕（FULL 模式）
            scaleType = when (aspectRatio) {
                AspectRatio.RATIO_FULL -> PreviewView.ScaleType.FILL_CENTER  // FULL: 铺满屏幕
                else -> PreviewView.ScaleType.FIT_CENTER  // 其他: 保持比例
            }

            implementationMode = PreviewView.ImplementationMode.COMPATIBLE

            Logger.d("Camera",
                "PreviewView created with scaleType=${scaleType}, aspectRatio=$aspectRatio"
            )
        }
    }

    // [RD] PixelFree 美颜预览View（备用链路）
    val pixelFreeView = remember {
        PixelFreeGLSurfaceView(context).apply {
            Logger.d("Camera", "PixelFreeGLSurfaceView created for real-time beauty")
        }
    }

    // [RD] R 计划提供者（主引擎参数链路）
    val rPlanPreviewProvider = remember {
        RPlanBeautyPreviewProvider(context)
    }

    var persistedRPlanFallback by remember { mutableStateOf(false) }
    var persistedRPlanFallbackReason by remember { mutableStateOf<String?>(null) }
    var autoRecoveryRequestedAtMs by remember { mutableStateOf(0L) }

    LaunchedEffect(beautyStrategy, rPlanRecoveryAvailableAtMs) {
        if (beautyStrategy == BeautyStrategy.R_PLAN) {
            persistedRPlanFallback = false
            persistedRPlanFallbackReason = null
            autoRecoveryRequestedAtMs = 0L
            return@LaunchedEffect
        }

        if (beautyStrategy != BeautyStrategy.PIXEL_FREE) {
            return@LaunchedEffect
        }

        if (rPlanRecoveryAvailableAtMs <= 0L) {
            return@LaunchedEffect
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs >= rPlanRecoveryAvailableAtMs && autoRecoveryRequestedAtMs != rPlanRecoveryAvailableAtMs) {
            autoRecoveryRequestedAtMs = rPlanRecoveryAvailableAtMs
            userPreferencesRepository.triggerManualRPlanRecovery()
            Logger.i("Camera", "Cooldown ended, auto retry R Plan strategy")
        }
    }

    val onRPlanWarmUpFallback: (String) -> Unit = { reason ->
        BeautyEngineRuntimeState.markRPlanFallback(reason)

        if (!persistedRPlanFallback) {
            persistedRPlanFallback = true
            persistedRPlanFallbackReason = reason
            coroutineScope.launch {
                userPreferencesRepository.persistRPlanFallback(R_PLAN_RECOVERY_COOLDOWN_MS)
                Logger.w(
                    "Camera",
                    "Beauty strategy persisted to PIXEL_FREE after R Plan warm-up failure, cooldown=${R_PLAN_RECOVERY_COOLDOWN_MS}ms"
                )
            }
        }
    }

    val previewStrategyBundle = rememberPreviewStrategyBundle(
        beautyStrategy = beautyStrategy,
        previewView = previewView,
        pixelFreeView = pixelFreeView,
        rPlanPreviewProvider = rPlanPreviewProvider,
        onRPlanWarmUpFallback = onRPlanWarmUpFallback
    )
    val activePreviewStrategy = previewStrategyBundle.activeStrategy

    val bindPreviewSurfaceProvider: (Preview) -> Unit = { previewUseCase ->
        activePreviewStrategy.bindPreview(previewUseCase)
    }

    // [RD] 参数更新防抖：滑杆拖动时合并高频更新，减少实时处理抖动
    LaunchedEffect(beautySettings) {
        delay(90)
        debouncedBeautySettings = beautySettings
    }

    // [RD] 同步美颜参数到当前引擎
    LaunchedEffect(debouncedBeautySettings, beautyStrategy) {
        val settings = debouncedBeautySettings
        activePreviewStrategy.applyBeautySettings(settings)

        Logger.d(
            "Camera",
            "Beauty params updated: engine=${activePreviewStrategy.strategy.name}, smoothing=${settings.smoothing}, " +
                "whitening=${settings.whitening}, bigEyes=${settings.bigEyes}, slimFace=${settings.slimFace}"
        )
    }

    // [RD] 监听比例变化，动态调整 ScaleType
    LaunchedEffect(aspectRatio) {
        previewView.scaleType = when (aspectRatio) {
            AspectRatio.RATIO_FULL -> PreviewView.ScaleType.FILL_CENTER
            else -> PreviewView.ScaleType.FIT_CENTER
        }
        Logger.d("Camera",
            "ScaleType updated to ${previewView.scaleType} for aspectRatio=$aspectRatio"
        )
    }

    var showFilterSelector by remember { mutableStateOf(false) }
    var showBeautySelector by remember { mutableStateOf(false) }
    var showRatioSelector by remember { mutableStateOf(false) }
    var showCameraInfo by remember { mutableStateOf(false) }
    var showSceneSelector by remember { mutableStateOf(false) }
    var showGridSelector by remember { mutableStateOf(false) }
    var showLogOverlay by remember { mutableStateOf(false) }
    
    // [RD] 新增美颜子功能面板状态
    var showFacialRefinement by remember { mutableStateOf(false) }
    var showMakeupAdjustment by remember { mutableStateOf(false) }
    var showBodyManagement by remember { mutableStateOf(false) }
    
    val closePrimaryPanels = {
        showFilterSelector = false
        showBeautySelector = false
        showRatioSelector = false
        showSceneSelector = false
        showGridSelector = false
    }
    val closeBeautySubPanels = {
        showFacialRefinement = false
        showMakeupAdjustment = false
        showBodyManagement = false
    }
    val closeAllPanels = {
        closePrimaryPanels()
        closeBeautySubPanels()
    }

    // [RD] 定义美颜子功能回调函数
    val onToggleFacialRefinement = {
        closePrimaryPanels()
        showMakeupAdjustment = false
        showBodyManagement = false
        showFacialRefinement = !showFacialRefinement
    }
    val onToggleMakeupAdjustment = {
        closePrimaryPanels()
        showFacialRefinement = false
        showBodyManagement = false
        showMakeupAdjustment = !showMakeupAdjustment
    }
    val onToggleBodyManagement = {
        closePrimaryPanels()
        showFacialRefinement = false
        showMakeupAdjustment = false
        showBodyManagement = !showBodyManagement
    }

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

    // [RD] ImageCapture需要在LaunchedEffect中创建，以便1:1模式可以正确配置ViewPort
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    
    var actualLensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    var facePoint by remember { mutableStateOf(Offset.Zero) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    val focusIndicatorAlpha = remember { Animatable(0f) }

    val faceDetector = remember(faceLandmarkModeEnabled) {
        val optionsBuilder = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)

        if (faceLandmarkModeEnabled) {
            optionsBuilder
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        } else {
            optionsBuilder
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        }

        Logger.i(
            "Camera",
            "Face detector mode=${if (faceLandmarkModeEnabled) "LANDMARK" else "CONTOUR"}"
        )
        FaceDetection.getClient(optionsBuilder.build())
    }

    val mediaAssets by viewModel.allMedia.collectAsState()
    val lastMedia = mediaAssets.firstOrNull()
    var beautyPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var beautyPreviewStatus by remember { mutableStateOf(BeautyPreviewStatus.SKIPPED) }
    var beautyPreviewFps by remember { mutableFloatStateOf(0f) }
    var beautyPreviewProcessingMs by remember { mutableIntStateOf(0) }
    var beautyPreviewDelayMs by remember { mutableIntStateOf(100) }
    var beautyPreviewCpuUsage by remember { mutableFloatStateOf(0f) }
    var beautyPreviewNullFrames by remember { mutableIntStateOf(0) }

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
    
    // [RD] 第二阶段瘦身：将实时预览循环与性能监控下沉到独立函数
    LaunchedEffect(beautySettings.enabled, beautySettings.hasAnyEffect(), selectedFilter) {
        if (!beautySettings.enabled || !beautySettings.hasAnyEffect()) {
            beautyPreviewStatus = BeautyPreviewStatus.SKIPPED
            Logger.d(
                "Camera",
                "Beauty preview skipped: enabled=${beautySettings.enabled}, hasEffect=${beautySettings.hasAnyEffect()}"
            )
            beautyPreviewBitmap?.recycle()
            beautyPreviewBitmap = null
            beautyPreviewFps = 0f
            beautyPreviewProcessingMs = 0
            beautyPreviewDelayMs = 0
            beautyPreviewCpuUsage = 0f
            beautyPreviewNullFrames = 0
            return@LaunchedEffect
        }

        beautyPreviewStatus = BeautyPreviewStatus.ACTIVE
        runRealtimeBeautyPreviewLoop(
            previewView = previewView,
            faceDetector = faceDetector,
            imageProcessor = imageProcessor,
            selectedFilter = selectedFilter,
            faceDetectIntervalProfile = faceDetectIntervalProfile,
            adaptiveFaceDetectIntervalEnabled = adaptiveFaceDetectIntervalEnabled,
            beautySettingsProvider = { effectiveBeautySettings },
            onPreviewBitmapReady = { previewResult ->
                beautyPreviewBitmap?.takeIf { existingBitmap -> existingBitmap != previewResult }?.recycle()
                beautyPreviewBitmap = previewResult
            },
            onPerfUpdated = { fps, processingMs, delayMs, cpuUsage, nullFrames ->
                beautyPreviewFps = fps
                beautyPreviewProcessingMs = processingMs
                beautyPreviewDelayMs = delayMs
                beautyPreviewCpuUsage = cpuUsage
                beautyPreviewNullFrames = nullFrames
            }
        )
    }

    // [RD] 监听美颜参数变化（暂时注释，后续实现）
    // LaunchedEffect(beautySettings) {
    //     pixelFreeView.setSmoothingStrength(beautySettings.smoothing.toFloat())
    //     pixelFreeView.setWhiteningStrength(beautySettings.whitening.toFloat())
    //     pixelFreeView.setSlimFaceStrength(beautySettings.slimFace.toFloat())
    //     pixelFreeView.setBigEyesStrength(beautySettings.bigEyes.toFloat())
    //     Logger.d("Camera", "PixelFree beauty updated: smoothing=${beautySettings.smoothing}, whitening=${beautySettings.whitening}")
    // }
    
    // [RD] PixelFreeEffects 初始化
    LaunchedEffect(Unit) {
        // 等待 GLSurface 创建和初始化
        kotlinx.coroutines.delay(500)
        Logger.d("Camera", "PixelFree initialization requested")
    }

    LaunchedEffect(lensFacing, captureMode, aspectRatio) {
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
            onActualLensFacingChanged = { facing -> actualLensFacing = facing },
            onFacePointChanged = { point -> facePoint = point },
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

    DisposableEffect(Unit) {
        onDispose {
            beautyPreviewBitmap?.recycle()
            beautyPreviewBitmap = null
            previewStrategyBundle.rPlanStrategy.release()
            previewStrategyBundle.pixelFreeStrategy.release()
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
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            if (beautySettings.enabled && beautySettings.hasAnyEffect()) {
                beautyPreviewBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    },
    uiState = CameraPreviewUiState(
        selectedFilter = selectedFilter,
        facePoint = facePoint,
        focusIndicatorAlpha = focusIndicatorAlpha.value,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        showFilterSelector = showFilterSelector,
        showBeautySelector = showBeautySelector,
        showRatioSelector = showRatioSelector,
        showCameraInfo = showCameraInfo,
        showSceneSelector = showSceneSelector,
        showGridSelector = showGridSelector,
        debugUiEnabled = debugUiEnabled,
        showFacialRefinement = showFacialRefinement,
        showMakeupAdjustment = showMakeupAdjustment,
        showBodyManagement = showBodyManagement,
        currentScene = currentScene,
        currentGrid = currentGrid,
        beautySettings = beautySettings,
        beautyDebugState = BeautyDebugState(
            status = beautyPreviewStatus,
            fps = beautyPreviewFps,
            processingMs = beautyPreviewProcessingMs,
            delayMs = beautyPreviewDelayMs,
            cpuUsage = beautyPreviewCpuUsage,
            nullFrames = beautyPreviewNullFrames,
            persistedFallback = persistedRPlanFallback,
            persistedFallbackReason = persistedRPlanFallbackReason,
            strategy = beautyStrategy,
            recoveryAvailableAtMs = rPlanRecoveryAvailableAtMs
        ),
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = 0,
        exposureRange = -2..2,
        whiteBalanceMode = 0
    ),
    actions = CameraPreviewActions(
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToDebug = onNavigateToDebug,
        onFlipCamera = {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            actualLensFacing = lensFacing
        },
        onToggleBeauty = {
            val next = !showBeautySelector
            closePrimaryPanels()
            showBeautySelector = next
        },
        onToggleFilter = {
            val next = !showFilterSelector
            closePrimaryPanels()
            showFilterSelector = next
        },
        onToggleRatio = {
            val next = !showRatioSelector
            closePrimaryPanels()
            showRatioSelector = next
        },
        onToggleCameraInfo = { showCameraInfo = !showCameraInfo },
        onToggleScene = {
            val next = !showSceneSelector
            closePrimaryPanels()
            showSceneSelector = next
        },
        onToggleGrid = {
            val next = !showGridSelector
            closePrimaryPanels()
            showGridSelector = next
        },
        onToggleLogs = {
            if (debugUiEnabled) {
                showLogOverlay = !showLogOverlay
            }
        },
        onToggleFacialRefinement = onToggleFacialRefinement,
        onToggleMakeupAdjustment = onToggleMakeupAdjustment,
        onToggleBodyManagement = onToggleBodyManagement,
        onZoomPresetClick = { ratio -> cameraControl?.setZoomRatio(ratio) },
        onExposureChange = { exposure -> cameraControl?.setExposureCompensationIndex(exposure) },
        onWhiteBalanceChange = { _ -> },
        onSceneSelected = {
            currentScene = it
            showSceneSelector = false
        },
        onGridSelected = {
            currentGrid = it
            showGridSelector = false
        },
        onGalleryClick = onNavigateToGallery,
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
        onModeChange = { captureMode = it },
        onFilterSelected = { selectedFilter = it },
        onBeautySettingsChanged = { updatedSettings ->
            val onlyToggleChanged =
                beautySettings.copy(enabled = updatedSettings.enabled) == updatedSettings &&
                    beautySettings.enabled != updatedSettings.enabled

            beautySettings = when {
                onlyToggleChanged -> updatedSettings
                updatedSettings.hasAnyEffect() -> updatedSettings.copy(enabled = true)
                else -> updatedSettings.copy(enabled = false)
            }
        },
        onRatioSelected = {
            aspectRatio = it
            showRatioSelector = false
        },
        onDismissPanels = {
            closeAllPanels()
        }
    )
)

        if (debugUiEnabled && showLogOverlay) {
            LogOverlay(onDismiss = { showLogOverlay = false })
        }

}

private fun toCameraAspectRatio(aspectRatio: Int): Int {
    return when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> androidx.camera.core.AspectRatio.RATIO_16_9
        else -> androidx.camera.core.AspectRatio.RATIO_4_3
    }
}

@ExperimentalGetImage
private fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    cameraProviderFuture: com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>,
    lensFacing: Int,
    captureMode: MediaType,
    aspectRatio: Int,
    previewView: PreviewView,
    bindPreviewSurfaceProvider: (Preview) -> Unit,
    cameraExecutor: java.util.concurrent.ExecutorService,
    faceDetector: com.google.mlkit.vision.face.FaceDetector,
    beautySettings: BeautySettings,
    videoCapture: VideoCapture<Recorder>,
    onImageCaptureChanged: (ImageCapture) -> Unit,
    onCameraControlChanged: (CameraControl) -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    onActualLensFacingChanged: (Int) -> Unit,
    onFacePointChanged: (Offset) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit
) {
    val cameraProvider = cameraProviderFuture.get()
    Logger.d("Camera", "Binding camera with aspectRatio=$aspectRatio")

    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    val imageAnalysis = ImageAnalysis.Builder()
        .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    val imageCapture = ImageCapture.Builder()
        .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    onImageCaptureChanged(imageCapture)

    val useCaseGroup = if (aspectRatio == AspectRatio.RATIO_FULL) {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
            .setTargetRotation(rotation)
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }

        imageCapture.targetRotation = rotation

        val displayMetrics = context.resources.displayMetrics
        val viewport = androidx.camera.core.ViewPort.Builder(
            android.util.Rational(displayMetrics.widthPixels, displayMetrics.heightPixels),
            rotation
        ).build()

        androidx.camera.core.UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .addUseCase(imageAnalysis)
            .setViewPort(viewport)
            .build()
    } else {
        null
    }

    val preview = if (useCaseGroup == null) {
        Preview.Builder()
            .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }
    } else {
        null
    }

    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        handleImageAnalysisFrame(
            imageProxy = imageProxy,
            previewView = previewView,
            faceDetector = faceDetector,
            lensFacing = lensFacing,
            beautySettings = beautySettings,
            onFacePointChanged = onFacePointChanged,
            onShowFocusIndicatorChanged = onShowFocusIndicatorChanged
        )
    }

    try {
        cameraProvider.unbindAll()

        val camera = if (useCaseGroup != null) {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )
        } else {
            when (captureMode) {
                MediaType.PHOTO, MediaType.PORTRAIT, MediaType.PRO, MediaType.DOCUMENT -> {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview!!,
                        imageCapture,
                        imageAnalysis
                    )
                }
                MediaType.VIDEO -> {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview!!,
                        videoCapture,
                        imageAnalysis
                    )
                }
            }
        }

        onCameraControlChanged(camera.cameraControl)
        camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
            onZoomRatioChanged(state.zoomRatio)
        }
        onActualLensFacingChanged(camera.cameraInfo.lensFacing)
        Logger.d("PicMe:Camera", "Camera bound: lensFacing=${camera.cameraInfo.lensFacing}, selector=$lensFacing")
    } catch (error: Exception) {
        Logger.e("Camera", "Binding failed", error)
    }
}

@ExperimentalGetImage
private fun handleImageAnalysisFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    previewView: PreviewView,
    faceDetector: com.google.mlkit.vision.face.FaceDetector,
    lensFacing: Int,
    beautySettings: BeautySettings,
    onFacePointChanged: (Offset) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage == null || previewView.width <= 0 || previewView.height <= 0) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val bounds = faces[0].boundingBox
                    val screenPoint = transformFaceCoordinateSimple(
                        faceX = bounds.centerX().toFloat(),
                        faceY = bounds.centerY().toFloat(),
                        imageProxyWidth = imageProxy.width,
                        imageProxyHeight = imageProxy.height,
                        previewWidth = previewView.width.toFloat(),
                        previewHeight = previewView.height.toFloat(),
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        lensFacing = lensFacing
                    )
                    onFacePointChanged(screenPoint)
                    onShowFocusIndicatorChanged(true)
                } else {
                    onShowFocusIndicatorChanged(false)
                }
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

private suspend fun runRealtimeBeautyPreviewLoop(
    previewView: PreviewView,
    faceDetector: com.google.mlkit.vision.face.FaceDetector,
    imageProcessor: com.picme.core.image.ImageProcessor,
    selectedFilter: FilterType,
    faceDetectIntervalProfile: FaceDetectIntervalProfile,
    adaptiveFaceDetectIntervalEnabled: Boolean,
    beautySettingsProvider: () -> BeautySettings,
    onPreviewBitmapReady: (Bitmap) -> Unit,
    onPerfUpdated: (fps: Float, processingMs: Int, delayMs: Int, cpuUsage: Float, nullFrames: Int) -> Unit
) {
    val initialBeautySettings = beautySettingsProvider()
    Logger.i(
        "Camera",
        "Beauty preview active: filter=$selectedFilter, smooth=${initialBeautySettings.smoothing}, white=${initialBeautySettings.whitening}, slim=${initialBeautySettings.slimFace}, eye=${initialBeautySettings.bigEyes}"
    )

    var adaptiveDelay = 100L
    val minDelay = 67L
    val maxDelay = 150L
    var frameCount = 0
    var nullFrameCount = 0
    var lastFpsLogTime = System.currentTimeMillis()
    var lastCpuSampleTime = SystemClock.elapsedRealtime()
    var lastCpuTimeMs = Process.getElapsedCpuTime()
    var cachedPreviewFaces: List<Face> = emptyList()
    var lastFaceDetectAt = 0L
    val intervalParams = when (faceDetectIntervalProfile) {
        FaceDetectIntervalProfile.CONSERVATIVE -> Quadruple(320L, 520L, 35L, 10L)
        FaceDetectIntervalProfile.BALANCED -> Quadruple(280L, 450L, 25L, 15L)
        FaceDetectIntervalProfile.AGGRESSIVE -> Quadruple(220L, 360L, 20L, 25L)
    }
    var faceDetectIntervalMs = intervalParams.first
    val faceDetectIntervalMinMs = intervalParams.first
    val faceDetectIntervalMaxMs = intervalParams.second
    val detectIntervalSlowStepMs = intervalParams.third
    val detectIntervalFastStepMs = intervalParams.fourth

    while (currentCoroutineContext().isActive) {
        val frameStartTime = System.currentTimeMillis()
        val frameBitmap = previewView.bitmap

        if (frameBitmap != null) {
            val sourceBitmap = if (frameBitmap.config == Bitmap.Config.ARGB_8888 && frameBitmap.isMutable) {
                frameBitmap
            } else {
                val copiedBitmap = frameBitmap.copy(Bitmap.Config.ARGB_8888, true)
                frameBitmap.recycle()
                copiedBitmap
            }

            val currentBeautySettings = beautySettingsProvider()
            val requiresFaceDetection = currentBeautySettings.bigEyes > 0f ||
                currentBeautySettings.slimFace != 0f

            val previewResult = withContext(Dispatchers.Default) {
                try {
                    if (requiresFaceDetection) {
                        val currentTimeMs = SystemClock.elapsedRealtime()
                        if (currentTimeMs - lastFaceDetectAt >= faceDetectIntervalMs || cachedPreviewFaces.isEmpty()) {
                            cachedPreviewFaces = try {
                                val inputImage = InputImage.fromBitmap(sourceBitmap, 0)
                                Tasks.await(faceDetector.process(inputImage))
                            } catch (error: Exception) {
                                Logger.e("Camera", "Real-time preview face detect failed", error)
                                emptyList()
                            }
                            lastFaceDetectAt = currentTimeMs
                        }
                    } else {
                        cachedPreviewFaces = emptyList()
                    }

                    imageProcessor.processPhoto(
                        source = sourceBitmap,
                        filter = selectedFilter,
                        beauty = currentBeautySettings,
                        faces = cachedPreviewFaces
                    )
                } catch (error: Exception) {
                    Logger.e("Camera", "Real-time beauty preview processing failed", error)
                    sourceBitmap
                }
            }

            if (previewResult != sourceBitmap && !sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }

            onPreviewBitmapReady(previewResult)

            val processingTime = System.currentTimeMillis() - frameStartTime
            adaptiveDelay = when {
                processingTime < 80 -> maxOf(minDelay, adaptiveDelay - 10)
                processingTime > 120 -> minOf(maxDelay, adaptiveDelay + 10)
                else -> adaptiveDelay
            }

            if (adaptiveFaceDetectIntervalEnabled && requiresFaceDetection) {
                faceDetectIntervalMs = when {
                    processingTime > 120 -> minOf(
                        faceDetectIntervalMaxMs,
                        faceDetectIntervalMs + detectIntervalSlowStepMs
                    )
                    processingTime < 80 -> maxOf(
                        faceDetectIntervalMinMs,
                        faceDetectIntervalMs - detectIntervalFastStepMs
                    )
                    else -> faceDetectIntervalMs
                }
            } else {
                faceDetectIntervalMs = faceDetectIntervalMinMs
            }

            frameCount++
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFpsLogTime >= 1000) {
                val fps = frameCount * 1000f / (currentTime - lastFpsLogTime)
                val cpuNowMs = Process.getElapsedCpuTime()
                val cpuWallDeltaMs = (SystemClock.elapsedRealtime() - lastCpuSampleTime).coerceAtLeast(1L)
                val cpuDeltaMs = (cpuNowMs - lastCpuTimeMs).coerceAtLeast(0L)
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val cpuUsage = ((cpuDeltaMs.toFloat() / cpuWallDeltaMs.toFloat()) / cores.toFloat() * 100f)
                    .coerceIn(0f, 100f)

                onPerfUpdated(
                    fps,
                    processingTime.toInt(),
                    adaptiveDelay.toInt(),
                    cpuUsage,
                    nullFrameCount
                )

                Logger.d(
                    "Camera",
                    "Beauty preview FPS: ${"%.1f".format(fps)}, processing=${processingTime}ms, delay=${adaptiveDelay}ms, profile=${faceDetectIntervalProfile.name}, faceDetectInterval=${faceDetectIntervalMs}ms, cpu=${"%.1f".format(cpuUsage)}%, nullFrame=$nullFrameCount"
                )
                frameCount = 0
                nullFrameCount = 0
                lastFpsLogTime = currentTime
                lastCpuSampleTime = SystemClock.elapsedRealtime()
                lastCpuTimeMs = cpuNowMs
            }
        } else {
            nullFrameCount++
        }

        delay(adaptiveDelay)
    }
}

@SuppressLint("MissingPermission")
private fun handleCaptureClick(
    context: Context,
    captureMode: MediaType,
    isRecording: Boolean,
    recording: Recording?,
    videoCapture: VideoCapture<Recorder>,
    viewModel: MediaViewModel,
    imageCapture: ImageCapture?,
    imageProcessor: com.picme.core.image.ImageProcessor,
    selectedFilter: FilterType,
    beautySettings: BeautySettings,
    lensFacing: Int,
    onRecordingChanged: (Recording?) -> Unit,
    onIsRecordingChanged: (Boolean) -> Unit
) {
    if (captureMode != MediaType.VIDEO) {
        imageCapture?.let { capture ->
            imageProcessor.takePhoto(
                context = context,
                imageCapture = capture,
                viewModel = viewModel,
                filter = selectedFilter,
                beauty = beautySettings,
                lensFacing = lensFacing,
                mode = captureMode
            )
        }
        return
    }

    if (isRecording) {
        recording?.stop()
        onRecordingChanged(null)
        onIsRecordingChanged(false)
        return
    }

    val name = "PicMe_" + System.currentTimeMillis() + ".mp4"
    val contentValues = android.content.ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicMe")
    }
    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
        .setContentValues(contentValues)
        .build()

    onIsRecordingChanged(true)
    val newRecording = videoCapture.output
        .prepareRecording(context, mediaStoreOutputOptions)
        .withAudioEnabled()
        .start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    if (!event.hasError()) {
                        viewModel.insertMedia(
                            MediaAsset(
                                uri = event.outputResults.outputUri.toString(),
                                type = MediaType.VIDEO,
                                captureDate = System.currentTimeMillis(),
                                fileName = name
                            )
                        )
                    } else {
                        onRecordingChanged(null)
                        onIsRecordingChanged(false)
                    }
                }
            }
        }
    onRecordingChanged(newRecording)
}

@Composable
private fun CameraPreviewContent(
    previewView: @Composable () -> Unit,
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    with(uiState) {
        with(actions) {
            val isAnyPanelOpen = showFilterSelector || showBeautySelector || showRatioSelector ||
                showSceneSelector || showGridSelector

            Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center  // ✅ 居中对齐
    ) {
        // 使用 PreviewView 显示相机预览
        previewView()

        CameraOverlays(
            isStable = isStable,
            gridType = currentGrid,
            facePoint = facePoint,
            focusAlpha = focusIndicatorAlpha,
            showInfo = showCameraInfo,
            lensFacing = lensFacing,
            captureMode = captureMode,
            zoomRatio = zoomRatio,
            aspectRatio = aspectRatio,
            selectedFilter = selectedFilter,
            beautySettings = beautySettings,
            exposureCompensation = exposureCompensation,
            whiteBalanceMode = whiteBalanceMode,
            currentScene = currentScene
        )

        val statusText = if (beautyDebugState.status == BeautyPreviewStatus.ACTIVE) {
            "Beauty: ACTIVE"
        } else {
            "Beauty: SKIPPED"
        }
        val statusColor = if (beautyDebugState.status == BeautyPreviewStatus.ACTIVE) {
            Color(0xFF00C853)
        } else {
            Color(0xFFFFA000)
        }

        val activeEffects = mutableListOf<String>()
        if (beautySettings.smoothing > 0f) {
            activeEffects.add("SMOOTH")
        }
        if (beautySettings.whitening > 0f) {
            activeEffects.add("WHITE")
        }
        if (beautySettings.slimFace != 0f) {
            activeEffects.add("SLIM")
        }
        if (beautySettings.bigEyes > 0f) {
            activeEffects.add("EYE")
        }
        val effectsText = if (activeEffects.isEmpty()) {
            "Effects: None"
        } else {
            "Effects: " + activeEffects.joinToString(",")
        }
        val perfText = "Perf: ${"%.1f".format(beautyDebugState.fps)}fps " +
            "${beautyDebugState.processingMs}ms ${beautyDebugState.delayMs}ms CPU ${"%.1f".format(beautyDebugState.cpuUsage)}%"
        val dropText = "Drop: ${beautyDebugState.nullFrames}"
        val nowMs = System.currentTimeMillis()
        val hasPersistedFallback = beautyDebugState.strategy == BeautyStrategy.PIXEL_FREE && beautyDebugState.recoveryAvailableAtMs > 0L
        val fallbackStateText = if (hasPersistedFallback) {
            val reasonText = beautyDebugState.persistedFallbackReason ?: "runtime failure"
            val remainingMs = (beautyDebugState.recoveryAvailableAtMs - nowMs).coerceAtLeast(0L)
            val remainingSec = remainingMs / 1000L
            if (remainingMs > 0L) {
                "Fallback: PERSISTED -> PIXEL_FREE (${remainingSec}s, $reasonText)"
            } else {
                "Fallback: READY_TO_RECOVER ($reasonText)"
            }
        } else {
            "Fallback: NONE"
        }

        if (debugUiEnabled) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 76.dp, top = 18.dp)
                    .background(statusColor.copy(alpha = 0.75f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusText,
                    color = Color.White
                )
                Text(
                    text = effectsText,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 10.sp
                )
                Text(
                    text = perfText,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 10.sp
                )
                Text(
                    text = dropText,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 10.sp
                )
                Text(
                    text = fallbackStateText,
                    color = if (hasPersistedFallback || beautyDebugState.persistedFallback) {
                        Color(0xFFFFE082)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    fontSize = 10.sp
                )
            }
        }

        CameraLeftControls(
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToDebug = onNavigateToDebug,
            onToggleCameraInfo = onToggleCameraInfo,
            onToggleLogs = onToggleLogs,
            isCameraInfoSelected = showCameraInfo,
            showDebugTools = debugUiEnabled,
            modifier = Modifier.align(Alignment.TopStart)
        )

        CameraRightControls(
            onToggleBeauty = onToggleBeauty,
            onToggleFilter = onToggleFilter,
            onToggleRatio = onToggleRatio,
            onToggleScene = onToggleScene,
            onToggleGrid = onToggleGrid,
            onToggleFacialRefinement = onToggleFacialRefinement,
            onToggleMakeupAdjustment = onToggleMakeupAdjustment,
            onToggleBodyManagement = onToggleBodyManagement,
            onToggleBeautyEnabled = { onBeautySettingsChanged(beautySettings.copy(enabled = !beautySettings.enabled)) },
            isBeautySelected = showFacialRefinement || showMakeupAdjustment || showBodyManagement || showBeautySelector,
            isFilterSelected = showFilterSelector,
            isRatioSelected = showRatioSelector,
            isSceneActive = currentScene != ScenePreset.NONE,
            isGridActive = showGridSelector,
            isBeautyEnabled = beautySettings.enabled,
            isFacialRefinementSelected = showFacialRefinement,
            isMakeupAdjustmentSelected = showMakeupAdjustment,
            isBodyManagementSelected = showBodyManagement,
            currentRatio = aspectRatio,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        if (captureMode == MediaType.PRO && !isAnyPanelOpen) {
            ProModeControls(
                exposure = exposureCompensation,
                exposureRange = exposureRange,
                onExposureChange = onExposureChange,
                whiteBalance = whiteBalanceMode,
                onWhiteBalanceChange = onWhiteBalanceChange,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 200.dp)
            )
        }

        CameraBottomControls(
            lastMedia = lastMedia,
            zoomRatio = zoomRatio,
            captureMode = captureMode,
            isRecording = isRecording,
            isAnyPanelOpen = isAnyPanelOpen,
            onZoomPresetClick = onZoomPresetClick,
            onGalleryClick = onGalleryClick,
            onCaptureClick = onCaptureClick,
            onFlipCamera = onFlipCamera,
            onModeChange = onModeChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // [RD] 美颜子功能面板显示
        val isAnyBeautyPanelOpen = showFacialRefinement || showMakeupAdjustment || showBodyManagement
        
        AnimatedVisibility(
            visible = isAnyBeautyPanelOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val title = when {
                showFacialRefinement -> stringResource(R.string.facial_refinement)
                showMakeupAdjustment -> stringResource(R.string.makeup_adjustment)
                showBodyManagement -> stringResource(R.string.body_management)
                else -> ""
            }

            ControlPanel(
                title = title,
                onDismiss = onDismissPanels
            ) {
                when {
                    showFacialRefinement -> FacialRefinementSelector(beautySettings) { onBeautySettingsChanged(it) }
                    showMakeupAdjustment -> MakeupAdjustmentSelector(beautySettings) { onBeautySettingsChanged(it) }
                    showBodyManagement -> BodyManagementSelector(beautySettings) { onBeautySettingsChanged(it) }
                }
            }
        }

        if (captureMode == MediaType.DOCUMENT && !isAnyPanelOpen) {
            DocumentDetectionOverlay(
                documentBounds = androidx.compose.ui.geometry.Rect.Zero,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = isAnyPanelOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val title = when {
                showFilterSelector -> stringResource(R.string.filters)
                showBeautySelector -> stringResource(R.string.beauty)
                showRatioSelector -> stringResource(R.string.aspect_ratio)
                showSceneSelector -> stringResource(R.string.scene)
                showGridSelector -> stringResource(R.string.grid)
                else -> ""
            }

            ControlPanel(
                title = title,
                onDismiss = onDismissPanels
            ) {
                when {
                    showFilterSelector -> FilterSelector(selectedFilter) { onFilterSelected(it) }
                    showBeautySelector -> BeautySelector(beautySettings) { settings ->
                        onBeautySettingsChanged(settings)
                    }
                    showRatioSelector -> RatioSelector(
                        selectedRatio = when (aspectRatio) {
                            AspectRatio.RATIO_4_3 -> CameraAspectRatio.RATIO_4_3
                            AspectRatio.RATIO_16_9 -> CameraAspectRatio.RATIO_16_9
                            AspectRatio.RATIO_FULL -> CameraAspectRatio.RATIO_FULL
                            else -> CameraAspectRatio.RATIO_FULL
                        },
                        onRatioSelected = {
                            onRatioSelected(
                                when (it) {
                                    CameraAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                                    CameraAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                                    CameraAspectRatio.RATIO_FULL -> AspectRatio.RATIO_FULL
                                }
                            )
                        }
                    )
                    showSceneSelector -> SceneSelector(currentScene) { onSceneSelected(it) }
                    showGridSelector -> GridSelector(currentGrid) { onGridSelected(it) }
                }
            }
            }
        }
    }
}
}

/**
 * [RD] 简化版人脸坐标转换（用于 BeautyTextureView）
 */
/**
 * [FIX] 简化版人脸坐标转换函数
 * 
 * 【问题】原函数没有考虑相机画面的实际显示比例，导致坐标偏移
 * 【解决】使用与 transformFaceCoordinate 相同的逻辑，但适配 TextureView
 */
private fun transformFaceCoordinateSimple(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
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
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step1 [归一化]: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
            "norm=($normX,$normY)"
    )
    
    // ========== Step 2: 镜像处理（前置摄像头）==========
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step2 [镜像]: lens=${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前" else "后"}, " +
            "norm=($normX,$normY), mirrored=($mirroredX,$normY)"
    )
    
    // ========== Step 3: 旋转补偿 ==========
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)      // 竖屏：不需要调整
        90 -> Pair(mirroredX, normY)     // 顺时针 90°: 不交换 XY
        180 -> Pair(1f - mirroredX, 1f - normY) // 倒立：XY 都翻转
        270 -> Pair(mirroredX, normY)    // 逆时针 90°: 不交换 XY
        else -> Pair(mirroredX, normY)
    }
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step3 [旋转补偿]: rot=$rotationDegrees, mirrored=($mirroredX,$normY), " +
            "adjusted=($adjustedX,$adjustedY)"
    )
    
    // ========== Step 4: 转换为像素坐标 ==========
    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight
    
    android.util.Log.d(
        "PicMe:Camera",
        "Step4 [像素转换]: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}, " +
            "screen=($screenX,$screenY)"
    )
    
    return Offset(screenX, screenY)
}
