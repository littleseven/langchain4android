package com.picme.features.camera
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.picme.PicMeApplication
import com.picme.R
import com.picme.core.common.Logger
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_FULL }
enum class BeautyPreviewStatus { ACTIVE, SKIPPED }

private const val R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L
private const val PROVIDER_VIEW_BIND_TIMEOUT_MS = 1800L

private interface BeautyPreviewEngineStrategy {
val strategy: BeautyStrategy

/**
* @return true 表示预览绑定到了自定义渲染 Surface，需要显示 provider 自带 View。
*/
fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean

fun applyBeautySettings(settings: BeautySettings)

fun applyFaceWarpParams(params: FaceWarpParams)

fun release()
}

private class PixelFreePreviewStrategy(
    private val previewView: PreviewView,
    private val pixelFreeView: PixelFreeGLSurfaceView,
    private val rPlanPreviewProvider: RPlanBeautyPreviewProvider
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.PIXEL_FREE

override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
runCatching {
rPlanPreviewProvider.initialize()
}.onFailure { error ->
Logger.w("Camera", "PixelFree bridge warm-up failed", error)
}
Logger.i("Camera", "Preview connected via PreviewView for PixelFree strategy")
return false
}

override fun applyBeautySettings(settings: BeautySettings) {
pixelFreeView.queueEvent {
pixelFreeView.setSmoothingStrength(settings.smoothing / 100f)
pixelFreeView.setWhiteningStrength(settings.whitening / 100f)
pixelFreeView.setBigEyesStrength((settings.bigEyes / 100f * 1.35f).coerceIn(0f, 1f))
pixelFreeView.setSlimFaceStrength(((settings.slimFace + 50f) / 100f).coerceIn(0f, 1f))
}

runCatching {
rPlanPreviewProvider.initialize()
rPlanPreviewProvider.updateFilters(settings)
}.onFailure { error ->
Logger.w("Camera", "PixelFree bridge update failed", error)
}
}

override fun applyFaceWarpParams(params: FaceWarpParams) {
runCatching {
rPlanPreviewProvider.updateFaceWarpParams(
faceCenterX = params.faceCenterX,
faceCenterY = params.faceCenterY,
leftEyeX = params.leftEyeX,
leftEyeY = params.leftEyeY,
rightEyeX = params.rightEyeX,
rightEyeY = params.rightEyeY,
faceRadius = params.faceRadius,
hasFace = params.hasFace
)
}.onFailure { error ->
Logger.w("Camera", "PixelFree bridge face params update failed", error)
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

override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
    return try {
        rPlanPreviewProvider.initialize()
        rPlanPreviewProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)

        val mainExecutor = ContextCompat.getMainExecutor(previewView.context)
        previewUseCase.setSurfaceProvider { request ->
            val resolution = request.resolution
            rPlanPreviewProvider.setCameraInputBufferSize(
                width = resolution.width,
                height = resolution.height
            )
            val previewSurface = rPlanPreviewProvider.createPreviewSurface()
            request.provideSurface(previewSurface, mainExecutor) { result ->
                Logger.d("Camera", "R Plan surface request completed: $result")
            }
        }

        Logger.i("Camera", "Preview connected via R Plan provider surface, aspectRatio=$aspectRatio")
        true
    } catch (error: Throwable) {
        Logger.w("Camera", "R Plan warm-up failed, fallback to PreviewView", error)
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        onWarmUpFallback(error.message ?: "warm-up error")
        false
    }
}

override fun applyBeautySettings(settings: BeautySettings) {
try {
rPlanPreviewProvider.initialize()
rPlanPreviewProvider.updateFilters(settings)
} catch (error: Throwable) {
Logger.w("Camera", "R Plan update failed", error)
}
}

