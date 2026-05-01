package com.picme.features.gallery

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.picme.R
import com.picme.domain.model.DuplicateGroup
import com.picme.domain.model.GroupTitleType
import com.picme.domain.model.GroupedMedia
import com.picme.domain.model.GroupingMode
import com.picme.domain.model.GroupingMode.DATE
import com.picme.domain.model.GroupingMode.FACE
import com.picme.domain.model.GroupingMode.LANDSCAPE
import com.picme.domain.model.GroupingMode.NONE
import com.picme.domain.model.GroupingMode.PERSON
import com.picme.domain.model.GroupingMode.SEXY
import com.picme.domain.model.GroupingMode.SWIMWEAR
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.gallery.components.MediaGroupHeader
import com.picme.features.gallery.components.MediaPager
import java.io.File

private fun resolveGroupTitle(context: Context, group: GroupedMedia): String {
    return when (group.titleType) {
        GroupTitleType.NONE -> group.titleValue
        GroupTitleType.DATE -> group.titleValue
        GroupTitleType.WITH_FACES -> context.getString(R.string.with_faces)
        GroupTitleType.NO_FACES -> context.getString(R.string.no_faces)
        GroupTitleType.PERSON -> context.getString(R.string.person_group, group.titleValue)
        GroupTitleType.LANDSCAPE -> context.getString(R.string.landscape)
        GroupTitleType.SWIMWEAR -> context.getString(R.string.swimwear)
        GroupTitleType.SEXY -> context.getString(R.string.sexy)
    }
}

