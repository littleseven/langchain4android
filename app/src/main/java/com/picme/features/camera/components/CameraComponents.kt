package com.picme.features.camera.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FilterBAndW
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.CameraAspectRatio
import com.picme.features.camera.GridType
import com.picme.features.camera.MakeupEntry
import com.picme.features.camera.ScenePreset
import com.picme.features.camera.model.FilterType

/** Panel height ratio relative to screen height */
private const val PANEL_HEIGHT_RATIO = 0.5f

@Composable
fun CameraLeftControls(
    onNavigateToSettings: () -> Unit,
    onFlipCamera: () -> Unit,
    isFrontCamera: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 设置
        ControlButton(icon = Icons.Rounded.Settings, onClick = onNavigateToSettings)
        
        // 翻转摄像头
        ControlButton(
            icon = Icons.Rounded.Cameraswitch,
            onClick = onFlipCamera,
            isActive = false
        )
    }
}

@Composable
fun CameraRightControls(
    onToggleBeauty: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleRatio: () -> Unit,
    onToggleScene: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleBeautyEnabled: () -> Unit,
    isBeautySelected: Boolean,
    isFilterSelected: Boolean,
    isRatioSelected: Boolean,
    isSceneActive: Boolean,
    isGridActive: Boolean,
    isBeautyEnabled: Boolean,
    currentRatio: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End
    ) {
        // 美颜总开关（核心功能）
        ControlButton(
            icon = Icons.Rounded.AutoFixHigh,
            onClick = onToggleBeautyEnabled,
            isActive = isBeautyEnabled
        )
        
        // 美颜面板入口
        ControlButton(
            icon = Icons.Rounded.Face,
            onClick = onToggleBeauty,
            isActive = isBeautySelected
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        // 构图类工具：画幅 -> 网格
        ControlButton(
            icon = when (currentRatio) {
                0 -> Icons.Rounded.AspectRatio
                1 -> Icons.Rounded.Crop169
                2 -> Icons.Rounded.CropSquare
                else -> Icons.Rounded.CropFree
            },
            onClick = onToggleRatio,
            isActive = isRatioSelected
        )
        ControlButton(
            icon = Icons.Rounded.GridOn,
            onClick = onToggleGrid,
            isActive = isGridActive
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 风格类工具：场景 -> 滤镜
        ControlButton(
            icon = Icons.Rounded.Landscape,
            onClick = onToggleScene,
            isActive = isSceneActive
        )
        ControlButton(
            icon = Icons.Rounded.FilterBAndW,
            onClick = onToggleFilter,
            isActive = isFilterSelected
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
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Black.copy(alpha = 0.5f)
            },
            contentColor = if (isActive) Color.Black else Color.White
        )
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun ControlPainterButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Black.copy(alpha = 0.5f)
            },
            contentColor = if (isActive) Color.Black else Color.White
        )
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ControlPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    // Drawer-style panel: slides in from bottom, occupies bottom area of screen
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * PANEL_HEIGHT_RATIO
    val density = LocalDensity.current
    val panelHeightState = remember { mutableStateOf(0.dp) }
    
    val overlayHeight = panelHeightState.value
        .plus(24.dp)
        .coerceIn(160.dp, panelMaxHeight)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // 遮罩高度跟随面板内容，避免内容较少时出现过高的背景
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(overlayHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.42f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = with(density) { overlayHeight.toPx() }
                    )
                )
        )
        
        // 控制面板：默认包裹内容，内容增多时最多占半屏
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .onSizeChanged { size ->
                    panelHeightState.value = with(density) { size.height.toDp() }
                }
                .heightIn(max = panelMaxHeight - 16.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            shadowElevation = 20.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight - 32.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                
                // 内容区域 - 直接调用 content，在 Column 作用域内
                content()
            }
        }
    }
}

