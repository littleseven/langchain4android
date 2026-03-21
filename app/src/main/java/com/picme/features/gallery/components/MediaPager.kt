package com.picme.features.gallery.components

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.picme.R
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPager(
    assets: List<MediaAsset>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (MediaAsset) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { assets.size })
    var showInfo by remember { mutableStateOf(true) }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp
        ) { page ->
            val asset = assets[page]
            if (asset.type == MediaType.VIDEO) {
                VideoPlayer(uri = asset.uri)
            } else {
                AsyncImage(
                    model = asset.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Top Controls
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
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Info Toggle Switch
                IconButton(
                    onClick = { showInfo = !showInfo },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showInfo) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = if (showInfo) Color.Black else Color.White)
                }

                IconButton(
                    onClick = { onDelete(assets[pagerState.currentPage]) },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color.White)
                }
            }
        }

        // Source Info Overlay (Bottom Left)
        val currentAsset = assets[pagerState.currentPage]
        AnimatedVisibility(
            visible = showInfo && currentAsset.source != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).navigationBarsPadding()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text(
                        text = "Source: ${currentAsset.source?.uppercase()}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
