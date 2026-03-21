package com.picme.features.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.picme.R
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.gallery.components.MediaPager
import com.picme.core.designsystem.PicMeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: MediaViewModel,
    onNavigateBack: () -> Unit
) {
    val groupedMedia by viewModel.groupedMedia.collectAsState()
    val groupingMode by viewModel.groupingMode.collectAsState()
    
    var selectedMediaIndex by remember { mutableStateOf<Int?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    val allFlatMedia = remember(groupedMedia) { groupedMedia.flatMap { it.items } }

    BackHandler {
        if (selectedMediaIndex != null) {
            selectedMediaIndex = null
        } else if (isSelectionMode) {
            isSelectionMode = false
            selectedIds.clear()
        } else {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            if (selectedMediaIndex == null) {
                TopAppBar(
                    title = {
                        Text(
                            if (isSelectionMode) stringResource(R.string.selected_items, selectedIds.size)
                            else stringResource(R.string.gallery)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            if (isSelectionMode) {
                                isSelectionMode = false
                                selectedIds.clear()
                            } else onNavigateBack() 
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                if (selectedIds.size == allFlatMedia.size) selectedIds.clear()
                                else {
                                    selectedIds.clear()
                                    selectedIds.addAll(allFlatMedia.map { it.id })
                                }
                            }) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = null)
                            }
                            IconButton(onClick = {
                                viewModel.deleteMediaByIds(selectedIds.toList())
                                isSelectionMode = false
                                selectedIds.clear()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = null)
                            }
                        } else {
                            IconButton(onClick = { /* TODO: Sort */ }) {
                                Icon(Icons.Rounded.Sort, contentDescription = null)
                            }
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    GroupingMode.values().forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.name) },
                                            onClick = { viewModel.setGroupingMode(mode); showMenu = false },
                                            leadingIcon = { if (groupingMode == mode) Icon(Icons.Rounded.Check, null) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                groupedMedia.forEach { group ->
                    if (group.title.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = group.title,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    items(group.items, key = { it.id }) { asset ->
                        val isSelected = selectedIds.contains(asset.id)
                        MediaItem(
                            asset = asset,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    if (isSelected) selectedIds.remove(asset.id) else selectedIds.add(asset.id)
                                } else {
                                    selectedMediaIndex = allFlatMedia.indexOf(asset)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds.add(asset.id)
                                }
                            }
                        )
                    }
                }
            }

            if (selectedMediaIndex != null) {
                MediaPager(
                    assets = allFlatMedia,
                    initialIndex = selectedMediaIndex!!,
                    onClose = { selectedMediaIndex = null },
                    onDelete = { asset ->
                        viewModel.deleteMediaByIds(listOf(asset.id))
                        val newAllFlat = allFlatMedia.filter { it.id != asset.id }
                        if (newAllFlat.isEmpty()) selectedMediaIndex = null
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItem(
    asset: MediaAsset,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(asset.uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (asset.type == MediaType.VIDEO) {
            Icon(
                Icons.Rounded.PlayCircle,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
        }

        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.Black.copy(alpha = 0.3f) else Color.Transparent)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                )
            }
        }
    }
}