private fun shareMediaAssets(context: Context, assets: List<MediaAsset>) {
    if (assets.isEmpty()) {
        return
    }

    val uris = assets.map { mediaAsset -> mediaAsset.uri.toUri() }
    val shareIntent = if (uris.size == 1) {
        val firstAsset = assets.first()
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            type = if (firstAsset.type == MediaType.VIDEO) "video/*" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    context.startActivity(Intent.createChooser(shareIntent, null))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: MediaViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    val groupedMedia by viewModel.groupedMedia.collectAsState()
    val groupingMode by viewModel.groupingMode.collectAsState()
    val showDuplicateManager by viewModel.showDuplicateManager.collectAsState()
    val duplicateGroups by viewModel.duplicateGroups.collectAsState()
    val isScanningDuplicates by viewModel.isScanningDuplicates.collectAsState()

    var selectedMediaIndex by remember { mutableStateOf<Int?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }


    val selectedIds = remember { mutableStateListOf<Long>() }

    val allFlatMedia = remember(groupedMedia) { groupedMedia.flatMap { it.items } }
    val mediaById = remember(allFlatMedia) { allFlatMedia.associateBy { mediaAsset -> mediaAsset.id } }
    val context = LocalContext.current

    // Store thumbnail positions for zoom animation
    val thumbnailPositions = remember { mutableStateMapOf<Long, Rect>() }
    var dragSelectionTargetSelected by remember { mutableStateOf(true) }
    val dragSelectionVisitedIds = remember { hashSetOf<Long>() }

    // 沉浸式模式
    val view = LocalView.current

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)

        // 隐藏状态栏和导航栏
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        // 设置沉浸式模式，滑动边缘时显示系统栏
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            // 恢复系统栏显示
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        when {
            showDuplicateManager -> viewModel.toggleDuplicateManager(false)
            selectedMediaIndex != null -> selectedMediaIndex = null
            isSelectionMode -> {
                isSelectionMode = false
                selectedIds.clear()
            }

            else -> onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            if (selectedMediaIndex == null && !showDuplicateManager) {
                GalleryTopBar(
                    isSelectionMode = isSelectionMode,
                    selectedCount = selectedIds.size,
                    groupingMode = groupingMode,
                    onNavigateBack = onNavigateBack,
                    onToggleSelectionMode = {
                        isSelectionMode = false
                        selectedIds.clear()
                    },
                    onSelectAll = {
                        if (selectedIds.size == allFlatMedia.size) {
                            selectedIds.clear()
                        } else {
                            selectedIds.clear()
                            selectedIds.addAll(allFlatMedia.map { it.id })
                        }
                    },
                    onDeleteSelected = {
                        viewModel.deleteMediaByIds(selectedIds.toList())
                        isSelectionMode = false
                        selectedIds.clear()
                    },
                    onShareSelected = {
                        val selectedAssets = selectedIds.mapNotNull { id -> mediaById[id] }
                        shareMediaAssets(context, selectedAssets)
                    },
                    onGroupingModeSelected = { mode -> viewModel.setGroupingMode(mode) },
                    onManageDuplicates = { viewModel.toggleDuplicateManager(true) },
                    onOpenTestDataTools = onNavigateToDebug
                )
            } else if (showDuplicateManager) {
                DuplicateManagerTopBar(
                    onNavigateBack = { viewModel.toggleDuplicateManager(false) },
                    onDeleteAllDuplicates = { viewModel.deleteAllDuplicatesExceptOne() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (showDuplicateManager) {
                DuplicateManagerScreen(
                    duplicateGroups = duplicateGroups,
                    isScanning = isScanningDuplicates,
                    onDeleteGroup = { group -> viewModel.deleteDuplicateGroup(group, 0) }
                )
            } else if (allFlatMedia.isEmpty()) {
                EmptyGalleryMessage()
            } else {
                MediaGrid(
                    context = context,
                    groupedMedia = groupedMedia,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    thumbnailPositions = thumbnailPositions,
                    mediaById = mediaById,
                    onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                    onMediaClick = { asset ->
                        if (isSelectionMode) {
                            if (selectedIds.contains(asset.id)) {
                                selectedIds.remove(asset.id)
                            } else {
                                selectedIds.add(asset.id)
                            }
                        } else {
                            selectedMediaIndex = allFlatMedia.indexOf(asset)
                        }
                    },
                    onMediaLongClick = { asset ->
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedIds.add(asset.id)
                        }
                    },
                    onDragSelectionStart = { asset ->
                        if (!isSelectionMode) {
                            isSelectionMode = true
                        }
                        dragSelectionVisitedIds.clear()
                        dragSelectionTargetSelected = !selectedIds.contains(asset.id)
                        if (dragSelectionTargetSelected) {
                            if (!selectedIds.contains(asset.id)) {
                                selectedIds.add(asset.id)
                            }
                        } else {
                            selectedIds.remove(asset.id)
                        }
                        dragSelectionVisitedIds.add(asset.id)
                    },
                    onDragSelectionItem = { asset ->
                        if (!dragSelectionVisitedIds.add(asset.id)) {
                            return@MediaGrid
                        }
                        if (dragSelectionTargetSelected) {
                            if (!selectedIds.contains(asset.id)) {
                                selectedIds.add(asset.id)
                            }
                        } else {
                            selectedIds.remove(asset.id)
                        }
                    },
                    onDragSelectionEnd = {
                        dragSelectionVisitedIds.clear()
                    }
                )
            }

            val currentMedia = selectedMediaIndex?.let { allFlatMedia.getOrNull(it) }
            val rect = currentMedia?.let { thumbnailPositions[it.id] }

            AnimatedVisibility(
                visible = selectedMediaIndex != null,
                enter = fadeIn(animationSpec = tween(300)) + scaleIn(
                    initialScale = 0.2f,
                    transformOrigin = rect?.let {
                        TransformOrigin(
                            (it.center.x / 1080f).coerceIn(0f, 1f),
                            (it.center.y / 1920f).coerceIn(0f, 1f)
                        )
                    } ?: TransformOrigin.Center,
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(animationSpec = tween(300)) + scaleOut(
                    targetScale = 0.2f,
                    transformOrigin = rect?.let {
                        TransformOrigin(
                            (it.center.x / 1080f).coerceIn(0f, 1f),
                            (it.center.y / 1920f).coerceIn(0f, 1f)
                        )
                    } ?: TransformOrigin.Center,
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                )
            ) {
                if (selectedMediaIndex != null) {
                    MediaPager(
                        assets = allFlatMedia,
                        initialIndex = selectedMediaIndex!!,
                        onClose = { selectedMediaIndex = null },
                        onDelete = { asset ->
                            viewModel.deleteMediaByIds(listOf(asset.id))
                            val newAllFlat = allFlatMedia.filter { item -> item.id != asset.id }
                            if (newAllFlat.isEmpty()) {
                                selectedMediaIndex = null
                            }
                        },
                        onStartOcr = { uriString ->
                            Log.d("PicMe:UX", "Triggering OCR from Pager")
                            viewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                        },
                        onDismissOcr = {
                            viewModel.clearOcrResult()
                        },
                        ocrState = viewModel.ocrState // 将 ViewModel 的 OCR 状态流传递下去
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    groupingMode: GroupingMode,
    onNavigateBack: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onGroupingModeSelected: (GroupingMode) -> Unit,
    onManageDuplicates: () -> Unit,
    onOpenTestDataTools: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                if (isSelectionMode) {
                    stringResource(R.string.selected_items, selectedCount)
                } else {
                    stringResource(R.string.gallery)
                }
            )
        },
        navigationIcon = {
            IconButton(onClick = {
                if (isSelectionMode) {
                    onToggleSelectionMode()
                } else {
                    onNavigateBack()
                }
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all))
                }
                IconButton(onClick = onShareSelected) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.ocr_share))
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                }
            } else {
                IconButton(onClick = onOpenTestDataTools) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.test_data_tools)
                    )
                }
                IconButton(onClick = onManageDuplicates) {
                    Icon(Icons.Outlined.FilterDrama, contentDescription = "Manage Duplicates")
                }
                GroupingMenu(
                    currentMode = groupingMode,
                    onModeSelected = onGroupingModeSelected
                )
            }
        }
    )
}

