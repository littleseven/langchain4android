package com.picme.features.camera.components

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.domain.model.BeautySettings
import com.picme.features.camera.model.FilterType
import com.picme.features.camera.GridType
import java.util.Locale

@Composable
fun HyperOSLiveTile(
    isRecording: Boolean,
    isStable: Boolean,
    recordingTime: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isRecording || !isStable,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .height(36.dp)
                .padding(horizontal = 16.dp),
            color = Color.Black,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isRecording) {
                    Icon(
                        imageVector = Icons.Rounded.Videocam,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = recordingTime,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                } else if (!isStable) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = Color.Yellow,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.live_unstable),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PortraitGuidance(faceOffset: Offset, alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(alpha = alpha)) {
        val color = Color.Cyan.copy(alpha = 0.6f)
        val stroke = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f))
        
        drawCircle(
            color = color,
            radius = 80.dp.toPx(),
            center = Offset(size.width / 2, size.height * 0.4f),
            style = stroke
        )
        
        drawLine(
            color = color,
            start = Offset(0f, size.height / 3),
            end = Offset(size.width, size.height / 3),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )

        drawLine(
            color = Color.Yellow.copy(alpha = 0.4f),
            start = faceOffset,
            end = Offset(size.width / 2, size.height * 0.4f),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
        )
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
                drawLine(color, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), strokeWidth, pathEffect = pathEffect)
                drawLine(color, Offset(size.width * 2 / 3, 0f), Offset(size.width * 2 / 3, size.height), strokeWidth, pathEffect = pathEffect)
                drawLine(color, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), strokeWidth, pathEffect = pathEffect)
                drawLine(color, Offset(0f, size.height * 2 / 3), Offset(size.width, size.height * 2 / 3), strokeWidth, pathEffect = pathEffect)
            }
            GridType.GOLDEN -> {
                val ratio = 0.618f
                drawLine(color, Offset(size.width * (1 - ratio), 0f), Offset(size.width * (1 - ratio), size.height), strokeWidth, pathEffect = pathEffect)
                drawLine(color, Offset(size.width * ratio, 0f), Offset(size.width * ratio, size.height), strokeWidth, pathEffect = pathEffect)
                drawLine(color, Offset(0f, size.height * (1 - ratio)), Offset(size.width, size.height * (1 - ratio)), strokeWidth, pathEffect = pathEffect)
                drawLine(color, Offset(0f, size.height * ratio), Offset(size.width, size.height * ratio), strokeWidth, pathEffect = pathEffect)
            }
            else -> {}
        }
    }
}

@Composable
fun FaceFocusIndicator(offset: Offset, alpha: Float) {
    val sizeDp = 60.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = alpha)
    ) {
        Canvas(
            modifier = Modifier
                .size(sizeDp)
                .offset {
                    IntOffset(
                        (offset.x - sizeDp.toPx() / 2).toInt(),
                        (offset.y - sizeDp.toPx() / 2).toInt()
                    )
                }
        ) {
            val color = Color.Yellow
            val strokeWidth = 2.dp.toPx()
            val bracketLen = 10.dp.toPx()
            
            drawLine(color, Offset(0f, size.height/2), Offset(size.width*0.3f, size.height/2), strokeWidth)
            drawLine(color, Offset(size.width*0.7f, size.height/2), Offset(size.width, size.height/2), strokeWidth)
            drawLine(color, Offset(size.width/2, 0f), Offset(size.width/2, size.height*0.3f), strokeWidth)
            drawLine(color, Offset(size.width/2, size.height*0.7f), Offset(size.width/2, size.height), strokeWidth)
            
            drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), strokeWidth)
            drawLine(color, Offset(0f, 0f), Offset(0f, bracketLen), strokeWidth)
            drawLine(color, Offset(size.width, 0f), Offset(size.width-bracketLen, 0f), strokeWidth)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, bracketLen), strokeWidth)
            drawLine(color, Offset(0f, size.height), Offset(bracketLen, size.height), strokeWidth)
            drawLine(color, Offset(0f, size.height), Offset(0f, size.height-bracketLen), strokeWidth)
            drawLine(color, Offset(size.width, size.height), Offset(size.width-bracketLen, size.height), strokeWidth)
            drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height-bracketLen), strokeWidth)
        }
    }
}

@Composable
fun CameraInfoOverlay(
    lensFacing: Int,
    zoomRatio: Float,
    aspectRatio: Int,
    filter: FilterType,
    beautySettings: BeautySettings,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 200.dp),
        color = Color.Black.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.camera_info),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            InfoGroup(title = stringResource(R.string.info_group_device)) {
                InfoItem(
                    label = stringResource(R.string.info_lens_facing),
                    value = if (lensFacing == 1) stringResource(R.string.info_back) else stringResource(R.string.info_front)
                )
                InfoItem(
                    label = stringResource(R.string.info_zoom),
                    value = String.format(Locale.US, "%.1fx", zoomRatio)
                )
            }
            
            InfoGroup(title = stringResource(R.string.info_group_settings)) {
                InfoItem(
                    label = stringResource(R.string.info_aspect_ratio),
                    value = if (aspectRatio == 0) "4:3" else "16:9"
                )
                InfoItem(
                    label = stringResource(R.string.info_filter),
                    value = stringResource(filter.displayNameRes)
                )
                InfoItem(
                    label = stringResource(R.string.info_beauty),
                    value = String.format(Locale.US, "%d%%", (beautySettings.smoothing * 100).toInt())
                )
                if (exposureCompensation != 0) {
                    InfoItem(
                        label = stringResource(R.string.ev),
                        value = if (exposureCompensation > 0) "+$exposureCompensation" else exposureCompensation.toString()
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
        }
    }
}

@Composable
private fun InfoGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title.uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "$label:", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
