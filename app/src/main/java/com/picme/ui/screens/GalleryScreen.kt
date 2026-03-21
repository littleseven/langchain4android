package com.picme.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.picme.R
import com.picme.data.model.MediaAsset
import com.picme.data.model.MediaType
import com.picme.ui.theme.PicMeTheme
import com.picme.ui.viewmodel.GroupingMode
import com.picme.ui.viewmodel.MediaGroup
import com.picme.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun GalleryScreen(
    viewModel: MediaViewModel,
    onNavigateBack: () -> Unit
) {
    val groupedMedia by viewModel.groupedMedia.collectAsState()
    val allMedia by viewModel.allMedia.collectAsState()
    val groupingMode by viewModel.groupingMode.collectAsState()

    GalleryContent(
        groupedMedia = groupedMedia,
        allMedia = allMedia,
        groupingMode = groupingMode,
        onSetGroupingMode = { viewModel.setGroupingMode(it) },
        onDeleteMedia = { viewModel.deleteMediaByIds(it) },
        onNavigateBack = onNavigateBack,
        onEditMedia = { /* handled via state in GalleryContent if needed */ },
        viewModel = viewModel
    )
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
private fun GalleryContent(
    groupedMedia: List<MediaGroup>,
    allMedia: List<MediaAsset>,
    groupingMode: GroupingMode,
    onSetGroupingMode: (GroupingMode) -> Unit,
    onDeleteMedia: (List<Long>) -> Unit,
    onNavigateBack: () -> Unit,
    onEditMedia: (MediaAsset) -> Unit,
    viewModel: MediaViewModel? = null
) {
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var editingAsset by remember { mutableStateOf<MediaAsset?>(null) }
    
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    // Flatten assets based on the CURRENT groupedMedia to ensure pager matches grid order
    val currentOrderedAssets = remember(groupedMedia) {
        groupedMedia.flatMap { it.items }
    }

    val closeSelection = {
        isSelectionMode = false
        selectedIds.clear()
    }

    BackHandler(enabled = isSelectionMode || selectedIndex != -1 || editingAsset != null) {
        if (editingAsset != null) editingAsset = null
        else if (selectedIndex != -1) selectedIndex = -1
        else closeSelection()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Text(if (isSelectionMode) stringResource(R.string.selected_items, selectedIds.size) else stringResource(R.string.gallery))
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (isSelectionMode) closeSelection() else onNavigateBack() }) {
                            Icon(if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                if (selectedIds.size == allMedia.size) selectedIds.clear()
                                else { selectedIds.clear(); selectedIds.addAll(allMedia.map { it.id }) }
                            }) { Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all)) }
                            IconButton(onClick = { onDeleteMedia(selectedIds.toList()); closeSelection() }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                )
                
                // Grouping Mode Selector
                ScrollableTabRow(
                    selectedTabIndex = groupingMode.ordinal,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {}
                ) {
                    GroupingMode.entries.forEach { mode ->
                        val label = when(mode) {
                            GroupingMode.NONE -> stringResource(R.string.group_none)
                            GroupingMode.DATE -> stringResource(R.string.group_date)
                            GroupingMode.FACE -> stringResource(R.string.group_face)
                            GroupingMode.PERSON -> stringResource(R.string.group_person)
                        }
                        Tab(
                            selected = groupingMode == mode,
                            onClick = { onSetGroupingMode(mode) },
                            text = { Text(label, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (allMedia.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.no_media))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(1.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                groupedMedia.forEach { group ->
                    if (group.title.isNotEmpty()) {
                        item(span = { GridItemSpan(3) }) {
                            Text(
                                text = group.title,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(group.items) { asset ->
                        MediaGridItem(
                            asset = asset,
                            isSelected = selectedIds.contains(asset.id),
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    if (selectedIds.contains(asset.id)) {
                                        selectedIds.remove(asset.id)
                                        if (selectedIds.isEmpty()) isSelectionMode = false
                                    } else selectedIds.add(asset.id)
                                } else {
                                    // Set index based on the current flattened order
                                    selectedIndex = currentOrderedAssets.indexOf(asset)
                                }
                            },
                            onLongClick = { if (!isSelectionMode) { isSelectionMode = true; selectedIds.add(asset.id) } }
                        )
                    }
                }
            }
        }
    }

    if (selectedIndex != -1) {
        FullScreenPager(
            assets = currentOrderedAssets,
            initialIndex = selectedIndex,
            onDismiss = { selectedIndex = -1 },
            onEdit = { editingAsset = it }
        )
    }

    if (viewModel != null) {
        editingAsset?.let { asset ->
            ImageEditScreen(asset = asset, viewModel = viewModel, onDismiss = { editingAsset = null })
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    asset: MediaAsset,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.aspectRatio(1f).background(Color.LightGray).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(asset.uri).decoderFactory(VideoFrameDecoder.Factory()).build(),
            contentDescription = asset.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (asset.type == MediaType.VIDEO) {
            Icon(Icons.Default.PlayCircle, contentDescription = stringResource(R.string.video), modifier = Modifier.align(Alignment.Center).size(32.dp), tint = Color.White.copy(alpha = 0.8f))
        }
        if (isSelectionMode) {
            Box(modifier = Modifier.fillMaxSize().background(if (isSelected) Color.Black.copy(alpha = 0.3f) else Color.Transparent))
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.align(Alignment.TopEnd),
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = Color.White)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullScreenPager(assets: List<MediaAsset>, initialIndex: Int, onDismiss: () -> Unit, onEdit: (MediaAsset) -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { assets.size }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 16.dp) { page ->
            val asset = assets[page]
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (asset.type == MediaType.VIDEO) VideoPlayer(uri = asset.uri.toUri())
                else AsyncImage(model = asset.uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val currentAsset = assets[pagerState.currentPage]
                if (currentAsset.type == MediaType.PHOTO) {
                    IconButton(onClick = { onEdit(currentAsset) }, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), tint = Color.White)
                    }
                }
                Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp)) {
                    Text(text = "${pagerState.currentPage + 1} / ${assets.size}", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(Media3Item.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(uri) {
        exoPlayer.setMediaItem(Media3Item.fromUri(uri))
        exoPlayer.prepare()
        onDispose { exoPlayer.release() }
    }
    AndroidView(factory = { PlayerView(context).apply { player = exoPlayer; useController = true } }, modifier = Modifier.fillMaxSize())
}

@Preview(showBackground = true)
@Composable
fun GalleryScreenPreview() {
    val sampleMedia = listOf(
        MediaAsset(id = 1, uri = "content://media/external/images/media/1", type = MediaType.PHOTO, captureDate = System.currentTimeMillis(), fileName = "IMG_1.jpg"),
        MediaAsset(id = 2, uri = "content://media/external/images/media/2", type = MediaType.VIDEO, captureDate = System.currentTimeMillis(), fileName = "VID_1.mp4"),
        MediaAsset(id = 3, uri = "content://media/external/images/media/3", type = MediaType.PHOTO, captureDate = System.currentTimeMillis(), fileName = "IMG_2.jpg")
    )
    val groupedMedia = listOf(
        MediaGroup("Today", sampleMedia)
    )
    PicMeTheme {
        GalleryContent(
            groupedMedia = groupedMedia,
            allMedia = sampleMedia,
            groupingMode = GroupingMode.DATE,
            onSetGroupingMode = {},
            onDeleteMedia = {},
            onNavigateBack = {},
            onEditMedia = {}
        )
    }
}
