package com.picme.features.camera

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.picme.core.image.ImageProcessor
import com.picme.PicMeApplication
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.picme.beauty.api.facedetect.DevicePreference
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.InferenceBackendType
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType
import com.picme.core.common.Logger
import com.picme.di.BeautyEngineRuntimeState
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.DetectionModelType
import com.picme.domain.model.InferenceDevicePreference
import com.picme.domain.model.InferenceEngineType
import com.picme.domain.model.StageConfig
import com.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.first
import com.picme.features.camera.preview.gl.rememberGlBeautyPreviewProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L

// 领域模型 → 推理引擎层模型转换
private fun DetectionModelType.toRoiDetectorType(): RoiDetectorType = when (this) {
    DetectionModelType.MEDIAPIPE -> RoiDetectorType.MEDIAPIPE
    DetectionModelType.INSIGHTFACE_DET10G -> RoiDetectorType.DET10G
    DetectionModelType.INSIGHTFACE_2D106 -> error("2D106 is not a valid ROI detector model")
}

private fun DetectionModelType.toLandmarkDetectorType(): LandmarkDetectorType = when (this) {
    DetectionModelType.MEDIAPIPE -> LandmarkDetectorType.MEDIAPIPE
    DetectionModelType.INSIGHTFACE_2D106 -> LandmarkDetectorType.INSIGHTFACE_2D106
    DetectionModelType.INSIGHTFACE_DET10G -> error("DET10G is not a valid Landmark detector model")
}

private fun InferenceEngineType.toInferenceBackendType(): InferenceBackendType = when (this) {
    InferenceEngineType.ONNX -> InferenceBackendType.ONNX
    InferenceEngineType.MNN -> InferenceBackendType.MNN
    InferenceEngineType.NCNN -> InferenceBackendType.NCNN
    InferenceEngineType.TFLITE -> InferenceBackendType.TFLITE
}

private fun InferenceDevicePreference.toDevicePreference(): DevicePreference = when (this) {
    InferenceDevicePreference.AUTO -> DevicePreference.AUTO
    InferenceDevicePreference.FORCE_CPU -> DevicePreference.FORCE_CPU
    InferenceDevicePreference.FORCE_GPU -> DevicePreference.FORCE_GPU
}

internal data class CameraRuntimeContext(
    val imageProcessor: ImageProcessor,
    val userPreferencesRepository: UserSettingsRepository,
    val coroutineScope: CoroutineScope,
    val beautyStrategy: BeautyStrategy,
    val debugUiEnabled: Boolean,
    val showCameraInfoInPreview: Boolean,
    val showFaceDebugOverlay: Boolean,
    val showLogOverlay: Boolean,
    val faceDetectionEngineMode: FaceDetectionEngineMode,
    val faceLandmarkModeEnabled: Boolean,
    val glRecoveryAvailableAtMs: Long,
    val lifecycleOwner: LifecycleOwner,
    val faceDetectorManager: FaceDetector,
    val roiStageConfig: StageConfig,
    val landmarkStageConfig: StageConfig
)

