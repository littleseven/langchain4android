package com.picme.features.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.domain.model.MediaType
import com.picme.features.camera.components.BeautyPanel
import com.picme.features.camera.components.CameraBottomControls
import com.picme.features.camera.components.CameraLeftControls
import com.picme.features.camera.components.CameraOverlays
import com.picme.features.camera.components.CameraRightControls
import com.picme.features.camera.components.ControlPanel
import com.picme.features.camera.components.DocumentDetectionOverlay
import com.picme.features.camera.components.FilterSelector
import com.picme.features.camera.components.GridSelector
import com.picme.features.camera.components.ProModeControls
import com.picme.features.camera.components.RatioSelector
import com.picme.features.camera.components.SceneSelector
import com.picme.features.camera.components.StyleFilterSelector

@Composable
internal fun CameraPreviewContent(
    previewView: @Composable () -> Unit,
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    // 非美颜类面板开启状态（美颜面板用独立的 BeautyPanel 渲染，不走 PrimaryControlPanels）
    val isAnyPanelOpen = uiState.showFilterSelector || uiState.showRatioSelector ||
        uiState.showSceneSelector || uiState.showGridSelector
    val isBeautyPanelOpen = uiState.showBeautySelector
    val isProPanelOpen = uiState.captureMode == MediaType.PRO

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 点击取景区空白处关闭所有面板
            .clickable(
                enabled = isAnyPanelOpen || isBeautyPanelOpen || isProPanelOpen,
                onClick = {
                    if (isProPanelOpen) {
                        actions.onModeChange(MediaType.PHOTO)
                    } else {
                        actions.onDismissPanels()
                    }
                }
            ),
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
                slimFaceValue = uiState.beautySettings.slimFace,
                aspectRatio = uiState.aspectRatio
            )
        }

        CameraPreviewDebugStatus(uiState = uiState)
        CameraPreviewSideControls(uiState = uiState, actions = actions)

        CameraBottomControls(
            lastMedia = uiState.lastMedia,
            zoomRatio = uiState.zoomRatio,
            minZoomRatio = uiState.minZoomRatio,
            maxZoomRatio = uiState.maxZoomRatio,
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

        // Pro Mode Controls - 底部 Sheet 风格，与其他面板保持一致
        // 必须在 CameraBottomControls 之后声明，确保浮层盖在固定按钮之上
        AnimatedVisibility(
            visible = isProPanelOpen && !isAnyPanelOpen,
            enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ProModeControls(
                exposure = uiState.exposureCompensation,
                exposureRange = uiState.exposureRange,
                onExposureChange = actions.onExposureChange,
                whiteBalance = uiState.whiteBalanceMode,
                onWhiteBalanceChange = actions.onWhiteBalanceChange,
                onClose = { actions.onModeChange(MediaType.PHOTO) },
                beautySettings = uiState.beautySettings,
                onBeautySettingsChanged = actions.onBeautySettingsChanged,
            )
        }

        // 美颜面板（统一入口：使用美图秀秀风格 Tab 标签页）
        AnimatedVisibility(
            visible = isBeautyPanelOpen,
            enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BeautyPanel(
                settings = uiState.beautySettings,
                onSettingsChanged = actions.onBeautySettingsChanged,
                onDismiss = actions.onDismissPanels
            )
        }

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

    val nowMs = System.currentTimeMillis()
    val hasPersistedFallback = uiState.beautyDebugState.recoveryAvailableAtMs > 0L
    val fallbackStateText = if (hasPersistedFallback) {
        val reasonText = uiState.beautyDebugState.persistedFallbackReason ?: "runtime failure"
        val remainingMs = (uiState.beautyDebugState.recoveryAvailableAtMs - nowMs).coerceAtLeast(0L)
        val remainingSec = remainingMs / 1000L
        if (remainingMs > 0L) {
            "Fallback: PERSISTED (${remainingSec}s, $reasonText)"
        } else {
            "Fallback: READY_TO_RECOVER ($reasonText)"
        }
    } else {
        "Fallback: NONE"
    }

    val lipRealtimePreviewSupported = uiState.beautyDebugState.providerRenderActive

    val lipCompactText = buildString {
        append("LIP ${uiState.beautySettings.lipColor.toInt()}% #${uiState.beautySettings.lipColorIndex}")
        append(" M:${uiState.faceWarpParams.lipOuterContourPoints.size}/${uiState.faceWarpParams.lipInnerContourPoints.size}")
        append(" P:${if (lipRealtimePreviewSupported) "OK" else "FB"}")
    }

    val hasFace = uiState.faceWarpParams.hasFace
    val faceCompactText = if (hasFace) {
        "Face OK C(${"%.2f".format(uiState.faceWarpParams.faceCenterX)},${"%.2f".format(uiState.faceWarpParams.faceCenterY)}) R${"%.2f".format(uiState.faceWarpParams.faceRadius)}"
    } else {
        "Face NONE"
    }

    val effectsCompact = if (activeEffects.isEmpty()) {
        "FX None"
    } else {
        "FX ${activeEffects.joinToString("/")}"
    }
    val perfCompact = "FPS ${"%.1f".format(uiState.beautyDebugState.fps)} | ${uiState.beautyDebugState.processingMs}ms/${uiState.beautyDebugState.delayMs}ms | D${uiState.beautyDebugState.nullFrames}"

    var debugExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 14.dp)
            .width(248.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable { debugExpanded = !debugExpanded }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val compactTitle = "$statusText  ${"%.1f".format(uiState.beautyDebugState.fps)}fps"
        Text(
            text = if (debugExpanded) "$compactTitle  ▲" else "$compactTitle  ▼",
            color = statusColor,
            fontSize = 10.sp
        )

        AnimatedVisibility(visible = debugExpanded) {
            Column {
                Text(text = effectsCompact, color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                Text(text = perfCompact, color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                Text(
                    text = fallbackStateText,
                    color = if (hasPersistedFallback || uiState.beautyDebugState.persistedFallback) {
                        Color(0xFFFFE082)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    fontSize = 9.sp
                )

                Text(
                    text = lipCompactText,
                    color = if (uiState.beautySettings.lipColor > 0) Color(0xFFFF80AB) else Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp
                )
                Text(
                    text = faceCompactText,
                    color = if (hasFace) Color(0xFF80D8FF) else Color(0xFFFFA000),
                    fontSize = 9.sp
                )
            }
        }
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
        modifier = Modifier.align(Alignment.TopStart)
    )

    CameraRightControls(
        onToggleBeauty = actions.onToggleBeauty,
        onToggleFilter = actions.onToggleFilter,
        onToggleRatio = actions.onToggleRatio,
        onToggleScene = actions.onToggleScene,
        onToggleGrid = actions.onToggleGrid,
        onToggleBeautyEnabled = {
            actions.onBeautySettingsChanged(
                uiState.beautySettings.copy(enabled = !uiState.beautySettings.enabled)
            )
        },
        isBeautySelected = uiState.showBeautySelector,
        isFilterSelected = uiState.showFilterSelector,
        isRatioSelected = uiState.showRatioSelector,
        isSceneActive = uiState.currentScene != ScenePreset.NONE,
        isGridActive = uiState.showGridSelector,
        isBeautyEnabled = uiState.beautySettings.enabled,
        currentRatio = uiState.aspectRatio,
        modifier = Modifier.align(Alignment.TopEnd)
    )
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
        ControlPanel(onDismiss = actions.onDismissPanels) {
            when {
                uiState.showFilterSelector -> {
                    FilterSelector(uiState.selectedFilter) { selected -> actions.onFilterSelected(selected) }
                    StyleFilterSelector(uiState.selectedStyleFilter) { selected -> actions.onStyleFilterSelected(selected) }
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

