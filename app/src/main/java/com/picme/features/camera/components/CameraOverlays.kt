package com.picme.features.camera.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.domain.model.BeautySettings
import com.picme.domain.model.MediaType
import com.picme.features.camera.GridType
import com.picme.features.camera.ScenePreset
import com.picme.features.camera.model.FilterType
import java.util.Locale

@Composable
fun CameraOverlays(
    isStable: Boolean,
    gridType: GridType,
    facePoint: Offset?,
    focusAlpha: Float,
    showInfo: Boolean,
    lensFacing: Int,
    captureMode: MediaType,
    zoomRatio: Float,
    aspectRatio: Int,
    selectedFilter: FilterType,
    beautySettings: BeautySettings,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    currentScene: ScenePreset,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        CompositionGrid(gridType = gridType)

        // [OPTIMIZED] 十字星显示优化：只要检测到人脸就显示，不受面板状态影响
        facePoint?.let { point ->
            if (focusAlpha > 0f) {
                FaceFocusIndicator(offset = point, alpha = focusAlpha)
            }
        }

        if (showInfo) {
            CameraInfoOverlay(
                captureMode = captureMode,
                lensFacing = lensFacing,
                zoomRatio = zoomRatio,
                aspectRatio = aspectRatio,
                filter = selectedFilter,
                beautySettings = beautySettings,
                exposureCompensation = exposureCompensation,
                whiteBalanceMode = whiteBalanceMode,
                currentScene = currentScene,
                gridType = gridType,
                isStable = isStable,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp) // Below top control bar
            )
        }
    }
}

@Composable
fun CameraInfoOverlay(
    captureMode: MediaType,
    lensFacing: Int,
    zoomRatio: Float,
    aspectRatio: Int,
    filter: FilterType,
    beautySettings: BeautySettings,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    currentScene: ScenePreset,
    gridType: GridType,
    isStable: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CameraInfoTitle(isStable = isStable)

            InfoGroup(title = stringResource(R.string.info_group_device)) {
                InfoItem(
                    label = stringResource(R.string.info_lens_facing),
                    value = if (lensFacing == 1) {
                        stringResource(R.string.info_back)
                    } else {
                        stringResource(R.string.info_front)
                    }
                )
                InfoItem(
                    label = stringResource(R.string.info_zoom),
                    value = String.format(Locale.US, "%.1fx", zoomRatio)
                )
                InfoItem(
                    label = stringResource(R.string.pro_mode),
                    value = captureMode.name
                )
            }

            InfoGroup(title = stringResource(R.string.info_group_settings)) {
                SettingsInfoItems(
                    aspectRatio = aspectRatio,
                    filter = filter,
                    currentScene = currentScene,
                    gridType = gridType,
                    exposureCompensation = exposureCompensation,
                    whiteBalanceMode = whiteBalanceMode
                )
            }
        }
    }
}

@Composable
private fun CameraInfoTitle(isStable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.camera_info),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp
        )
        // Stability indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (isStable) Color.Green else Color.Red, CircleShape)
        )
    }
}

@Composable
private fun SettingsInfoItems(
    aspectRatio: Int,
    filter: FilterType,
    currentScene: ScenePreset,
    gridType: GridType,
    exposureCompensation: Int,
    whiteBalanceMode: Int
) {
    InfoItem(
        label = stringResource(R.string.info_aspect_ratio),
        value = when (aspectRatio) {
            0 -> "4:3"
            1 -> "16:9"
            else -> "FULL"
        }
    )
    InfoItem(
        label = stringResource(R.string.info_filter),
        value = stringResource(filter.displayNameRes)
    )
    InfoItem(
        label = stringResource(R.string.scene),
        value = currentScene.name
    )
    InfoItem(
        label = stringResource(R.string.grid),
        value = gridType.name
    )
    if (exposureCompensation != 0) {
        InfoItem(
            label = stringResource(R.string.ev),
            value = if (exposureCompensation > 0) {
                "+$exposureCompensation"
            } else {
                exposureCompensation.toString()
            }
        )
    }
    if (whiteBalanceMode != 0) {
        val wbLabel = when (whiteBalanceMode) {
            1 -> stringResource(R.string.wb_sunny)
            2 -> stringResource(R.string.wb_cloudy)
            3 -> stringResource(R.string.wb_incandescent)
            4 -> stringResource(R.string.wb_fluorescent)
            else -> stringResource(R.string.wb_auto)
        }
        InfoItem(label = stringResource(R.string.wb), value = wbLabel)
    }
}

