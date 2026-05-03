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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.picme.features.camera.model.StyleFilter

/** Panel height ratio relative to screen height */
private const val PANEL_HEIGHT_RATIO = 0.5f

@Composable
fun CameraLeftControls(
    onNavigateToSettings: () -> Unit,
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
        // 美颜入口：单击展开面板，图标颜色区分开关状态
        BeautyEntryButton(
            isEnabled = isBeautyEnabled,
            isPanelOpen = isBeautySelected,
            onTogglePanel = onToggleBeauty,
            onToggleEnabled = onToggleBeautyEnabled
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

/**
 * 美颜入口按钮
 * - 单击：展开/收起美颜面板
 * - 图标颜色：开启=主题色，关闭=灰色
 * - 右上角小圆点：美颜已开启时显示，提示用户当前美颜状态
 */
@Composable
private fun BeautyEntryButton(
    isEnabled: Boolean,
    isPanelOpen: Boolean,
    onTogglePanel: () -> Unit,
    onToggleEnabled: () -> Unit
) {
    Box(contentAlignment = Alignment.TopEnd) {
        FilledIconButton(
            onClick = onTogglePanel,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = when {
                    isPanelOpen -> MaterialTheme.colorScheme.primary
                    isEnabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else -> Color.Black.copy(alpha = 0.5f)
                },
                contentColor = when {
                    isPanelOpen -> Color.Black
                    isEnabled -> MaterialTheme.colorScheme.primary
                    else -> Color.White.copy(alpha = 0.55f)
                }
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoFixHigh,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }

        // 美颜开启指示点（右上角绿点）
        if (isEnabled && !isPanelOpen) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, end = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
            )
        }
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
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * PANEL_HEIGHT_RATIO

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // 半透明渐变背景遮罩（与 BeautyPanel 一致）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight + 24.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {

                // ── 拖拽把手（与 BeautyPanel 一致，替代标题栏+关闭按钮）──
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )

                // 内容区域：超高时可滚动
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 统一滤镜选择器（大美丽模式专用）
 *
 * 所有滤镜平铺为三排网格，不区分色调滤镜和风格特效。
 * 每排按屏幕宽度显示6个滤镜项。
 * 点击色调滤镜时自动清除风格特效，点击风格特效时自动清除色调滤镜。
 *
 * 美颜模式下不显示此选择器。
 */
@Composable
fun UnifiedFilterSelector(
    selectedFilter: FilterType,
    selectedStyleFilter: StyleFilter,
    onFilterSelected: (FilterType) -> Unit,
    onStyleFilterSelected: (StyleFilter) -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val itemWidth = (screenWidth - 20.dp) / 5

    // 所有滤镜项统一排列，按相关性逐行排列（使用 LazyVerticalGrid 实现横向填充）：
    // 第1排：原图 + 徕卡系列 + 胶片系列
    // 第2排：复古/冷暖调 + 风格特效（卡通/素描/色块）
    // 第3排：风格特效（浮雕/交叉线）
    val allFilters = remember {
        listOf(
            // 第1排：基础色调
            FilterItemData(FilterType.NONE.displayNameRes, FilterKind.COLOR, FilterType.NONE.ordinal),
            FilterItemData(FilterType.LEICA_CLASSIC.displayNameRes, FilterKind.COLOR, FilterType.LEICA_CLASSIC.ordinal),
            FilterItemData(FilterType.LEICA_VIBRANT.displayNameRes, FilterKind.COLOR, FilterType.LEICA_VIBRANT.ordinal),
            FilterItemData(FilterType.LEICA_BW.displayNameRes, FilterKind.COLOR, FilterType.LEICA_BW.ordinal),
            FilterItemData(FilterType.FILM_GOLD.displayNameRes, FilterKind.COLOR, FilterType.FILM_GOLD.ordinal),
            FilterItemData(FilterType.FILM_FUJI.displayNameRes, FilterKind.COLOR, FilterType.FILM_FUJI.ordinal),
            // 第2排：氛围色调 + 艺术风格
            FilterItemData(FilterType.VINTAGE.displayNameRes, FilterKind.COLOR, FilterType.VINTAGE.ordinal),
            FilterItemData(FilterType.COOL.displayNameRes, FilterKind.COLOR, FilterType.COOL.ordinal),
            FilterItemData(FilterType.WARM.displayNameRes, FilterKind.COLOR, FilterType.WARM.ordinal),
            FilterItemData(StyleFilter.TOON.displayNameRes, FilterKind.STYLE, StyleFilter.TOON.ordinal),
            FilterItemData(StyleFilter.SKETCH.displayNameRes, FilterKind.STYLE, StyleFilter.SKETCH.ordinal),
            FilterItemData(StyleFilter.POSTERIZE.displayNameRes, FilterKind.STYLE, StyleFilter.POSTERIZE.ordinal),
            // 第3排：艺术风格
            FilterItemData(StyleFilter.EMBOSS.displayNameRes, FilterKind.STYLE, StyleFilter.EMBOSS.ordinal),
            FilterItemData(StyleFilter.CROSSHATCH.displayNameRes, FilterKind.STYLE, StyleFilter.CROSSHATCH.ordinal),
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = rememberLazyGridState(),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(allFilters.size) { index ->
            val item = allFilters[index]
            when (item.kind) {
                FilterKind.COLOR -> {
                    val filter = FilterType.values()[item.ordinal]
                    val isSelected = selectedFilter == filter && selectedStyleFilter == StyleFilter.NONE
                    FilterItem(
                        label = stringResource(item.nameRes),
                        isSelected = isSelected,
                        assetPath = filterAssetPath(filter),
                        itemWidth = itemWidth,
                        onClick = {
                            onFilterSelected(filter)
                            onStyleFilterSelected(StyleFilter.NONE)
                        }
                    )
                }
                FilterKind.STYLE -> {
                    val style = StyleFilter.values()[item.ordinal]
                    val isSelected = selectedStyleFilter == style
                    FilterItem(
                        label = stringResource(item.nameRes),
                        isSelected = isSelected,
                        assetPath = styleAssetPath(style),
                        itemWidth = itemWidth,
                        onClick = {
                            onStyleFilterSelected(style)
                            onFilterSelected(FilterType.NONE)
                        }
                    )
                }
            }
        }
    }
}

private enum class FilterKind { COLOR, STYLE }

private data class FilterItemData(
    val nameRes: Int,
    val kind: FilterKind,
    val ordinal: Int
)

/**
 * 本地 assets 滤镜预览图路径映射。
 * 图片已下载到 app/src/main/assets/filters/ 目录。
 */
private fun filterAssetPath(filter: FilterType): String {
    return when (filter) {
        FilterType.NONE -> "filters/filter_none.jpg"
        FilterType.LEICA_CLASSIC -> "filters/filter_leica_classic.jpg"
        FilterType.LEICA_VIBRANT -> "filters/filter_leica_vibrant.jpg"
        FilterType.LEICA_BW -> "filters/filter_leica_bw.jpg"
        FilterType.FILM_GOLD -> "filters/filter_film_gold.jpg"
        FilterType.FILM_FUJI -> "filters/filter_film_fuji.jpg"
        FilterType.VINTAGE -> "filters/filter_vintage.jpg"
        FilterType.COOL -> "filters/filter_cool.jpg"
        FilterType.WARM -> "filters/filter_warm.jpg"
    }
}

private fun styleAssetPath(style: StyleFilter): String {
    return when (style) {
        StyleFilter.TOON -> "filters/style_toon.jpg"
        StyleFilter.SKETCH -> "filters/style_sketch.jpg"
        StyleFilter.POSTERIZE -> "filters/style_posterize.jpg"
        StyleFilter.EMBOSS -> "filters/style_emboss.jpg"
        StyleFilter.CROSSHATCH -> "filters/style_crosshatch.jpg"
        else -> "filters/style_toon.jpg"
    }
}

@Composable
private fun FilterItem(
    label: String,
    isSelected: Boolean,
    assetPath: String,
    itemWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (isSelected) 1.08f else 1.0f, label = "scale")
    val imageSize = itemWidth * 0.72f
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(itemWidth)
            .clickable { onClick() }
            .scale(scale)
    ) {
        Box(
            modifier = Modifier
                .size(imageSize)
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
            // 本地 assets 图片加载
            val bitmap = remember(assetPath) {
                try {
                    context.assets.open(assetPath).use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                // 加载失败时显示渐变色占位
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )
                            )
                        )
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(imageSize * 0.38f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Beauty tabs enum - used by BeautyPanel for tab navigation.
 * 3 tabs: Face (smoothing/whitening/slim/eyes) / Makeup (lip/blush/eyebrow) / Body (shape/legs)
 */
private enum class BeautyTab(val labelRes: Int, val icon: ImageVector) {
    FACE(R.string.facial_refinement, Icons.Rounded.FaceRetouchingNatural),
    MAKEUP(R.string.makeup_adjustment, Icons.Rounded.ColorLens),
    BODY(R.string.body_management, Icons.Rounded.SelfImprovement)
}

/**
 * 美颜面板（参考美图秀秀风格）
 *
 * 交互逻辑：
 * - 底部横向分类 Tab，点击切换当前分类
 * - 每个 Tab 下只展示本分类的 Slider / 色板
 * - 点击取景区空白区域（onDismiss）关闭面板
 */
@Composable
fun BeautyPanel(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit,
    onDismiss: () -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * PANEL_HEIGHT_RATIO
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = BeautyTab.values()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // 半透明渐变背景遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight + 24.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {

                // ── 拖拽把手 ──
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )

                // ── 当前分类内容区（可滚动）──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (tabs[selectedTab]) {
                        BeautyTab.FACE -> {
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
                        BeautyTab.MAKEUP -> {
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
                        BeautyTab.BODY -> {
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

                // ── 底部横向分类 Tab 栏（3 个 Tab 均分宽度）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp)
                ) {
                    tabs.forEach { tab ->
                        val index = tab.ordinal
                        val isSelected = selectedTab == index
                        val tabLabel = stringResource(tab.labelRes)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable { selectedTab = index }
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tabLabel,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BeautySelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    // 保留兼容签名，内部委托给 BeautyPanel（不带 dismiss 的内嵌场景）
    BeautyPanelContent(settings = settings, onSettingsChanged = onSettingsChanged)
}

/**
 * 内嵌版内容（供 ControlPanel 内部复用，无 dismiss 按钮）
 */
@Composable
private fun BeautyPanelContent(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    val tabs = BeautyTab.values()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 横向 Tab 栏（均分宽度）
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val index = tab.ordinal
                val isSelected = selectedTab == index
                val tabLabel = stringResource(tab.labelRes)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tabLabel,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 当前 Tab 内容
        when (tabs[selectedTab]) {
            BeautyTab.FACE -> {
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
            BeautyTab.MAKEUP -> {
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
            BeautyTab.BODY -> {
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
    beautySettings: BeautySettings = BeautySettings(),
    onBeautySettingsChanged: (BeautySettings) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * PANEL_HEIGHT_RATIO

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // 半透明渐变背景遮罩（与其他面板一致）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight + 24.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── 拖拽把手（点击关闭面板，消费事件避免透传到外层 Box）──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onClose() }
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    )
                }

                // ── 白平衡控制（模式选择芯片）──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.white_balance),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }

                // ── 曝光控制 ──
                val exposureValueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat()
                val exposureDisplayText = if (exposure >= 0) "+$exposure" else "$exposure"
                ProModeSlider(
                    label = stringResource(R.string.exposure),
                    valueText = exposureDisplayText,
                    isValueChanged = exposure != 0,
                    sliderContent = {
                        Slider(
                            value = exposure.toFloat(),
                            valueRange = exposureValueRange,
                            steps = if (exposureRange.last > exposureRange.first) {
                                exposureRange.last - exposureRange.first - 1
                            } else {
                                0
                            },
                            onValueChange = { newValue -> onExposureChange(newValue.toInt()) },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            thumb = { ProModeThumb() },
                            track = { state ->
                                ProModeTrack(
                                    fraction = state.valueRange.run {
                                        (exposure.toFloat() - start) / (endInclusive - start)
                                            .coerceAtLeast(0.001f)
                                    }.coerceIn(0f, 1f)
                                )
                            }
                        )
                    }
                )

                // ── 分隔线 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )

                // ── 专业调色（对比度 / 饱和度 / 色温） ──
                // 大美丽引擎路径：参数通过 BeautyParamsConverter 映射到 Shader
                ProModeSlider(
                    label = stringResource(R.string.contrast),
                    valueText = if (kotlin.math.abs(beautySettings.contrast - 50f) > 0.5f)
                        beautySettings.contrast.toInt().toString() else "--",
                    isValueChanged = kotlin.math.abs(beautySettings.contrast - 50f) > 0.5f,
                    sliderContent = {
                        Slider(
                            value = beautySettings.contrast,
                            valueRange = 0f..200f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(contrast = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            thumb = { ProModeThumb() },
                            track = { state ->
                                ProModeTrack(
                                    fraction = state.valueRange.run {
                                        (beautySettings.contrast - start) / (endInclusive - start)
                                    }.coerceIn(0f, 1f)
                                )
                            }
                        )
                    }
                )

                ProModeSlider(
                    label = stringResource(R.string.saturation),
                    valueText = if (kotlin.math.abs(beautySettings.saturation - 100f) > 0.5f)
                        beautySettings.saturation.toInt().toString() else "--",
                    isValueChanged = kotlin.math.abs(beautySettings.saturation - 100f) > 0.5f,
                    sliderContent = {
                        Slider(
                            value = beautySettings.saturation,
                            valueRange = 0f..200f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(saturation = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            thumb = { ProModeThumb() },
                            track = { state ->
                                ProModeTrack(
                                    fraction = state.valueRange.run {
                                        (beautySettings.saturation - start) / (endInclusive - start)
                                    }.coerceIn(0f, 1f)
                                )
                            }
                        )
                    }
                )

                ProModeSlider(
                    label = stringResource(R.string.color_temperature),
                    valueText = if (kotlin.math.abs(beautySettings.temperature - 5000f) > 50f)
                        "${beautySettings.temperature.toInt()}K" else "--",
                    isValueChanged = kotlin.math.abs(beautySettings.temperature - 5000f) > 50f,
                    sliderContent = {
                        Slider(
                            value = beautySettings.temperature,
                            valueRange = 2000f..8000f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(temperature = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            thumb = { ProModeThumb() },
                            track = { state ->
                                ProModeTrack(
                                    fraction = state.valueRange.run {
                                        (beautySettings.temperature - start) / (endInclusive - start)
                                    }.coerceIn(0f, 1f)
                                )
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * 专业模式统一滑块行布局：标签 + 值显示 + 滑块内容插槽
 */
@Composable
private fun ProModeSlider(
    label: String,
    valueText: String,
    isValueChanged: Boolean,
    sliderContent: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = if (isValueChanged) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        sliderContent()
    }
}

/**
 * 专业模式统一滑块 Thumb（带按压缩放动画）
 */
@Composable
private fun ProModeThumb() {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(if (isPressed) 1.4f else 1f, label = "thumbScale")
    Spacer(
        modifier = Modifier
            .size(20.dp)
            .scale(thumbScale)
            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    )
}

/**
 * 专业模式统一滑块 Track
 */
@Composable
private fun ProModeTrack(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.primary
                        )
                    )
                )
        )
    }
}
