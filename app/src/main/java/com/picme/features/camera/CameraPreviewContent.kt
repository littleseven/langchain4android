package com.picme.features.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.domain.model.MediaType
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

@Composable
internal fun CameraPreviewContent(
    previewView: @Composable () -> Unit,
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    val isAnyPanelOpen = uiState.showFilterSelector || uiState.showBeautySelector || uiState.showRatioSelector ||
        uiState.showSceneSelector || uiState.showGridSelector

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        previewView()

        CameraOverlays(
            isStable = uiState.isStable,
            gridType = uiState.currentGrid,
            facePoint = uiState.facePoint,
            focusAlpha = uiState.focusIndicatorAlpha,
            showInfo = uiState.showCameraInfo,
            lensFacing = uiState.lensFacing,
            captureMode = uiState.captureMode,
            zoomRatio = uiState.zoomRatio,
            aspectRatio = uiState.aspectRatio,
            selectedFilter = uiState.selectedFilter,
            beautySettings = uiState.beautySettings,
            exposureCompensation = uiState.exposureCompensation,
            whiteBalanceMode = uiState.whiteBalanceMode,
            currentScene = uiState.currentScene
        )

        if (uiState.showFaceDebugOverlay) {
            FaceDebugOverlay(
                faceWarpParams = uiState.faceWarpParams,
                slimFaceValue = uiState.beautySettings.slimFace
            )
        }

        CameraPreviewDebugStatus(uiState = uiState)
        CameraPreviewSideControls(uiState = uiState, actions = actions)

        if (uiState.captureMode == MediaType.PRO && !isAnyPanelOpen) {
            ProModeControls(
                exposure = uiState.exposureCompensation,
                exposureRange = uiState.exposureRange,
                onExposureChange = actions.onExposureChange,
                whiteBalance = uiState.whiteBalanceMode,
                onWhiteBalanceChange = actions.onWhiteBalanceChange,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 200.dp)
            )
        }

        CameraBottomControls(
            lastMedia = uiState.lastMedia,
            zoomRatio = uiState.zoomRatio,
            captureMode = uiState.captureMode,
            isRecording = uiState.isRecording,
            isAnyPanelOpen = isAnyPanelOpen,
            onZoomPresetClick = actions.onZoomPresetClick,
            onGalleryClick = actions.onGalleryClick,
            onCaptureClick = actions.onCaptureClick,
            onFlipCamera = actions.onFlipCamera,
            onModeChange = actions.onModeChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        BeautySubPanels(uiState = uiState, actions = actions)

        if (uiState.captureMode == MediaType.DOCUMENT && !isAnyPanelOpen) {
            DocumentDetectionOverlay(
                documentBounds = androidx.compose.ui.geometry.Rect.Zero,
                modifier = Modifier.fillMaxSize()
            )
        }

        PrimaryControlPanels(
            uiState = uiState,
            actions = actions,
            isAnyPanelOpen = isAnyPanelOpen
        )
    }
}

