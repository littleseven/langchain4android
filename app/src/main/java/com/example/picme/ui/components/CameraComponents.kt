package com.example.picme.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.picme.R
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.model.MediaType
import com.example.picme.domain.BeautySettings
import com.example.picme.ui.model.FilterType

@Composable
fun SideBarItem(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                else Color.Black.copy(alpha = 0.3f),
                CircleShape
            )
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
fun CameraCaptureButton(
    isRecording: Boolean,
    mode: MediaType,
    onClick: () -> Unit
) {
    val color = if (mode == MediaType.VIDEO) Color.Red else Color.White
    Box(
        modifier = Modifier
            .size(80.dp)
            .border(width = 4.dp, color = color.copy(alpha = 0.5f), shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
fun ControlPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color.Black.copy(alpha = 0.8f),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .navigationBarsPadding()
                .padding(vertical = 24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                content()
            }
        }
    }
}

@Composable
fun BeautySelector(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BeautySlider(
            label = stringResource(R.string.smoothing),
            value = settings.smoothing,
            onValueChange = { onSettingsChanged(settings.copy(smoothing = it)) }
        )
        BeautySlider(
            label = stringResource(R.string.slim_face),
            value = settings.slimFace,
            onValueChange = { onSettingsChanged(settings.copy(slimFace = it)) }
        )
        BeautySlider(
            label = stringResource(R.string.big_eyes),
            value = settings.bigEyes,
            onValueChange = { onSettingsChanged(settings.copy(bigEyes = it)) }
        )
        BeautySlider(
            label = stringResource(R.string.youth),
            value = settings.youth,
            onValueChange = { onSettingsChanged(settings.copy(youth = it)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeautySlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.White,
            modifier = Modifier.width(80.dp),
            fontSize = 14.sp
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        )
    }
}

@Composable
fun FilterSelector(
    selectedFilter: FilterType,
    onFilterSelected: (FilterType) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp)
    ) {
        items(FilterType.entries) { filter ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onFilterSelected(filter) }
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .border(
                            width = 3.dp,
                            color = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            color = Color.LightGray,
                            colorFilter = ColorFilter.colorMatrix(filter.getColorMatrix())
                        )
                    }
                }
                Text(
                    text = stringResource(filter.displayNameRes),
                    color = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun GalleryThumbnail(
    lastMedia: MediaAsset?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (lastMedia != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(lastMedia.uri)
                    .decoderFactory(VideoFrameDecoder.Factory())
                    .build(),
                contentDescription = "Gallery",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
        }
    }
}