@Composable
internal fun rememberCameraRuntimeContext(context: Context): CameraRuntimeContext {
    val app = context.applicationContext as PicMeApplication
    val imageProcessor = app.container.imageProcessor
    val userPreferencesRepository = app.container.userPreferencesRepository
    val faceDetectorManager = app.container.faceDetector
    val coroutineScope = rememberCoroutineScope()
    // 用 getBeautyStrategyBlocking() 同步读取初始值，避免 DataStore flow 首帧异步延迟
    // 导致先用 BIG_BEAUTY 初始化一次、再切换到真实策略的竞态（双引擎并存 → EGL_BAD_DISPLAY 黑屏）
    val beautyStrategy by userPreferencesRepository.beautyStrategyFlow.collectAsState(
        initial = userPreferencesRepository.getBeautyStrategyBlocking()
    )
    val debugUiEnabled by userPreferencesRepository.debugUiEnabledFlow.collectAsState(initial = true)
    val showCameraInfoInPreview by userPreferencesRepository.showCameraInfoInPreviewFlow.collectAsState(initial = false)
    val showFaceDebugOverlay by userPreferencesRepository.showFaceDebugOverlayFlow.collectAsState(initial = false)
    val showLogOverlay by userPreferencesRepository.showLogOverlayFlow.collectAsState(initial = false)
    val faceDetectionEngineMode by userPreferencesRepository.faceDetectionEngineModeFlow.collectAsState(
        initial = FaceDetectionEngineMode.INSIGHTFACE
    )
    val faceLandmarkModeEnabled by userPreferencesRepository.faceDetectionLandmarkModeFlow.collectAsState(initial = true)
    val glRecoveryAvailableAtMs by userPreferencesRepository.glEngineRecoveryAvailableAtFlow.collectAsState(initial = 0L)
    val roiStageConfig by userPreferencesRepository.roiStageConfigFlow.collectAsState(
        initial = StageConfig.defaultRoi()
    )
    val landmarkStageConfig by userPreferencesRepository.landmarkStageConfigFlow.collectAsState(
        initial = StageConfig.defaultLandmark()
    )
    val lifecycleOwner = LocalLifecycleOwner.current

    // [关键修复] 在 LaunchedEffect 中使用 produceState 确保首次发射后触发配置更新
    LaunchedEffect(Unit) {
        // 等待 DataStore 发射初始值
        val roiConfig = userPreferencesRepository.roiStageConfigFlow.first()
        val landmarkConfig = userPreferencesRepository.landmarkStageConfigFlow.first()

        Logger.d("Camera", "=== First-time Config Initialization ===")
        Logger.d("Camera", "  ROI Config from DataStore: $roiConfig")
        Logger.d("Camera", "  Landmark Config from DataStore: $landmarkConfig")

        // 使用 DataStore 中的阶段配置创建检测流水线
        val config = DetectionPipelineConfig(
            roiDetector = roiConfig.modelType.toRoiDetectorType(),
            landmarkDetector = landmarkConfig.modelType.toLandmarkDetectorType(),
            roiEngine = roiConfig.engineType.toInferenceBackendType(),
            landmarkEngine = landmarkConfig.engineType.toInferenceBackendType(),
            roiDevice = roiConfig.devicePreference.toDevicePreference(),
            landmarkDevice = landmarkConfig.devicePreference.toDevicePreference()
        )

        faceDetectorManager.updatePipelineConfig(config)
        Logger.d("Camera", "  Initial pipeline config applied successfully")
        Logger.d("Camera", "========================================")
    }

    return CameraRuntimeContext(
        imageProcessor = imageProcessor,
        userPreferencesRepository = userPreferencesRepository,
        coroutineScope = coroutineScope,
        beautyStrategy = beautyStrategy,
        debugUiEnabled = debugUiEnabled,
        showCameraInfoInPreview = showCameraInfoInPreview,
        showFaceDebugOverlay = showFaceDebugOverlay,
        showLogOverlay = showLogOverlay,
        faceDetectionEngineMode = faceDetectionEngineMode,
        faceLandmarkModeEnabled = faceLandmarkModeEnabled,
        glRecoveryAvailableAtMs = glRecoveryAvailableAtMs,
        lifecycleOwner = lifecycleOwner,
        faceDetectorManager = faceDetectorManager,
        roiStageConfig = roiStageConfig,
        landmarkStageConfig = landmarkStageConfig
    )
}

internal data class GlRecoveryUiState(
    val persistedFallback: Boolean,
    val persistedFallbackReason: String?,
    val onGlWarmUpFallback: (String) -> Unit
)

internal data class PreviewRuntimeViews(
    val previewView: PreviewView,
    val glPreviewProvider: BeautyPreviewEngine?
)

internal enum class MakeupEntry {
    LIP_COLOR,
    BLUSH,
    EYEBROW
}

