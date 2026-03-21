package com.picme.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.data.model.MediaAsset
import com.picme.data.model.MediaType
import com.picme.ui.screens.AspectRatio
import com.picme.ui.screens.GridType
import com.picme.ui.screens.ScenePreset

@Composable
fun CameraLeftControls(
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onToggleGrid: () -> Unit,
    isGridActive: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ControlIconItem(icon = Icons.Rounded.Settings, onClick = onNavigateToSettings)
        ControlIconItem(icon = Icons.Rounded.Grid4x4, isSelected = isGridActive, onClick = onToggleGrid)
        ControlIconItem(icon = Icons.Rounded.BugReport, onClick = onNavigateToDebug)
    }
}

@Composable
fun CameraRightControls(
    onToggleBeauty: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleRatio: () -> Unit,
    onToggleCameraInfo: () -> Unit,
    onToggleScene: () -> Unit,
    isBeautySelected: Boolean,
    isFilterSelected: Boolean,
    isRatioSelected: Boolean,
    isCameraInfoSelected: Boolean,
    isSceneActive: Boolean,
    currentRatio: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ControlIconItem(
            icon = Icons.Rounded.Info, 
            isSelected = isCameraInfoSelected, 
            onClick = onToggleCameraInfo
        )
        
        ControlIconItem(
            icon = Icons.Rounded.Landscape, 
            isSelected = isSceneActive, 
            onClick = onToggleScene
        )

        val ratioIcon = if (currentRatio == AspectRatio.RATIO_4_3) Icons.Rounded.Crop32 else Icons.Rounded.Crop169
        ControlIconItem(
            icon = ratioIcon, 
            isSelected = isRatioSelected, 
            onClick = onToggleRatio
        )

        ControlIconItem(
            icon = Icons.Rounded.Face, 
            isSelected = isBeautySelected, 
            onClick = onToggleBeauty
        )
        
        ControlIconItem(
            icon = Icons.Rounded.AutoFixHigh, 
            isSelected = isFilterSelected, 
            onClick = onToggleFilter
        )
    }
}

@Composable
fun ControlIconItem(
    icon: ImageVector,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ProModeControls(
    exposure: Int,
    exposureRange: IntRange,
    onExposureChange: (Int) -> Unit,
    whiteBalance: Int,
    onWhiteBalanceChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedParam by remember { mutableStateOf("EV") }
    
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.padding(bottom = 12.dp).height(50.dp), contentAlignment = Alignment.Center) {
            when (selectedParam) {
                "EV" -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(horizontal = 140.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items((exposureRange.first..exposureRange.last).toList()) { value ->
                            val isSelected = value == exposure
                            Text(
                                text = if (value > 0) "+$value" else value.toString(),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isSelected) 18.sp else 14.sp,
                                modifier = Modifier.clickable { onExposureChange(value) }
                            )
                        }
                    }
                }
                "WB" -> {
                    val wbModes = listOf(
                        0 to stringResource(R.string.wb_auto),
                        1 to stringResource(R.string.wb_sunny),
                        2 to stringResource(R.string.wb_cloudy),
                        3 to stringResource(R.string.wb_incandescent),
                        4 to stringResource(R.string.wb_fluorescent)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(horizontal = 120.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(wbModes) { (mode, label) ->
                            val isSelected = mode == whiteBalance
                            Text(
                                text = label.uppercase(),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = if (isSelected) 16.sp else 13.sp,
                                modifier = Modifier.clickable { onWhiteBalanceChange(mode) }
                            )
                        }
                    }
                }
                else -> {
                    Text(text = stringResource(R.string.auto).uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProParamItem(label = stringResource(R.string.ev), isSelected = selectedParam == "EV") { selectedParam = "EV" }
            ProParamItem(label = stringResource(R.string.iso), isSelected = selectedParam == "ISO") { selectedParam = "ISO" }
            ProParamItem(label = stringResource(R.string.shutter), isSelected = selectedParam == "S") { selectedParam = "S" }
            ProParamItem(label = stringResource(R.string.wb), isSelected = selectedParam == "WB") { selectedParam = "WB" }
            ProParamItem(label = stringResource(R.string.focus), isSelected = selectedParam == "AF") { selectedParam = "AF" }
        }
    }
}

@Composable
fun ProParamItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
        if (isSelected) {
            Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        } else {
            Spacer(modifier = Modifier.height(3.dp))
        }
    }
}

