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
import com.picme.core.image.pixelfree.PixelFreeGLSurfaceView
import com.picme.core.image.rplan.RPlanBeautyPreviewProvider
import com.picme.data.preferences.BeautyStrategy
import com.picme.di.BeautyEngineRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val R_PLAN_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L

internal data class CameraRuntimeContext(
    val imageProcessor: com.picme.core.image.ImageProcessor,
    val userPreferencesRepository: com.picme.data.preferences.UserPreferencesRepository,
    val coroutineScope: CoroutineScope,
    val beautyStrategy: BeautyStrategy,
    val debugUiEnabled: Boolean,
    val faceLandmarkModeEnabled: Boolean,
    val rPlanRecoveryAvailableAtMs: Long,
    val lifecycleOwner: androidx.lifecycle.LifecycleOwner
)

@Composable
internal fun rememberCameraRuntimeContext(context: Context): CameraRuntimeContext {
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

    return CameraRuntimeContext(
        imageProcessor = imageProcessor,
        userPreferencesRepository = userPreferencesRepository,
        coroutineScope = coroutineScope,
        beautyStrategy = beautyStrategy,
        debugUiEnabled = debugUiEnabled,
        faceLandmarkModeEnabled = faceLandmarkModeEnabled,
        rPlanRecoveryAvailableAtMs = rPlanRecoveryAvailableAtMs,
        lifecycleOwner = lifecycleOwner
    )
}

internal data class RPlanRecoveryUiState(
    val persistedFallback: Boolean,
    val persistedFallbackReason: String?,
    val onRPlanWarmUpFallback: (String) -> Unit
)

internal data class PreviewRuntimeViews(
    val previewView: PreviewView,
    val pixelFreeView: PixelFreeGLSurfaceView,
    val rPlanPreviewProvider: RPlanBeautyPreviewProvider
)

@Stable
internal class CameraPanelState {
    var showFilterSelector by mutableStateOf(false)
    var showBeautySelector by mutableStateOf(false)
    var showRatioSelector by mutableStateOf(false)
    var showSceneSelector by mutableStateOf(false)
    var showGridSelector by mutableStateOf(false)
    var showFacialRefinement by mutableStateOf(false)
    var showMakeupAdjustment by mutableStateOf(false)
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
        closePrimaryPanels()
        showFacialRefinement = false
        showBodyManagement = false
        showMakeupAdjustment = !showMakeupAdjustment
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
internal fun rememberPreviewRuntimeViews(context: Context, aspectRatio: Int): PreviewRuntimeViews {
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

    val pixelFreeView = remember {
        PixelFreeGLSurfaceView(context).apply {
            Logger.d("Camera", "PixelFreeGLSurfaceView created for real-time beauty")
        }
    }

    val rPlanPreviewProvider = remember {
        RPlanBeautyPreviewProvider(context)
    }

    return PreviewRuntimeViews(
        previewView = previewView,
        pixelFreeView = pixelFreeView,
        rPlanPreviewProvider = rPlanPreviewProvider
    )
}

@Composable
internal fun rememberRPlanRecoveryState(
    beautyStrategy: BeautyStrategy,
    rPlanRecoveryAvailableAtMs: Long,
    userPreferencesRepository: com.picme.data.preferences.UserPreferencesRepository,
    coroutineScope: CoroutineScope
): RPlanRecoveryUiState {
    var persistedFallback by remember { mutableStateOf(false) }
    var persistedFallbackReason by remember { mutableStateOf<String?>(null) }
    var autoRecoveryRequestedAtMs by remember { mutableStateOf(0L) }

    LaunchedEffect(beautyStrategy, rPlanRecoveryAvailableAtMs) {
        if (beautyStrategy == BeautyStrategy.R_PLAN) {
            persistedFallback = false
            persistedFallbackReason = null
            autoRecoveryRequestedAtMs = 0L
            return@LaunchedEffect
        }

        if (beautyStrategy != BeautyStrategy.PIXEL_FREE || rPlanRecoveryAvailableAtMs <= 0L) {
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

        if (!persistedFallback) {
            persistedFallback = true
            persistedFallbackReason = reason
            coroutineScope.launch {
                userPreferencesRepository.persistRPlanFallback(R_PLAN_RECOVERY_COOLDOWN_MS)
                Logger.w(
                    "Camera",
                    "Beauty strategy persisted to PIXEL_FREE after R Plan warm-up failure, cooldown=${R_PLAN_RECOVERY_COOLDOWN_MS}ms"
                )
            }
        }
    }

    return RPlanRecoveryUiState(
        persistedFallback = persistedFallback,
        persistedFallbackReason = persistedFallbackReason,
        onRPlanWarmUpFallback = onRPlanWarmUpFallback
    )
}