@Stable
internal class CameraPanelState {
    var showFilterSelector by mutableStateOf(false)
    var showBeautySelector by mutableStateOf(false)
    var showRatioSelector by mutableStateOf(false)
    var showSceneSelector by mutableStateOf(false)
    var showGridSelector by mutableStateOf(false)
    var showFacialRefinement by mutableStateOf(false)
    var showMakeupAdjustment by mutableStateOf(false)
    var activeMakeupEntry by mutableStateOf(MakeupEntry.LIP_COLOR)
    var showBodyManagement by mutableStateOf(false)

    fun closePrimaryPanels() {
        showFilterSelector = false
        showBeautySelector = false
        showRatioSelector = false
        showSceneSelector = false
        showGridSelector = false
    }

    fun closeBeautySubPanels() {
        showFacialRefinement = false
        showMakeupAdjustment = false
        showBodyManagement = false
    }

    fun closeAllPanels() {
        closePrimaryPanels()
        closeBeautySubPanels()
    }

    fun toggleFacialRefinement() {
        closePrimaryPanels()
        showMakeupAdjustment = false
        showBodyManagement = false
        showFacialRefinement = !showFacialRefinement
    }

    fun toggleMakeupAdjustment() {
        openMakeupEntry(activeMakeupEntry)
    }

    fun openMakeupEntry(entry: MakeupEntry) {
        closePrimaryPanels()
        showFacialRefinement = false
        showBodyManagement = false

        val isSameEntryOpen = showMakeupAdjustment && activeMakeupEntry == entry
        if (isSameEntryOpen) {
            showMakeupAdjustment = false
            return
        }

        activeMakeupEntry = entry
        showMakeupAdjustment = true
    }

    fun toggleBodyManagement() {
        closePrimaryPanels()
        showFacialRefinement = false
        showMakeupAdjustment = false
        showBodyManagement = !showBodyManagement
    }
}

@Composable
internal fun rememberCameraPanelState(): CameraPanelState {
    return remember { CameraPanelState() }
}

@Composable
internal fun rememberPreviewRuntimeViews(
    context: Context,
    aspectRatio: Int,
    beautyStrategy: BeautyStrategy,
): PreviewRuntimeViews {
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = when (aspectRatio) {
                AspectRatio.RATIO_FULL -> PreviewView.ScaleType.FILL_CENTER
                else -> PreviewView.ScaleType.FIT_CENTER
            }
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            Logger.d("Camera", "PreviewView created with scaleType=${scaleType}, aspectRatio=$aspectRatio")
        }
    }

    val glPreviewProvider = rememberGlBeautyPreviewProvider(
        context = context,
        beautyStrategy = beautyStrategy,
)

    return PreviewRuntimeViews(
        previewView = previewView,
        glPreviewProvider = glPreviewProvider
    )
}

@Composable
internal fun rememberGlRecoveryState(
    beautyStrategy: BeautyStrategy,
    glRecoveryAvailableAtMs: Long,
    userPreferencesRepository: UserSettingsRepository,
    coroutineScope: CoroutineScope
): GlRecoveryUiState {
    var persistedFallback by remember { mutableStateOf(false) }
    var persistedFallbackReason by remember { mutableStateOf<String?>(null) }
    var autoRecoveryRequestedAtMs by remember { mutableStateOf(0L) }

    LaunchedEffect(beautyStrategy, glRecoveryAvailableAtMs) {
        persistedFallback = false
        persistedFallbackReason = null
        autoRecoveryRequestedAtMs = 0L
    }

    val onGlWarmUpFallback: (String) -> Unit = { reason ->
        BeautyEngineRuntimeState.markGlEngineFallback(reason)

        if (!persistedFallback) {
            persistedFallback = true
            persistedFallbackReason = reason
            coroutineScope.launch {
                userPreferencesRepository.persistGlEngineFallback(R_PLAN_RECOVERY_COOLDOWN_MS)
                Logger.w(
                    "Camera",
                    "R Plan warm-up failure recorded, cooldown=${R_PLAN_RECOVERY_COOLDOWN_MS}ms"
                )
            }
        }
    }

    return GlRecoveryUiState(
        persistedFallback = persistedFallback,
        persistedFallbackReason = persistedFallbackReason,
        onGlWarmUpFallback = onGlWarmUpFallback
    )
}