@Composable
fun SceneSelector(currentScene: ScenePreset, onSceneSelected: (ScenePreset) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SceneOption(label = stringResource(R.string.scene_none), isSelected = currentScene == ScenePreset.NONE, onClick = { onSceneSelected(ScenePreset.NONE) })
        SceneOption(label = stringResource(R.string.scene_night), isSelected = currentScene == ScenePreset.NIGHT, onClick = { onSceneSelected(ScenePreset.NIGHT) })
        SceneOption(label = stringResource(R.string.scene_moon), isSelected = currentScene == ScenePreset.MOON, onClick = { onSceneSelected(ScenePreset.MOON) })
    }
}

@Composable
fun SceneOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text = label, color = if (isSelected) Color.Black else Color.White)
    }
}

@Composable
fun GridSelector(currentGrid: GridType, onGridSelected: (GridType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SceneOption(label = stringResource(R.string.grid_none), isSelected = currentGrid == GridType.NONE, onClick = { onGridSelected(GridType.NONE) })
        SceneOption(label = stringResource(R.string.grid_thirds), isSelected = currentGrid == GridType.THIRDS, onClick = { onGridSelected(GridType.THIRDS) })
        SceneOption(label = stringResource(R.string.grid_golden), isSelected = currentGrid == GridType.GOLDEN, onClick = { onGridSelected(GridType.GOLDEN) })
    }
}

@Composable
fun RatioSelector(
    currentRatio: Int,
    onRatioSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        RatioOption(
            label = stringResource(R.string.ratio_4_3),
            isSelected = currentRatio == AspectRatio.RATIO_4_3,
            onClick = { onRatioSelected(AspectRatio.RATIO_4_3) }
        )
        RatioOption(
            label = stringResource(R.string.ratio_16_9),
            isSelected = currentRatio == AspectRatio.RATIO_16_9,
            onClick = { onRatioSelected(AspectRatio.RATIO_16_9) }
        )
    }
}

@Composable
fun RatioOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun CameraBottomControls(
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    isAnyPanelOpen: Boolean,
    onZoomPresetClick: (Float) -> Unit,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onModeChange: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    val zoomPresets = listOf(0.6f, 1f, 2.6f, 5f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = captureMode != MediaType.PRO && !isAnyPanelOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(modifier = Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                zoomPresets.forEach { preset ->
                    val isSelected = (zoomRatio >= preset - 0.05f && zoomRatio <= preset + 0.05f) || (preset == 1f && zoomRatio < 1.1f && zoomRatio > 0.9f)
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f))
                            .clickable { onZoomPresetClick(preset) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = if (preset < 1f) ".6" else preset.toInt().toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryThumbnail(
                lastMedia = lastMedia,
                onClick = onGalleryClick
            )
            CameraCaptureButton(
                isRecording = isRecording,
                mode = captureMode,
                onClick = onCaptureClick
            )
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cached,
                    contentDescription = "Flip Camera",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MediaType.entries.forEach { mode ->
                val label = when (mode) {
                    MediaType.PHOTO -> stringResource(R.string.photo)
                    MediaType.VIDEO -> stringResource(R.string.video)
                    MediaType.PORTRAIT -> stringResource(R.string.portrait)
                    MediaType.PRO -> stringResource(R.string.pro)
                }
                Text(
                    text = label.uppercase(),
                    color = if (captureMode == mode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                    fontWeight = if (captureMode == mode) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onModeChange(mode) }
                )
            }
        }
    }
}