@Composable
private fun GroupingMenu(
    currentMode: GroupingMode,
    onModeSelected: (GroupingMode) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            GroupingMode.entries.forEach { mode ->
                val label = when (mode) {
                    NONE -> stringResource(R.string.group_none)
                    DATE -> stringResource(R.string.group_date)
                    FACE -> stringResource(R.string.group_face)
                    PERSON -> stringResource(R.string.group_person)
                    LANDSCAPE -> stringResource(R.string.landscape)
                    SWIMWEAR -> stringResource(R.string.swimwear)
                    SEXY -> stringResource(R.string.sexy)
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(mode)
                        showMenu = false
                    },
                    leadingIcon = {
                        if (currentMode == mode) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyGalleryMessage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.no_media),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateManagerTopBar(
    onNavigateBack: () -> Unit,
    onDeleteAllDuplicates: () -> Unit
) {
    TopAppBar(
        title = { Text(stringResource(R.string.manage_duplicates)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Rounded.Close, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onDeleteAllDuplicates) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete All Duplicates")
            }
        }
    )
}

@Composable
private fun DuplicateManagerScreen(
    duplicateGroups: List<DuplicateGroup>,
    isScanning: Boolean,
    onDeleteGroup: (DuplicateGroup) -> Unit
) {
    if (isScanning) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator()
        }
    } else if (duplicateGroups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_duplicates_found),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.duplicate_groups_found, duplicateGroups.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            items(
                count = duplicateGroups.size,
                key = { index -> index }
            ) { index ->
                val group = duplicateGroups[index]
                DuplicateGroupCard(
                    group = group,
                    onDeleteGroup = onDeleteGroup
                )
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onDeleteGroup: (DuplicateGroup) -> Unit
) {
    var showPreview by remember { mutableStateOf(false) }
    
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (group.isExactDuplicate) {
                        stringResource(R.string.exact_duplicate)
                    } else {
                        stringResource(R.string.similar_image)
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.count_files, group.fileUris.size),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // 显示前 3 张图片的缩略图
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.fileUris.take(3).forEach { uri ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            // 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { showPreview = true }
                ) {
                    Text(stringResource(R.string.preview_all))
                }
                androidx.compose.material3.Button(
                    onClick = { onDeleteGroup(group) },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(stringResource(R.string.keep_first_delete_others))
                }
            }
        }
    }
    
    if (showPreview) {
        DuplicatePreviewDialog(
            fileUris = group.fileUris,
            onDismiss = { showPreview = false },
            onDelete = {
                showPreview = false
                onDeleteGroup(group)
            }
        )
    }
}