@Composable
private fun BoxScope.CameraPreviewDebugStatus(uiState: CameraPreviewUiState) {
    if (!uiState.debugUiEnabled) {
        return
    }

    val statusText = if (uiState.beautyDebugState.status == BeautyPreviewStatus.ACTIVE) {
        "Beauty: ACTIVE"
    } else {
        "Beauty: SKIPPED"
    }
    val statusColor = if (uiState.beautyDebugState.status == BeautyPreviewStatus.ACTIVE) {
        Color(0xFF00C853)
    } else {
        Color(0xFFFFA000)
    }

    val activeEffects = mutableListOf<String>()
    if (uiState.beautySettings.smoothing > 0f) {
        activeEffects.add("SMOOTH")
    }
    if (uiState.beautySettings.whitening > 0f) {
        activeEffects.add("WHITE")
    }
    if (uiState.beautySettings.slimFace != 0f) {
        activeEffects.add("SLIM")
    }
    if (uiState.beautySettings.bigEyes > 0f) {
        activeEffects.add("EYE")
    }
    // 妆容调节
    if (uiState.beautySettings.lipColor > 0f) {
        activeEffects.add("LIP(${uiState.beautySettings.lipColor.toInt()})#${uiState.beautySettings.lipColorIndex}")
    }
    if (uiState.beautySettings.blush > 0f) {
        activeEffects.add("BLUSH")
    }
    if (uiState.beautySettings.eyebrow > 0f) {
        activeEffects.add("BROW")
    }

    val effectsText = if (activeEffects.isEmpty()) {
        "Effects: None"
    } else {
        "Effects: " + activeEffects.joinToString(",")
    }

    val perfText = "Perf: ${"%.1f".format(uiState.beautyDebugState.fps)}fps " +
        "${uiState.beautyDebugState.processingMs}ms ${uiState.beautyDebugState.delayMs}ms " +
        "CPU ${"%.1f".format(uiState.beautyDebugState.cpuUsage)}%"
    val dropText = "Drop: ${uiState.beautyDebugState.nullFrames}"
    val nowMs = System.currentTimeMillis()
    val hasPersistedFallback =
        uiState.beautyDebugState.strategy == com.picme.data.preferences.BeautyStrategy.PIXEL_FREE &&
            uiState.beautyDebugState.recoveryAvailableAtMs > 0L
    val fallbackStateText = if (hasPersistedFallback) {
        val reasonText = uiState.beautyDebugState.persistedFallbackReason ?: "runtime failure"
        val remainingMs = (uiState.beautyDebugState.recoveryAvailableAtMs - nowMs).coerceAtLeast(0L)
        val remainingSec = remainingMs / 1000L
        if (remainingMs > 0L) {
            "Fallback: PERSISTED -> PIXEL_FREE (${remainingSec}s, $reasonText)"
        } else {
            "Fallback: READY_TO_RECOVER ($reasonText)"
        }
    } else {
        "Fallback: NONE"
    }

    val pixelFreeLinkText = if (uiState.beautyDebugState.strategy == com.picme.data.preferences.BeautyStrategy.PIXEL_FREE) {
        when (uiState.beautyDebugState.pixelFreeLinkMode) {
            com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode.PROVIDER -> "PixelFree Link: PROVIDER(realtime)"
            com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode.RAW -> "PixelFree Link: RAW(no warp realtime)"
            com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode.PREVIEW_FALLBACK -> "PixelFree Link: PREVIEW_FALLBACK"
            null -> "PixelFree Link: UNKNOWN"
        }
    } else {
        "PixelFree Link: N/A"
    }
    val pixelFreeLinkReasonText = uiState.beautyDebugState.pixelFreeLinkReason?.let { reason ->
        val mappedReason = mapProviderFailReason(reason)
        "Provider失败: $mappedReason"
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = 76.dp, top = 18.dp)
            .background(statusColor.copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = statusText, color = Color.White)
        Text(text = effectsText, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
        Text(text = perfText, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
        Text(text = dropText, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
        Text(
            text = fallbackStateText,
            color = if (hasPersistedFallback || uiState.beautyDebugState.persistedFallback) {
                Color(0xFFFFE082)
            } else {
                Color.White.copy(alpha = 0.9f)
            },
            fontSize = 10.sp
        )
        Text(
            text = pixelFreeLinkText,
            color = when (uiState.beautyDebugState.pixelFreeLinkMode) {
                com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode.PROVIDER -> Color(0xFF80D8FF)
                com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode.RAW -> Color(0xFFFFCC80)
                com.picme.features.camera.preview.pixelfree.PixelFreePreviewLinkMode.PREVIEW_FALLBACK -> Color(0xFFE57373)
                null -> Color.White.copy(alpha = 0.85f)
            },
            fontSize = 10.sp
        )
        if (pixelFreeLinkReasonText != null) {
            Text(
                text = pixelFreeLinkReasonText,
                color = Color(0xFFFFCDD2),
                fontSize = 10.sp
            )
        }
        
        // 唇色调试信息
        val lipDebugText = buildString {
            append("LIP: ${uiState.beautySettings.lipColor.toInt()}% #${uiState.beautySettings.lipColorIndex}")
            if (uiState.beautySettings.lipColor > 0) {
                append(" | ${uiState.beautyDebugState.strategy.name}")
                if (uiState.beautyDebugState.strategy == com.picme.data.preferences.BeautyStrategy.PIXEL_FREE) {
                    append(":${uiState.beautyDebugState.pixelFreeLinkMode?.name ?: "N/A"}")
                }
                // 提示：预览不支持唇色，只在拍照后处理
                append(" | Preview:NOT_SUPPORTED")
            }
        }
        Text(
            text = lipDebugText,
            color = if (uiState.beautySettings.lipColor > 0) Color(0xFFFF80AB) else Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp
        )
        
        // 提示信息
        if (uiState.beautySettings.lipColor > 0) {
            Text(
                text = "Note: Lip color only works in captured photo",
                color = Color(0xFFFFB74D),
                fontSize = 9.sp
            )
        }
        
        // 人脸检测调试信息
        val hasFace = uiState.faceWarpParams.hasFace
        val faceDebugText = buildString {
            append("Face: ${if (hasFace) "DETECTED" else "NONE"}")
            if (hasFace) {
                append(" | Center: (${"%.2f".format(uiState.faceWarpParams.faceCenterX)}, ${"%.2f".format(uiState.faceWarpParams.faceCenterY)})")
                append(" | Radius: ${"%.2f".format(uiState.faceWarpParams.faceRadius)}")
            }
        }
        Text(
            text = faceDebugText,
            color = if (hasFace) Color(0xFF80D8FF) else Color(0xFFFFA000),
            fontSize = 10.sp
        )
    }
}

private fun mapProviderFailReason(reason: String): String {
    val normalizedReason = reason.lowercase()
    return when {
        normalizedReason.contains("provider view is null") -> "Provider视图缺失"
        normalizedReason.contains("surface not ready") || normalizedReason.contains("surface unavailable") -> "相机Surface未就绪"
        normalizedReason.contains("egl") -> "EGL初始化/绑定失败"
        normalizedReason.contains("timeout") -> "Provider启动超时"
        normalizedReason.contains("resolution") || normalizedReason.contains("buffer") -> "相机缓冲区配置失败"
        normalizedReason.contains("provider unavailable") -> "Provider不可用"
        normalizedReason.contains("stability mode") -> "稳定模式：使用PreviewView"
        else -> "未知Provider失败"
    }
}

@Composable
private fun BoxScope.CameraPreviewSideControls(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    CameraLeftControls(
        onNavigateToSettings = actions.onNavigateToSettings,
        onNavigateToDebug = actions.onNavigateToDebug,
        onToggleCameraInfo = actions.onToggleCameraInfo,
        onToggleLogs = actions.onToggleLogs,
        onToggleFaceDebug = actions.onToggleFaceDebugOverlay,
        isCameraInfoSelected = uiState.showCameraInfo,
        isFaceDebugSelected = uiState.showFaceDebugOverlay,
        showDebugTools = uiState.debugUiEnabled,
        modifier = Modifier.align(Alignment.TopStart)
    )

    CameraRightControls(
        onToggleBeauty = actions.onToggleBeauty,
        onToggleFilter = actions.onToggleFilter,
        onToggleRatio = actions.onToggleRatio,
        onToggleScene = actions.onToggleScene,
        onToggleGrid = actions.onToggleGrid,
        onToggleFacialRefinement = actions.onToggleFacialRefinement,
        onToggleMakeupAdjustment = actions.onToggleMakeupAdjustment,
        onToggleBodyManagement = actions.onToggleBodyManagement,
        onToggleBeautyEnabled = {
            actions.onBeautySettingsChanged(
                uiState.beautySettings.copy(enabled = !uiState.beautySettings.enabled)
            )
        },
        isBeautySelected = uiState.showFacialRefinement ||
            uiState.showMakeupAdjustment ||
            uiState.showBodyManagement ||
            uiState.showBeautySelector,
        isFilterSelected = uiState.showFilterSelector,
        isRatioSelected = uiState.showRatioSelector,
        isSceneActive = uiState.currentScene != ScenePreset.NONE,
        isGridActive = uiState.showGridSelector,
        isBeautyEnabled = uiState.beautySettings.enabled,
        isFacialRefinementSelected = uiState.showFacialRefinement,
        isMakeupAdjustmentSelected = uiState.showMakeupAdjustment,
        isBodyManagementSelected = uiState.showBodyManagement,
        currentRatio = uiState.aspectRatio,
        modifier = Modifier.align(Alignment.TopEnd)
    )
}

@Composable
private fun BoxScope.BeautySubPanels(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    val isAnyBeautyPanelOpen =
        uiState.showFacialRefinement || uiState.showMakeupAdjustment || uiState.showBodyManagement

    AnimatedVisibility(
        visible = isAnyBeautyPanelOpen,
        enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        val title = when {
            uiState.showFacialRefinement -> stringResource(R.string.facial_refinement)
            uiState.showMakeupAdjustment -> stringResource(R.string.makeup_adjustment)
            uiState.showBodyManagement -> stringResource(R.string.body_management)
            else -> ""
        }

        ControlPanel(title = title, onDismiss = actions.onDismissPanels) {
            when {
                uiState.showFacialRefinement -> {
                    FacialRefinementSelector(uiState.beautySettings) { updatedSettings ->
                        actions.onBeautySettingsChanged(updatedSettings)
                    }
                }
                uiState.showMakeupAdjustment -> {
                    MakeupAdjustmentSelector(uiState.beautySettings) { updatedSettings ->
                        actions.onBeautySettingsChanged(updatedSettings)
                    }
                }
                uiState.showBodyManagement -> {
                    BodyManagementSelector(uiState.beautySettings) { updatedSettings ->
                        actions.onBeautySettingsChanged(updatedSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.PrimaryControlPanels(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions,
    isAnyPanelOpen: Boolean
) {
    AnimatedVisibility(
        visible = isAnyPanelOpen,
        enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        val title = when {
            uiState.showFilterSelector -> stringResource(R.string.filters)
            uiState.showBeautySelector -> stringResource(R.string.beauty)
            uiState.showRatioSelector -> stringResource(R.string.aspect_ratio)
            uiState.showSceneSelector -> stringResource(R.string.scene)
            uiState.showGridSelector -> stringResource(R.string.grid)
            else -> ""
        }

        ControlPanel(title = title, onDismiss = actions.onDismissPanels) {
            when {
                uiState.showFilterSelector -> {
                    FilterSelector(uiState.selectedFilter) { selected -> actions.onFilterSelected(selected) }
                }
                uiState.showBeautySelector -> {
                    BeautySelector(uiState.beautySettings) { updatedSettings ->
                        actions.onBeautySettingsChanged(updatedSettings)
                    }
                }
                uiState.showRatioSelector -> {
                    RatioSelector(
                        selectedRatio = when (uiState.aspectRatio) {
                            AspectRatio.RATIO_4_3 -> CameraAspectRatio.RATIO_4_3
                            AspectRatio.RATIO_16_9 -> CameraAspectRatio.RATIO_16_9
                            AspectRatio.RATIO_FULL -> CameraAspectRatio.RATIO_FULL
                            else -> CameraAspectRatio.RATIO_FULL
                        },
                        onRatioSelected = { selectedRatio ->
                            actions.onRatioSelected(
                                when (selectedRatio) {
                                    CameraAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                                    CameraAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                                    CameraAspectRatio.RATIO_FULL -> AspectRatio.RATIO_FULL
                                }
                            )
                        }
                    )
                }
                uiState.showSceneSelector -> {
                    SceneSelector(uiState.currentScene) { selectedScene ->
                        actions.onSceneSelected(selectedScene)
                    }
                }
                uiState.showGridSelector -> {
                    GridSelector(uiState.currentGrid) { selectedGrid ->
                        actions.onGridSelected(selectedGrid)
                    }
                }
            }
        }
    }
}

