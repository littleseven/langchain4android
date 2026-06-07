package com.picme.features.gallery.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.picme.domain.model.GroupedMedia
import com.picme.agent.core.model.MediaAsset
import com.picme.agent.core.model.MediaType
import androidx.compose.foundation.layout.PaddingValues

@Composable
fun MediaGrid(
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
            items(group.items, key = { item -> item.id }) { asset ->
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
