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
import com.picme.beauty.api.facedetect.EngineType
import com.picme.beauty.api.facedetect.FaceDetector
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType
import com.picme.core.common.Logger
import com.picme.di.BeautyEngineRuntimeState
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.model.FaceDetectionEngineMode
import com.picme.domain.model.InsightFaceLandmarkDetectorType
import com.picme.domain.model.InsightFaceRoiDetectorType
import com.picme.domain.repository.UserSettingsRepository
import com.picme.features.camera.preview.gl.rememberGlBeautyPreviewProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L

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
    val insightFaceRoiDetectorType: InsightFaceRoiDetectorType,
    val insightFaceLandmarkDetectorType: InsightFaceLandmarkDetectorType
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
    val insightFaceRoiDetectorType by userPreferencesRepository.insightFaceRoiDetectorTypeFlow.collectAsState(
        initial = InsightFaceRoiDetectorType.MNN
    )
    val insightFaceLandmarkDetectorType by userPreferencesRepository.insightFaceLandmarkDetectorTypeFlow.collectAsState(
        initial = InsightFaceLandmarkDetectorType.MNN
    )
    val lifecycleOwner = LocalLifecycleOwner.current

    // 监听 InsightFace 流水线配置变化并更新 FaceDetectorManager
    LaunchedEffect(insightFaceRoiDetectorType, insightFaceLandmarkDetectorType) {
        Logger.d("Camera", "=== InsightFace Config Change Detected ===")
        Logger.d("Camera", "  ROI Detector: $insightFaceRoiDetectorType")
        Logger.d("Camera", "  Landmark Detector: $insightFaceLandmarkDetectorType")

        val config = DetectionPipelineConfig(
            roiDetector = when (insightFaceRoiDetectorType) {
                InsightFaceRoiDetectorType.MEDIAPIPE -> RoiDetectorType.MEDIAPIPE
                InsightFaceRoiDetectorType.DET10G -> RoiDetectorType.DET10G
                InsightFaceRoiDetectorType.MNN -> RoiDetectorType.MNN
            },
            landmarkDetector = when (insightFaceLandmarkDetectorType) {
                InsightFaceLandmarkDetectorType.INSIGHTFACE_2D106 -> LandmarkDetectorType.INSIGHTFACE_2D106
                InsightFaceLandmarkDetectorType.MEDIAPIPE -> LandmarkDetectorType.MEDIAPIPE
                InsightFaceLandmarkDetectorType.MNN -> LandmarkDetectorType.MNN
            }
        )
        
        Logger.d("Camera", "  Converting to internal config:")
        Logger.d("Camera", "    roiDetector=${config.roiDetector}")
        Logger.d("Camera", "    landmarkDetector=${config.landmarkDetector}")
        
        faceDetectorManager.updatePipelineConfig(config)
        Logger.d("Camera", "  Pipeline config updated successfully")
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
        insightFaceRoiDetectorType = insightFaceRoiDetectorType,
        insightFaceLandmarkDetectorType = insightFaceLandmarkDetectorType
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