@Composable
private fun DuplicatePreviewDialog(
    fileUris: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_preview)) },
        text = {
            Column {
                Text(stringResource(R.string.will_keep_first_file))
                Spacer(modifier = Modifier.padding(4.dp))
                fileUris.forEachIndexed { index, uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (index == 0) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = File(uri).name,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = onDelete) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MediaGrid(
    context: Context,
    groupedMedia: List<GroupedMedia>,
    selectedIds: List<Long>,
    isSelectionMode: Boolean,
    thumbnailPositions: Map<Long, Rect>,
    mediaById: Map<Long, MediaAsset>,
    onThumbnailPositioned: (Long, Rect) -> Unit,
    onMediaClick: (MediaAsset) -> Unit,
    onMediaLongClick: (MediaAsset) -> Unit,
    onDragSelectionStart: (MediaAsset) -> Unit,
    onDragSelectionItem: (MediaAsset) -> Unit,
    onDragSelectionEnd: () -> Unit
) {
    var gridPositionInWindow by remember { mutableStateOf(Offset.Zero) }

    fun resolveDraggedAsset(localPoint: Offset): MediaAsset? {
        val windowPoint = localPoint + gridPositionInWindow
        val hitId = thumbnailPositions.entries.firstOrNull { (_, rect) ->
            windowPoint.x in rect.left..rect.right && windowPoint.y in rect.top..rect.bottom
        }?.key
        return hitId?.let { mediaById[it] }
    }

    LazyVerticalGrid(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                gridPositionInWindow = coordinates.positionInWindow()
            }
            .pointerInput(isSelectionMode, thumbnailPositions.size) {
                if (isSelectionMode) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            resolveDraggedAsset(offset)?.let(onDragSelectionStart)
                        },
                        onDragEnd = onDragSelectionEnd,
                        onDragCancel = onDragSelectionEnd,
                        onDrag = { change, _ ->
                            change.consume()
                            resolveDraggedAsset(change.position)?.let(onDragSelectionItem)
                        }
                    )
                } else {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            resolveDraggedAsset(offset)?.let(onDragSelectionStart)
                        },
                        onDragEnd = onDragSelectionEnd,
                        onDragCancel = onDragSelectionEnd,
                        onDrag = { change, _ ->
                            change.consume()
                            resolveDraggedAsset(change.position)?.let(onDragSelectionItem)
                        }
                    )
                }
            },
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        groupedMedia.forEach { group ->
            val groupTitle = resolveGroupTitle(context, group)
            if (groupTitle.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    MediaGroupHeader(
                        title = groupTitle,
                        count = group.items.size
                    )
                }
            }
            items(group.items, key = { it.id }) { asset ->
                MediaItem(
                    asset = asset,
                    isSelected = selectedIds.contains(asset.id),
                    isSelectionMode = isSelectionMode,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        onThumbnailPositioned(
                            asset.id,
                            Rect(coords.positionInWindow(), coords.size.toSize())
                        )
                    },
                    onClick = { onMediaClick(asset) },
                    onLongClick = { onMediaLongClick(asset) }
                )
            }
        }
    }
}

private fun IntSize.toSize() = Size(width.toFloat(), height.toFloat())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItem(
    asset: MediaAsset,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = modifier
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
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
        }

        if (isSelectionMode) {
            SelectionOverlay(isSelected = isSelected)
        }
    }
}

@Composable
private fun SelectionOverlay(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isSelected) {
                    Color.Black.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                }
            )
            .padding(4.dp)
    ) {
        Icon(
            imageVector = if (isSelected) {
                Icons.Rounded.CheckCircle
            } else {
                Icons.Rounded.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
        )
    }
}