@Composable
fun FilterSelector(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(FilterType.values()) { filter ->
            val isSelected = selectedFilter == filter
            val scale by animateFloatAsState(if (isSelected) 1.1f else 1.0f, label = "scale")

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(60.dp)
                    .clickable { onFilterSelected(filter) }
                    .scale(scale)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            brush = if (isSelected) {
                                Brush.linearGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.onSurface.copy(0.3f),
                                        MaterialTheme.colorScheme.onSurface.copy(0.1f)
                                    )
                                )
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // 使用渐变色代替图片，简洁表达滤镜效果
                    FilterGradientPreview(filter = filter)

                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(filter.displayNameRes),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 滤镜渐变预览组件
 * 使用简单的渐变色表达不同滤镜的色调特征
 */
@Composable
private fun FilterGradientPreview(filter: FilterType) {
    val gradientColors = when (filter) {
        FilterType.NONE -> listOf(Color(0xFFE0E0E0), Color(0xFFBDBDBD))
        FilterType.VINTAGE -> listOf(Color(0xFFFFD54F), Color(0xFFFFA726))
        FilterType.COOL -> listOf(Color(0xFF4FC3F7), Color(0xFF29B6F6))
        FilterType.WARM -> listOf(Color(0xFFFFAB91), Color(0xFFFFCC80))
        FilterType.LEICA_CLASSIC -> listOf(Color(0xFF90A4AE), Color(0xFF546E7A))
        FilterType.LEICA_VIBRANT -> listOf(Color(0xFF26C6DA), Color(0xFF00ACC1))
        FilterType.LEICA_BW -> listOf(Color(0xFFEEEEEE), Color(0xFF616161))
        FilterType.FILM_GOLD -> listOf(Color(0xFFFFECB3), Color(0xFFFFCA28))
        FilterType.FILM_FUJI -> listOf(Color(0xFFA5D6A7), Color(0xFF66BB6A))
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = gradientColors,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
    )
}

@Composable
fun BeautySelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    val expandedCategoryState = remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 面部精修 - 可折叠
        ExpandableSection(
            title = stringResource(R.string.facial_refinement),
            isExpanded = expandedCategoryState.value == "facial",
            onToggle = { 
                expandedCategoryState.value = if (expandedCategoryState.value == "facial") null else "facial"
            }
        ) {
            BeautySlider(
                icon = Icons.Rounded.Face,
                label = stringResource(R.string.smoothing),
                value = settings.smoothing,
                valueRange = 0f..100f,
                onValueChange = { onSettingsChanged(settings.copy(smoothing = it)) },
                onReset = { onSettingsChanged(settings.copy(smoothing = 0f)) }
            )

            BeautySlider(
                icon = Icons.Rounded.AutoFixHigh,
                label = stringResource(R.string.whitening),
                value = settings.whitening,
                valueRange = 0f..100f,
                onValueChange = { onSettingsChanged(settings.copy(whitening = it)) },
                onReset = { onSettingsChanged(settings.copy(whitening = 0f)) }
            )

            BeautySlider(
                icon = Icons.Rounded.FaceRetouchingNatural,
                label = stringResource(R.string.slim_face),
                value = settings.slimFace,
                valueRange = -50f..50f,
                onValueChange = { onSettingsChanged(settings.copy(slimFace = it)) },
                onReset = { onSettingsChanged(settings.copy(slimFace = 0f)) }
            )

            BeautySlider(
                icon = Icons.Rounded.Visibility,
                label = stringResource(R.string.big_eyes),
                value = settings.bigEyes,
                valueRange = 0f..100f,
                onValueChange = { onSettingsChanged(settings.copy(bigEyes = it)) },
                onReset = { onSettingsChanged(settings.copy(bigEyes = 0f)) }
            )

        }
        
        // 妆容调节 - 可折叠
        ExpandableSection(
            title = stringResource(R.string.makeup_adjustment),
            isExpanded = expandedCategoryState.value == "makeup",
            onToggle = { 
                expandedCategoryState.value = if (expandedCategoryState.value == "makeup") null else "makeup"
            }
        ) {
            // 唇色选择器
            LipColorSelector(
                strength = settings.lipColor,
                colorIndex = settings.lipColorIndex,
                onStrengthChanged = { onSettingsChanged(settings.copy(lipColor = it)) },
                onColorIndexChanged = { onSettingsChanged(settings.copy(lipColorIndex = it)) },
                onReset = {
                    onSettingsChanged(
                        settings.copy(
                            lipColor = BeautySettings.DEFAULT_LIP_COLOR,
                            lipColorIndex = 0
                        )
                    )
                }
            )
            
            BlushColorFamilySelector(
                selectedFamily = settings.blushColorFamily,
                onFamilyChanged = { family ->
                    onSettingsChanged(settings.copy(blushColorFamily = family))
                }
            )

            BeautySlider(
                icon = Icons.Rounded.FavoriteBorder,
                label = stringResource(R.string.blush),
                value = settings.blush,
                valueRange = 0f..100f,
                onValueChange = { onSettingsChanged(settings.copy(blush = it)) },
                onReset = { onSettingsChanged(settings.copy(blush = BeautySettings.DEFAULT_BLUSH)) }
            )

            BeautySlider(
                icon = Icons.Rounded.LineStyle,
                label = stringResource(R.string.eyebrow),
                value = settings.eyebrow,
                valueRange = 0f..100f,
                onValueChange = { onSettingsChanged(settings.copy(eyebrow = it)) },
                onReset = { onSettingsChanged(settings.copy(eyebrow = BeautySettings.DEFAULT_EYEBROW)) }
            )
        }
        
        // 身材管理 - 可折叠
        ExpandableSection(
            title = stringResource(R.string.body_management),
            isExpanded = expandedCategoryState.value == "body",
            onToggle = { 
                expandedCategoryState.value = if (expandedCategoryState.value == "body") null else "body"
            }
        ) {
            BeautySlider(
                icon = Icons.Rounded.SelfImprovement,
                label = stringResource(R.string.body_enhancement),
                value = settings.bodyEnhancement,
                valueRange = -30f..30f,
                onValueChange = { onSettingsChanged(settings.copy(bodyEnhancement = it)) },
                onReset = { onSettingsChanged(settings.copy(bodyEnhancement = 0f)) }
            )

            BeautySlider(
                icon = Icons.Rounded.Timeline,
                label = stringResource(R.string.leg_extension),
                value = settings.legExtension,
                valueRange = 0f..50f,
                onValueChange = { onSettingsChanged(settings.copy(legExtension = it)) },
                onReset = { onSettingsChanged(settings.copy(legExtension = 0f)) }
            )
        }
    }
}