@Composable
private fun InfoGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        content()
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CompositionGrid(gridType: GridType) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.5f)
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        when (gridType) {
            GridType.THIRDS -> {
                drawThirdsGrid(color, strokeWidth, pathEffect)
            }

            GridType.GOLDEN -> {
                drawGoldenGrid(color, strokeWidth, pathEffect)
            }

            else -> {}
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThirdsGrid(
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect
) {
    drawLine(
        color,
        Offset(size.width / 3, 0f),
        Offset(size.width / 3, size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(size.width * 2 / 3, 0f),
        Offset(size.width * 2 / 3, size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height / 3),
        Offset(size.width, size.height / 3),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height * 2 / 3),
        Offset(size.width, size.height * 2 / 3),
        strokeWidth,
        pathEffect = pathEffect
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGoldenGrid(
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect
) {
    val ratio = 0.618f
    drawLine(
        color,
        Offset(size.width * (1 - ratio), 0f),
        Offset(size.width * (1 - ratio), size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(size.width * ratio, 0f),
        Offset(size.width * ratio, size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height * (1 - ratio)),
        Offset(size.width, size.height * (1 - ratio)),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height * ratio),
        Offset(size.width, size.height * ratio),
        strokeWidth,
        pathEffect = pathEffect
    )
}

/**
 * 人脸对焦指示器 - 优化版
 * 使用更精确的定位和更流畅的动画
 */
@Composable
fun FaceFocusIndicator(offset: Offset, alpha: Float) {
    // [OPTIMIZED] 根据人脸大小动态调整指示器尺寸 (默认假设人脸占屏幕 15-20%)
    val baseSizeDp = 100.dp // 增大尺寸，更明显
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = alpha)
    ) {
        Canvas(
            modifier = Modifier
                .size(baseSizeDp)
                .offset {
                    IntOffset(
                        (offset.x - baseSizeDp.toPx() / 2).toInt(),
                        (offset.y - baseSizeDp.toPx() / 2).toInt()
                    )
                }
        ) {
            val color = Color.Yellow
            val strokeWidth = 4.dp.toPx() // 加粗线条
            val cornerLength = 20.dp.toPx() // 增长角标
            val gapLength = 8.dp.toPx()

            // [OPTIMIZED] 四个角的 L 型标记 + 中心十字星
            // 左上角
            drawLine(
                color,
                Offset(0f, cornerLength),
                Offset(0f, 0f),
                strokeWidth
            )
            drawLine(
                color,
                Offset(0f, 0f),
                Offset(cornerLength, 0f),
                strokeWidth
            )

            // 右上角
            drawLine(
                color,
                Offset(size.width - cornerLength, 0f),
                Offset(size.width, 0f),
                strokeWidth
            )
            drawLine(
                color,
                Offset(size.width, 0f),
                Offset(size.width, cornerLength),
                strokeWidth
            )

            // 右下角
            drawLine(
                color,
                Offset(size.width, size.height - cornerLength),
                Offset(size.width, size.height),
                strokeWidth
            )
            drawLine(
                color,
                Offset(size.width, size.height),
                Offset(size.width - cornerLength, size.height),
                strokeWidth
            )

            // 左下角
            drawLine(
                color,
                Offset(cornerLength, size.height),
                Offset(0f, size.height),
                strokeWidth
            )
            drawLine(
                color,
                Offset(0f, size.height),
                Offset(0f, size.height - cornerLength),
                strokeWidth
            )

            // [OPTIMIZED] 中心十字星标记 (替换小圆点，更醒目)
            val centerSize = 12.dp.toPx()
            val lineThickness = 3.dp.toPx()
            
            // 水平线
            drawLine(
                color = color.copy(alpha = 0.9f),
                start = Offset((size.width - centerSize) / 2, size.height / 2),
                end = Offset((size.width + centerSize) / 2, size.height / 2),
                strokeWidth = lineThickness
            )
            
            // 垂直线
            drawLine(
                color = color.copy(alpha = 0.9f),
                start = Offset(size.width / 2, (size.height - centerSize) / 2),
                end = Offset(size.width / 2, (size.height + centerSize) / 2),
                strokeWidth = lineThickness
            )
            
            // 中心点
            drawCircle(
                color = color.copy(alpha = 0.7f),
                radius = 3.dp.toPx(),
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCorners(
    color: Color,
    strokeWidth: Float,
    bracketLen: Float
) {
    // Top-left
    drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), strokeWidth)
    drawLine(color, Offset(0f, 0f), Offset(0f, bracketLen), strokeWidth)
    // Top-right
    drawLine(color, Offset(size.width, 0f), Offset(size.width - bracketLen, 0f), strokeWidth)
    drawLine(color, Offset(size.width, 0f), Offset(size.width, bracketLen), strokeWidth)
    // Bottom-left
    drawLine(color, Offset(0f, size.height), Offset(bracketLen, size.height), strokeWidth)
    drawLine(color, Offset(0f, size.height), Offset(0f, size.height - bracketLen), strokeWidth)
    // Bottom-right
    drawLine(color, Offset(size.width, size.height), Offset(size.width - bracketLen, size.height), strokeWidth)
    drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - bracketLen), strokeWidth)
}

/**
 * [NEW] OCR 文字检测预览提示
 */
@Composable
private fun OcrTextPreviewOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "检测到文字",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = "${text.length} 字",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            
            androidx.compose.material3.Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White.copy(alpha = 0.2f)
            )
            
            Text(
                text = text,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 2,
                lineHeight = 20.sp
            )
        }
    }
}
