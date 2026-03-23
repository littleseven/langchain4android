package com.picme.features.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.picme.R
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.gallery.MediaViewModel
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPager(
    assets: List<MediaAsset>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (MediaAsset) -> Unit,
    onNavigateToOcr: (MediaAsset) -> Unit,
    onStartOcr: (String) -> Unit,
    onDismissOcr: () -> Unit,
    ocrState: StateFlow<MediaViewModel.OcrResult?>
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { assets.size })
    var showInfo by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp
        ) { pageIndex ->
            val asset = assets[pageIndex]
            if (asset.type == MediaType.VIDEO) {
                VideoPlayer(uri = asset.uri)
            } else {
                AsyncImage(
                    model = asset.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = { showInfo = !showInfo },
                            onLongClick = {
                                // [RD] 按照 FEATURES.md 触发触感反馈并启动 OCR
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartOcr(asset.uri)
                            }
                        ),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Top Controls
        MediaPagerTopControls(
            onClose = onClose,
            showInfo = showInfo,
            onToggleInfo = { showInfo = !showInfo },
            onDelete = { onDelete(assets[pagerState.currentPage]) },
            onNavigateToOcr = { onNavigateToOcr(assets[pagerState.currentPage]) },
            onStartOcr = { onStartOcr(assets[pagerState.currentPage].uri) }
        )

        // Source Info Overlay (Bottom Left)
        val currentAsset = assets[pagerState.currentPage]
        SourceInfoOverlay(
            visible = showInfo && currentAsset.source != null,
            source = currentAsset.source
        )
        
        // OCR 识别结果浮层
        OcrResultOverlay(
            ocrState = ocrState,
            onDismiss = onDismissOcr
        )
    }
}

@Composable
private fun OcrResultOverlay(
    ocrState: StateFlow<MediaViewModel.OcrResult?>,
    onDismiss: () -> Unit
) {
    val result by ocrState.collectAsState()
    
    AnimatedVisibility(
        visible = result != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (val ocrResult = result) {
                        null -> {}
                        MediaViewModel.OcrResult.Loading -> {
                            androidx.compose.material3.CircularProgressIndicator()
                            Text(stringResource(R.string.ocr_progress))
                        }
                        is MediaViewModel.OcrResult.Success -> {
                            Text(
                                text = ocrResult.text,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                androidx.compose.material3.OutlinedButton(onClick = { /* 复制逻辑 */ }) {
                                    Text(stringResource(id = R.string.ocr_copy))
                                }
                                androidx.compose.material3.Button(onClick = { /* 分享逻辑 */ }) {
                                    Text(stringResource(id = R.string.ocr_share))
                                }
                            }
                        }
                        is MediaViewModel.OcrResult.Error -> {
                            Icon(Icons.Rounded.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(ocrResult.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPagerTopControls(
    onClose: () -> Unit,
    showInfo: Boolean,
    onToggleInfo: () -> Unit,
    onDelete: () -> Unit,
    onNavigateToOcr: () -> Unit,
    onStartOcr: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            )
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // OCR 识别按钮
            IconButton(
                onClick = { onStartOcr() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.TextSnippet,
                    contentDescription = stringResource(R.string.ocr_action_label),
                    tint = Color.White
                )
            }
            
            // Info Toggle Switch
            IconButton(
                onClick = onToggleInfo,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (showInfo) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    } else {
                        Color.Black.copy(alpha = 0.5f)
                    }
                )
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = if (showInfo) Color.Black else Color.White
                )
            }

            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = Color.White)
            }
        }
    }
}

@Composable
private fun SourceInfoOverlay(
    visible: Boolean,
    source: String?
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { height -> height }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { height -> height }),
        modifier = Modifier
            .padding(16.dp)
            .navigationBarsPadding()
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Source: ${source?.uppercase(Locale.getDefault())}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VideoPlayer(uri: String) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