/**
 * 面部精修选择器 - 独立版本
 */
@Composable
fun FacialRefinementSelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BeautySlider(
            icon = Icons.Rounded.Face,
            label = stringResource(R.string.smoothing),
            value = settings.smoothing,
            valueRange = 0f..100f,
            onValueChange = { onSettingsChanged(settings.copy(smoothing = it)) },
            onReset = { onSettingsChanged(settings.copy(smoothing = 0f)) }
        )

        BeautySlider(
            icon = Icons.Rounded.AutoFixHigh,
            label = stringResource(R.string.whitening),
            value = settings.whitening,
            valueRange = 0f..100f,
            onValueChange = { onSettingsChanged(settings.copy(whitening = it)) },
            onReset = { onSettingsChanged(settings.copy(whitening = 0f)) }
        )

        BeautySlider(
            icon = Icons.Rounded.FaceRetouchingNatural,
            label = stringResource(R.string.slim_face),
            value = settings.slimFace,
            valueRange = -50f..50f,
            onValueChange = { onSettingsChanged(settings.copy(slimFace = it)) },
            onReset = { onSettingsChanged(settings.copy(slimFace = 0f)) }
        )

        BeautySlider(
            icon = Icons.Rounded.Visibility,
            label = stringResource(R.string.big_eyes),
            value = settings.bigEyes,
            valueRange = 0f..100f,
            onValueChange = { onSettingsChanged(settings.copy(bigEyes = it)) },
            onReset = { onSettingsChanged(settings.copy(bigEyes = 0f)) }
        )

    }
}