override fun applyFaceWarpParams(params: FaceWarpParams) {
runCatching {
rPlanPreviewProvider.updateFaceWarpParams(
faceCenterX = params.faceCenterX,
faceCenterY = params.faceCenterY,
leftEyeX = params.leftEyeX,
leftEyeY = params.leftEyeY,
rightEyeX = params.rightEyeX,
rightEyeY = params.rightEyeY,
faceRadius = params.faceRadius,
hasFace = params.hasFace
)
}.onFailure { error ->
Logger.w("Camera", "R Plan face params update failed", error)
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
    val pixelFreeStrategy = remember(previewView, pixelFreeView, rPlanPreviewProvider) {
        PixelFreePreviewStrategy(
            previewView = previewView,
            pixelFreeView = pixelFreeView,
            rPlanPreviewProvider = rPlanPreviewProvider
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

private data class FaceWarpParams(
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val leftEyeX: Float = 0.4f,
    val leftEyeY: Float = 0.45f,
    val rightEyeX: Float = 0.6f,
    val rightEyeY: Float = 0.45f,
    val faceRadius: Float = 0.18f,
    val hasFace: Boolean = false,
    val contourPoints: List<Offset> = emptyList(),
    val leftEyeContourPoints: List<Offset> = emptyList(),
    val rightEyeContourPoints: List<Offset> = emptyList()
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
    
    var previewRebindSignal by remember { mutableIntStateOf(0) }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings(enabled = true)) }
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

    var useProviderRenderView by remember { mutableStateOf(false) }

    val bindPreviewSurfaceProvider: (Preview) -> Unit = { previewUseCase ->
        useProviderRenderView = activePreviewStrategy.bindPreview(previewUseCase, aspectRatio)
    }

    LaunchedEffect(useProviderRenderView, beautyStrategy, previewRebindSignal) {
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        kotlinx.coroutines.delay(PROVIDER_VIEW_BIND_TIMEOUT_MS)
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        if (!rPlanPreviewProvider.isReady()) {
            Logger.w(
                "Camera",
                "Provider view bind timeout, fallback to PreviewView and request rebind"
            )
            useProviderRenderView = false
            previewRebindSignal += 1
        }
    }

    var faceWarpParams by remember { mutableStateOf(FaceWarpParams()) }

    // [RD] 快路径：参数变更立即下发到当前预览引擎，保证滑杆跟手性。
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
    var showFaceDebugOverlay by remember { mutableStateOf(false) }

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
                rPlanPreviewProvider.getPerfStats() ?: com.picme.core.image.CameraPreviewRenderer.PerfStats()
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
            onActualLensFacingChanged = { facing -> actualLensFacing = facing },
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

    DisposableEffect(Unit) {
        onDispose {
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
            val rPlanPreviewView = remember(rPlanPreviewProvider) {
                runCatching {
                    rPlanPreviewProvider.initialize()
                    rPlanPreviewProvider.getView()
                }.getOrNull()
            }

            AndroidView(
                factory = { context ->
                    android.util.Log.d("PicMe:Camera", "AndroidView factory creating FrameLayout")
                    FrameLayout(context)
                },
                modifier = Modifier.fillMaxSize(),
                update = { container ->
                    val targetView = if (useProviderRenderView) {
                        android.util.Log.d("PicMe:Camera", "AndroidView update: useProviderRenderView=true, rPlanPreviewView=$rPlanPreviewView")
                        rPlanPreviewView ?: previewView
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
    uiState = CameraPreviewUiState(
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
            fps = renderPerfStats.fps,
            processingMs = renderPerfStats.processingMs,
            delayMs = renderPerfStats.delayMs,
            cpuUsage = renderPerfStats.cpuUsage,
            nullFrames = renderPerfStats.nullFrames,
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
        onToggleFaceDebugOverlay = {
            showFaceDebugOverlay = !showFaceDebugOverlay
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
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit
) {
    android.util.Log.d("PicMe:Camera", "bindCameraUseCases START: aspectRatio=$aspectRatio, captureMode=$captureMode")
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

    var frameCount = 0
    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        frameCount++
        if (frameCount % 30 == 0) {
            android.util.Log.d("PicMe:Camera", "ImageAnalysis frame received: #${frameCount}")
            Logger.d("Camera", "ImageAnalysis frame received: #${frameCount}")
        }
        handleImageAnalysisFrame(
            imageProxy = imageProxy,
            previewView = previewView,
            faceDetector = faceDetector,
            lensFacing = lensFacing,
            beautySettings = beautySettings,
            onFacePointChanged = onFacePointChanged,
            onFaceWarpParamsChanged = onFaceWarpParamsChanged,
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
        Logger.d(
            "PicMe:Camera",
            "Camera bound: lensFacing=${camera.cameraInfo.lensFacing}, selector=$lensFacing, " +
                "useCaseGroup=${useCaseGroup != null}, aspectRatio=$aspectRatio"
        )
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
            .addOnFailureListener { e ->
                android.util.Log.e("PicMe:Camera", "Face detection failed: ${e.message}", e)
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

        if (showFaceDebugOverlay) {
            FaceDebugOverlay(
                faceWarpParams = faceWarpParams,
                slimFaceValue = beautySettings.slimFace
            )
        }

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
            onToggleFaceDebug = onToggleFaceDebugOverlay,
            isCameraInfoSelected = showCameraInfo,
            isFaceDebugSelected = showFaceDebugOverlay,
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
@Composable
private fun FaceDebugOverlay(
    faceWarpParams: FaceWarpParams,
    slimFaceValue: Float
) {
    if (!faceWarpParams.hasFace) {
        return
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerNorm = Offset(
            x = faceWarpParams.faceCenterX.coerceIn(0f, 1f),
            y = faceWarpParams.faceCenterY.coerceIn(0f, 1f)
        )
        val leftEyeNorm = Offset(
            x = faceWarpParams.leftEyeX.coerceIn(0f, 1f),
            y = faceWarpParams.leftEyeY.coerceIn(0f, 1f)
        )
        val rightEyeNorm = Offset(
            x = faceWarpParams.rightEyeX.coerceIn(0f, 1f),
            y = faceWarpParams.rightEyeY.coerceIn(0f, 1f)
        )
        val faceRadiusNorm = faceWarpParams.faceRadius.coerceIn(0f, 1f)
        val slimFaceIntensity = (slimFaceValue / 50f).coerceIn(-1f, 1f)

        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = point.x.coerceIn(0f, 1f) * size.width,
                y = point.y.coerceIn(0f, 1f) * size.height
            )
        }

        val center = toCanvasPoint(centerNorm)
        val leftEye = toCanvasPoint(leftEyeNorm)
        val rightEye = toCanvasPoint(rightEyeNorm)
        val radiusPx = faceRadiusNorm * size.width

        // 人脸 contour 轮廓（用于核对作用区域是否偏移）
        val contourPoints = faceWarpParams.contourPoints.map { contourPoint ->
            toCanvasPoint(contourPoint)
        }
        val leftEyeContourPoints = faceWarpParams.leftEyeContourPoints.map { contourPoint ->
            toCanvasPoint(contourPoint)
        }
        val rightEyeContourPoints = faceWarpParams.rightEyeContourPoints.map { contourPoint ->
            toCanvasPoint(contourPoint)
        }

        fun drawClosedContour(points: List<Offset>, color: Color, strokeWidth: Float) {
            if (points.size < 2) {
                return
            }
            points.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth
                )
            }
            drawLine(
                color = color,
                start = points.last(),
                end = points.first(),
                strokeWidth = strokeWidth
            )
        }

        fun applySlimFaceDebug(point: Offset): Offset {
            val dirX = point.x - centerNorm.x
            val dirY = point.y - centerNorm.y
            val distance = sqrt(dirX * dirX + dirY * dirY)
            if (distance >= faceRadiusNorm || faceRadiusNorm <= 0.0001f) {
                return point
            }

            val eyeAxisXRaw = rightEyeNorm.x - leftEyeNorm.x
            val eyeAxisYRaw = rightEyeNorm.y - leftEyeNorm.y
            val eyeAxisLen = sqrt(eyeAxisXRaw * eyeAxisXRaw + eyeAxisYRaw * eyeAxisYRaw)
            val eyeAxisX = if (eyeAxisLen > 0.0001f) eyeAxisXRaw / eyeAxisLen else 1f
            val eyeAxisY = if (eyeAxisLen > 0.0001f) eyeAxisYRaw / eyeAxisLen else 0f

            val percent = 1f - distance / faceRadiusNorm
            val strength = slimFaceIntensity * percent * percent * 0.45f
            val axisOffset = (dirX * eyeAxisX + dirY * eyeAxisY) / faceRadiusNorm
            val offsetX = eyeAxisX * axisOffset * strength * faceRadiusNorm
            val offsetY = eyeAxisY * axisOffset * strength * faceRadiusNorm

            return Offset(
                x = (point.x - offsetX).coerceIn(0f, 1f),
                y = (point.y - offsetY).coerceIn(0f, 1f)
            )
        }

        drawClosedContour(
            points = contourPoints,
            color = Color.Magenta.copy(alpha = 0.9f),
            strokeWidth = 2.dp.toPx()
        )
        drawClosedContour(
            points = leftEyeContourPoints,
            color = Color.Yellow.copy(alpha = 0.95f),
            strokeWidth = 2.dp.toPx()
        )
        drawClosedContour(
            points = rightEyeContourPoints,
            color = Color.Green.copy(alpha = 0.95f),
            strokeWidth = 2.dp.toPx()
        )

        // 瘦脸形变区域可视化（与 shader 的作用半径一致）
        drawCircle(
            color = Color(0xFFFF6D00).copy(alpha = 0.12f),
            radius = radiusPx,
            center = center,
            style = Fill
        )
        drawCircle(
            color = Color(0xFFFF6D00).copy(alpha = 0.18f),
            radius = radiusPx * 0.58f,
            center = center,
            style = Fill
        )
        drawCircle(
            color = Color.Cyan.copy(alpha = 0.75f),
            radius = radiusPx,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // 瘦脸位移向量：原点(橙) -> 变形后(青)
        // 为了可视化更明显，调试展示使用放大后的位移终点。
        val debugWarpAmplifier = 6f
        val yRatios = listOf(-0.45f, -0.2f, 0f, 0.2f, 0.45f)
        yRatios.forEach { ratio ->
            val sampleY = (centerNorm.y + faceRadiusNorm * ratio).coerceIn(0f, 1f)
            val leftSample = Offset(
                x = (centerNorm.x - faceRadiusNorm * 0.72f).coerceIn(0f, 1f),
                y = sampleY
            )
            val rightSample = Offset(
                x = (centerNorm.x + faceRadiusNorm * 0.72f).coerceIn(0f, 1f),
                y = sampleY
            )
            listOf(leftSample, rightSample).forEach { samplePoint ->
                val warpedPoint = applySlimFaceDebug(samplePoint)
                val sampleCanvasPoint = toCanvasPoint(samplePoint)
                val warpedCanvasPoint = toCanvasPoint(warpedPoint)
                val debugWarpedCanvasPoint = Offset(
                    x = (
                        sampleCanvasPoint.x +
                            (warpedCanvasPoint.x - sampleCanvasPoint.x) * debugWarpAmplifier
                        ).coerceIn(0f, size.width),
                    y = (
                        sampleCanvasPoint.y +
                            (warpedCanvasPoint.y - sampleCanvasPoint.y) * debugWarpAmplifier
                        ).coerceIn(0f, size.height)
                )

                drawLine(
                    color = Color(0xFFFF6D00),
                    start = sampleCanvasPoint,
                    end = debugWarpedCanvasPoint,
                    strokeWidth = 3.dp.toPx()
                )
                drawCircle(
                    color = Color(0xFFFF6D00),
                    radius = 3.dp.toPx(),
                    center = sampleCanvasPoint
                )
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 4.dp.toPx(),
                    center = debugWarpedCanvasPoint
                )
            }
        }

        // 连线用于快速判断映射偏移
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = leftEye,
            end = rightEye,
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

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
