package com.picme.features.camera.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.picme.R
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.GridType
import com.picme.features.camera.ScenePreset
import com.picme.features.camera.CameraAspectRatio
import com.picme.features.camera.model.FilterType

@Composable
fun CameraLeftControls(
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onToggleGrid: () -> Unit,
    isGridActive: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp).statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ControlButton(icon = Icons.Rounded.Settings, onClick = onNavigateToSettings)
        ControlButton(icon = Icons.Rounded.BugReport, onClick = onNavigateToDebug)
        ControlButton(
            icon = Icons.Rounded.GridOn,
            onClick = onToggleGrid,
            isActive = isGridActive
        )
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
        modifier = modifier.padding(16.dp).statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.End
    ) {
        ControlButton(
            icon = Icons.Rounded.Info,
            onClick = onToggleCameraInfo,
            isActive = isCameraInfoSelected
        )
        ControlButton(
            icon = when(currentRatio) {
                0 -> Icons.Rounded.AspectRatio
                1 -> Icons.Rounded.Crop169
                2 -> Icons.Rounded.CropSquare
                else -> Icons.Rounded.CropFree
            },
            onClick = onToggleRatio,
            isActive = isRatioSelected
        )
        ControlButton(
            icon = Icons.Rounded.AutoFixHigh,
            onClick = onToggleBeauty,
            isActive = isBeautySelected
        )
        ControlButton(
            icon = Icons.Rounded.FilterBAndW,
            onClick = onToggleFilter,
            isActive = isFilterSelected
        )
        ControlButton(
            icon = Icons.Rounded.Landscape,
            onClick = onToggleScene,
            isActive = isSceneActive
        )
    }
}

@Composable
fun ControlButton(
    icon: ImageVector,
    onClick: () -> Unit,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
            contentColor = if (isActive) Color.Black else Color.White
        )
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun ControlPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                }
            }
            content()
        }
    }
}

@Composable
fun FilterSelector(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
    val listState = rememberLazyListState()
    
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(FilterType.values()) { filter ->
            val isSelected = selectedFilter == filter
            val scale by animateFloatAsState(if (isSelected) 1.15f else 1.0f, label = "scale")
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(72.dp)
                    .clickable { onFilterSelected(filter) }
                    .scale(scale)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            brush = if (isSelected) {
                                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color.White))
                            } else {
                                Brush.linearGradient(listOf(Color.White.copy(0.3f), Color.White.copy(0.1f)))
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Filter Preview Image (Mock with a placeholder or actual logic)
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(R.drawable.placeholder) // Should be a small preview image
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        colorFilter = ColorFilter.colorMatrix(filter.getColorMatrix())
                    )
                    
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(filter.displayNameRes),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun BeautySelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BeautySlider(label = stringResource(R.string.smoothing), value = settings.smoothing) { 
            onSettingsChanged(settings.copy(smoothing = it)) 
        }
        BeautySlider(label = stringResource(R.string.slim_face), value = settings.slimFace) { 
            onSettingsChanged(settings.copy(slimFace = it)) 
        }
        BeautySlider(label = stringResource(R.string.big_eyes), value = settings.bigEyes) { 
            onSettingsChanged(settings.copy(bigEyes = it)) 
        }
        BeautySlider(label = stringResource(R.string.youth), value = settings.youth) { 
            onSettingsChanged(settings.copy(youth = it)) 
        }
    }
}

@Composable
private fun BeautySlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = Color.White, modifier = Modifier.width(80.dp), fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
        Text(text = "${(value * 100).toInt()}%", color = Color.White, modifier = Modifier.width(40.dp), fontSize = 12.sp)
    }
}

@Composable
fun RatioSelector(selectedRatio: CameraAspectRatio, onRatioSelected: (CameraAspectRatio) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RatioItem(label = stringResource(R.string.ratio_4_3), isSelected = selectedRatio == CameraAspectRatio.RATIO_4_3) { onRatioSelected(CameraAspectRatio.RATIO_4_3) }
        RatioItem(label = stringResource(R.string.ratio_16_9), isSelected = selectedRatio == CameraAspectRatio.RATIO_16_9) { onRatioSelected(CameraAspectRatio.RATIO_16_9) }
        RatioItem(label = stringResource(R.string.ratio_1_1), isSelected = selectedRatio == CameraAspectRatio.RATIO_1_1) { onRatioSelected(CameraAspectRatio.RATIO_1_1) }
        RatioItem(label = stringResource(R.string.ratio_full), isSelected = selectedRatio == CameraAspectRatio.RATIO_FULL) { onRatioSelected(CameraAspectRatio.RATIO_FULL) }
    }
}

@Composable
private fun RatioItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray
        )
    ) {
        Text(text = label, color = if (isSelected) Color.Black else Color.White, fontSize = 12.sp)
    }
}

@Composable
fun SceneSelector(currentScene: ScenePreset, onSceneSelected: (ScenePreset) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ScenePreset.values().forEach { scene ->
            val label = when (scene) {
                ScenePreset.NONE -> stringResource(R.string.scene_none)
                ScenePreset.NIGHT -> stringResource(R.string.scene_night)
                ScenePreset.MOON -> stringResource(R.string.scene_moon)
            }
            Button(
                onClick = { onSceneSelected(scene) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentScene == scene) MaterialTheme.colorScheme.primary else Color.DarkGray
                )
            ) {
                Text(text = label, color = if (currentScene == scene) Color.Black else Color.White)
            }
        }
    }
}

@Composable
fun GridSelector(currentGrid: GridType, onGridSelected: (GridType) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GridType.values().forEach { grid ->
            val label = when (grid) {
                GridType.NONE -> stringResource(R.string.grid_none)
                GridType.THIRDS -> stringResource(R.string.grid_thirds)
                GridType.GOLDEN -> stringResource(R.string.grid_golden)
            }
            Button(
                onClick = { onGridSelected(grid) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentGrid == grid) MaterialTheme.colorScheme.primary else Color.DarkGray
                )
            ) {
                Text(text = label, color = if (currentGrid == grid) Color.Black else Color.White)
            }
        }
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
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ev), color = Color.White, modifier = Modifier.width(40.dp))
            Slider(
                value = exposure.toFloat(),
                onValueChange = { onExposureChange(it.toInt()) },
                valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                steps = if (exposureRange.last > exposureRange.first) exposureRange.last - exposureRange.first - 1 else 0,
                modifier = Modifier.weight(1f)
            )
            Text(text = if (exposure > 0) "+$exposure" else exposure.toString(), color = Color.White, modifier = Modifier.width(40.dp))
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(0, 1, 2, 3, 4)) { mode ->
                val label = when (mode) {
                    0 -> stringResource(R.string.wb_auto)
                    1 -> stringResource(R.string.wb_sunny)
                    2 -> stringResource(R.string.wb_cloudy)
                    3 -> stringResource(R.string.wb_incandescent)
                    4 -> stringResource(R.string.wb_fluorescent)
                    else -> ""
                }
                FilterChip(
                    selected = whiteBalance == mode,
                    onClick = { onWhiteBalanceChange(mode) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        labelColor = Color.White,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
    }
}