/**
 * 妆容调节选择器 - 独立版本
 */
@Composable
internal fun MakeupAdjustmentSelector(
    settings: BeautySettings,
    activeEntry: MakeupEntry? = null,
    onSettingsChanged: (BeautySettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (activeEntry) {
            MakeupEntry.LIP_COLOR -> {
                LipColorSelector(
                    strength = settings.lipColor,
                    colorIndex = settings.lipColorIndex,
                    onStrengthChanged = { onSettingsChanged(settings.copy(lipColor = it)) },
                    onColorIndexChanged = { onSettingsChanged(settings.copy(lipColorIndex = it)) },
                    onReset = {
                        onSettingsChanged(
                            settings.copy(
                                lipColor = BeautySettings.DEFAULT_LIP_COLOR,
                                lipColorIndex = 0
                            )
                        )
                    }
                )
            }

            MakeupEntry.BLUSH -> {
                BlushColorFamilySelector(
                    selectedFamily = settings.blushColorFamily,
                    onFamilyChanged = { family ->
                        onSettingsChanged(settings.copy(blushColorFamily = family))
                    }
                )

                BeautySlider(
                    icon = Icons.Rounded.FavoriteBorder,
                    label = stringResource(R.string.blush),
                    value = settings.blush,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(blush = it)) },
                    onReset = { onSettingsChanged(settings.copy(blush = BeautySettings.DEFAULT_BLUSH)) }
                )
            }

            MakeupEntry.EYEBROW -> {
                BeautySlider(
                    icon = Icons.Rounded.LineStyle,
                    label = stringResource(R.string.eyebrow),
                    value = settings.eyebrow,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(eyebrow = it)) },
                    onReset = { onSettingsChanged(settings.copy(eyebrow = BeautySettings.DEFAULT_EYEBROW)) }
                )
            }

            null -> {
                LipColorSelector(
                    strength = settings.lipColor,
                    colorIndex = settings.lipColorIndex,
                    onStrengthChanged = { onSettingsChanged(settings.copy(lipColor = it)) },
                    onColorIndexChanged = { onSettingsChanged(settings.copy(lipColorIndex = it)) },
                    onReset = {
                        onSettingsChanged(
                            settings.copy(
                                lipColor = BeautySettings.DEFAULT_LIP_COLOR,
                                lipColorIndex = 0
                            )
                        )
                    }
                )

                BlushColorFamilySelector(
                    selectedFamily = settings.blushColorFamily,
                    onFamilyChanged = { family ->
                        onSettingsChanged(settings.copy(blushColorFamily = family))
                    }
                )

                BeautySlider(
                    icon = Icons.Rounded.FavoriteBorder,
                    label = stringResource(R.string.blush),
                    value = settings.blush,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(blush = it)) },
                    onReset = { onSettingsChanged(settings.copy(blush = BeautySettings.DEFAULT_BLUSH)) }
                )

                BeautySlider(
                    icon = Icons.Rounded.LineStyle,
                    label = stringResource(R.string.eyebrow),
                    value = settings.eyebrow,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(eyebrow = it)) },
                    onReset = { onSettingsChanged(settings.copy(eyebrow = BeautySettings.DEFAULT_EYEBROW)) }
                )
            }
        }
    }
}

/**
 * 身材管理选择器 - 独立版本
 */
