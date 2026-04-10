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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.picme.PicMeApplication
import com.picme.core.common.Logger
import com.picme.beauty.api.BeautyPreviewEngine
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.di.BeautyEngineRuntimeState
import com.picme.domain.model.BeautyStrategy
import com.picme.domain.repository.UserSettingsRepository
import com.picme.features.camera.preview.gl.rememberGlBeautyPreviewProvider
import com.picme.features.camera.preview.pixelfree.rememberPixelFreePreviewView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L

internal data class CameraRuntimeContext(
    val imageProcessor: com.picme.core.image.ImageProcessor,
    val userPreferencesRepository: UserSettingsRepository,
    val coroutineScope: CoroutineScope,
    val beautyStrategy: BeautyStrategy,
    val debugUiEnabled: Boolean,
    val showCameraInfoInPreview: Boolean,
    val showFaceDebugOverlay: Boolean,
    val showLogOverlay: Boolean,
    val faceLandmarkModeEnabled: Boolean,
    val glRecoveryAvailableAtMs: Long,
    val lifecycleOwner: androidx.lifecycle.LifecycleOwner
)

@Composable
internal fun rememberCameraRuntimeContext(context: Context): CameraRuntimeContext {
    val app = context.applicationContext as PicMeApplication
    val imageProcessor = app.container.imageProcessor
    val userPreferencesRepository = app.container.userPreferencesRepository
    val coroutineScope = rememberCoroutineScope()
    val beautyStrategy by userPreferencesRepository.beautyStrategyFlow.collectAsState(
        initial = BeautyStrategy.BIG_BEAUTY
    )
    val debugUiEnabled by userPreferencesRepository.debugUiEnabledFlow.collectAsState(initial = true)
    val showCameraInfoInPreview by userPreferencesRepository.showCameraInfoInPreviewFlow.collectAsState(initial = false)
    val showFaceDebugOverlay by userPreferencesRepository.showFaceDebugOverlayFlow.collectAsState(initial = false)
    val showLogOverlay by userPreferencesRepository.showLogOverlayFlow.collectAsState(initial = false)
    val faceLandmarkModeEnabled by userPreferencesRepository.faceDetectionLandmarkModeFlow.collectAsState(initial = true)
    val glRecoveryAvailableAtMs by userPreferencesRepository.glEngineRecoveryAvailableAtFlow.collectAsState(initial = 0L)
    val lifecycleOwner = LocalLifecycleOwner.current

    return CameraRuntimeContext(
        imageProcessor = imageProcessor,
        userPreferencesRepository = userPreferencesRepository,
        coroutineScope = coroutineScope,
        beautyStrategy = beautyStrategy,
        debugUiEnabled = debugUiEnabled,
        showCameraInfoInPreview = showCameraInfoInPreview,
        showFaceDebugOverlay = showFaceDebugOverlay,
        showLogOverlay = showLogOverlay,
        faceLandmarkModeEnabled = faceLandmarkModeEnabled,
        glRecoveryAvailableAtMs = glRecoveryAvailableAtMs,
        lifecycleOwner = lifecycleOwner
    )
}

internal data class GlRecoveryUiState(
    val persistedFallback: Boolean,
    val persistedFallbackReason: String?,
    val onGlWarmUpFallback: (String) -> Unit
)

internal data class PreviewRuntimeViews(
    val previewView: PreviewView,
    val pixelFreeView: PixelFreeGLSurfaceView?,
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
    beautyStrategy: BeautyStrategy
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

    val pixelFreeView = rememberPixelFreePreviewView(
        context = context,
        beautyStrategy = beautyStrategy
    )

    val glPreviewProvider = rememberGlBeautyPreviewProvider(
        context = context,
        beautyStrategy = beautyStrategy
    )

    return PreviewRuntimeViews(
        previewView = previewView,
        pixelFreeView = pixelFreeView,
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
        if (beautyStrategy == BeautyStrategy.BIG_BEAUTY) {
            persistedFallback = false
            persistedFallbackReason = null
            autoRecoveryRequestedAtMs = 0L
            return@LaunchedEffect
        }

        if (beautyStrategy != BeautyStrategy.PIXEL_FREE || glRecoveryAvailableAtMs <= 0L) {
            return@LaunchedEffect
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs >= glRecoveryAvailableAtMs && autoRecoveryRequestedAtMs != glRecoveryAvailableAtMs) {
            autoRecoveryRequestedAtMs = glRecoveryAvailableAtMs
            userPreferencesRepository.triggerManualGlEngineRecovery()
            Logger.i("Camera", "Cooldown ended, auto retry R Plan strategy")
        }
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
                    "Beauty strategy persisted to PIXEL_FREE after R Plan warm-up failure, cooldown=${R_PLAN_RECOVERY_COOLDOWN_MS}ms"
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