@Composable
fun BodyManagementSelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BeautySlider(
            icon = Icons.Rounded.SelfImprovement,
            label = stringResource(R.string.body_enhancement),
            value = settings.bodyEnhancement,
            valueRange = -30f..30f,
            onValueChange = { onSettingsChanged(settings.copy(bodyEnhancement = it)) },
            onReset = { onSettingsChanged(settings.copy(bodyEnhancement = 0f)) }
        )

        BeautySlider(
            icon = Icons.Rounded.Timeline,
            label = stringResource(R.string.leg_extension),
            value = settings.legExtension,
            valueRange = 0f..50f,
            onValueChange = { onSettingsChanged(settings.copy(legExtension = it)) },
            onReset = { onSettingsChanged(settings.copy(legExtension = 0f)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeautySlider(
    icon: ImageVector,
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    // Apply a power curve to make the low-range effect more noticeable
    // Value displayed to user is linear 0-100, but internal value can be mapped
    val displayValue = if (valueRange.start < 0) {
        // For negative ranges like slim face (-50 to 50)
        value.toInt()
    } else {
        (value * 100 / valueRange.endInclusive).toInt()
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onReset)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (value != 0f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Floating value indicator logic (simplified here)
            Text(
                text = if (value != 0f) "$displayValue" else "--",
                color = if (value != 0f) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(contentAlignment = Alignment.Center) {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = {
                    // Internal: applying a power curve (x^0.7) to boost the low-end effect
                    // But we keep the UI value linear for user intuition
                    onValueChange(it)
                },
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                ),
                thumb = {
                    val thumbScale by animateFloatAsState(
                        if (isPressed) 1.5f else 1f,
                        label = "thumbScale"
                    )
                    Spacer(
                        modifier = Modifier
                            .size(20.dp)
                            .scale(thumbScale)
                            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    )
                },
                track = { sliderPositions ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    sliderPositions.valueRange.run {
                                        (value - start) / (endInclusive - start)
                                    }
                                )
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            MaterialTheme.colorScheme.primary
                                        )
                                    )
                                )
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun RatioSelector(selectedRatio: CameraAspectRatio, onRatioSelected: (CameraAspectRatio) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RatioItem(
            label = stringResource(R.string.ratio_4_3),
            isSelected = selectedRatio == CameraAspectRatio.RATIO_4_3
        ) {
            onRatioSelected(CameraAspectRatio.RATIO_4_3)
        }
        RatioItem(
            label = stringResource(R.string.ratio_16_9),
            isSelected = selectedRatio == CameraAspectRatio.RATIO_16_9
        ) {
            onRatioSelected(CameraAspectRatio.RATIO_16_9)
        }
        RatioItem(
            label = stringResource(R.string.ratio_full),
            isSelected = selectedRatio == CameraAspectRatio.RATIO_FULL
        ) {
            onRatioSelected(CameraAspectRatio.RATIO_FULL)
        }
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
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp
        )
    }
}

/**
 * 可折叠分类组件
 */
@Composable
private fun ExpandableSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "rotation")
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = if (isExpanded) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(initialAlpha = 0.3f),
            exit = shrinkVertically() + fadeOut(targetAlpha = 0.3f)
        ) {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 腮红色系选择器
 * 提供粉色、橙色、梅子色三种色系。
 */
@Composable
fun BlushColorFamilySelector(
    selectedFamily: Int,
    onFamilyChanged: (Int) -> Unit
) {
    val familyLabels = listOf(
        stringResource(R.string.blush_family_pink),
        stringResource(R.string.blush_family_orange),
        stringResource(R.string.blush_family_plum)
    )
    val familyColors = listOf(
        Color(0xFFFF8DAA),
        Color(0xFFFFA85C),
        Color(0xFF9B3D6A)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.blush_color_family),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            familyLabels.forEachIndexed { index, label ->
                val isSelected = selectedFamily == index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            }
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onFamilyChanged(index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(familyColors[index])
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 唇色选择器组件
 * 包含强度滑块和 12 种色号选择
 */
@Composable
fun LipColorSelector(
    strength: Float,
    colorIndex: Int,
    onStrengthChanged: (Float) -> Unit,
    onColorIndexChanged: (Int) -> Unit,
    onReset: () -> Unit
) {
    val lipColors = listOf(
        Color(0xFFD4757D), // 豆沙色
        Color(0xFFC43343), // 正红色
        Color(0xFFFF7F50), // 珊瑚色
        Color(0xFFE0527C), // 玫瑰色
        Color(0xFFFF6B9D), // 粉色
        Color(0xFF9B2335), // 酒红色
        Color(0xFFFFA07A), // 浅粉色
        Color(0xFFCD5C5C), // 印度红
        Color(0xFFDC143C), // 深红色
        Color(0xFFFFB6C1), // 浅玫瑰色
        Color(0xFFB22222), // 火砖色
        Color(0xFFFF1493)  // 深粉色
    )
    
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 色号选择 Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onReset)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ColorLens,
                    contentDescription = null,
                    tint = if (strength > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lip_color),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Text(
                text = if (strength > 0) "${strength.toInt()}" else "--",
                color = if (strength > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        // 12 色号网格
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(64.dp)
        ) {
            items(lipColors.size) { index ->
                Box(
                    modifier = Modifier
                        .size(48.dp, 28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(lipColors[index])
                        .border(
                            width = if (index == colorIndex) 3.dp else 1.dp,
                            color = if (index == colorIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.3f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onColorIndexChanged(index) }
                )
            }
        }
        
        // 强度滑块
        Slider(
            value = strength.coerceIn(0f, 100f),
            onValueChange = { value ->
                onStrengthChanged(value.coerceIn(0f, 100f))
            },
            valueRange = 0f..100f,
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
fun SceneSelector(currentScene: ScenePreset, onSceneSelected: (ScenePreset) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScenePreset.values().forEach { scene ->
            val label = when (scene) {
                ScenePreset.NONE -> stringResource(R.string.scene_none)
                ScenePreset.NIGHT -> stringResource(R.string.scene_night)
                ScenePreset.MOON -> stringResource(R.string.scene_moon)
            }
            Button(
                onClick = { onSceneSelected(scene) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentScene == scene) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.DarkGray
                    }
                )
            ) {
                Text(
                    text = label,
                    color = if (currentScene == scene) Color.Black else Color.White
                )
            }
        }
    }
}

@Composable
fun GridSelector(currentGrid: GridType, onGridSelected: (GridType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GridType.values().forEach { grid ->
            val label = when (grid) {
                GridType.NONE -> stringResource(R.string.grid_none)
                GridType.THIRDS -> stringResource(R.string.grid_thirds)
                GridType.GOLDEN -> stringResource(R.string.grid_golden)
            }
            Button(
                onClick = { onGridSelected(grid) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentGrid == grid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.DarkGray
                    }
                )
            ) {
                Text(
                    text = label,
                    color = if (currentGrid == grid) Color.Black else Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProModeControls(
    exposure: Int,
    exposureRange: IntRange,
    onExposureChange: (Int) -> Unit,
    whiteBalance: Int,
    onWhiteBalanceChange: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with title and close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.pro_mode),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(contentAlignment = Alignment.Center) {
            // Debug: Log exposure range
            LaunchedEffect(exposureRange) {
                android.util.Log.d("ProMode", "Exposure range: ${exposureRange.first} to ${exposureRange.last}, current: $exposure")
            }
            
            Slider(
                value = exposure.toFloat(),
                valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                steps = if (exposureRange.last > exposureRange.first) {
                    exposureRange.last - exposureRange.first - 1
                } else {
                    0
                },
                onValueChange = { newValue ->
                    android.util.Log.d("ProMode", "Exposure changed to: ${newValue.toInt()}")
                    onExposureChange(newValue.toInt())
                },
                enabled = true,
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),  // Increase touch target
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                ),
                thumb = {
                    val thumbScale by animateFloatAsState(
                        if (isPressed) 1.5f else 1f,
                        label = "thumbScale"
                    )
                    Spacer(
                        modifier = Modifier
                            .size(20.dp)
                            .scale(thumbScale)
                            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    )
                },
                track = { sliderPositions ->
                    val fraction = if (exposureRange.last > exposureRange.first) {
                        (exposure.toFloat() - exposureRange.first.toFloat()) / (exposureRange.last.toFloat() - exposureRange.first.toFloat())
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            MaterialTheme.colorScheme.primary
                                        )
                                    )
                                )
                        )
                    }
                }
            )
        }

        // White Balance Control
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
