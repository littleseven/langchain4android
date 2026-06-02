package com.picme.features.gallery.components

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.picme.R
import com.picme.beauty.api.BeautySettings
import com.picme.core.common.Logger
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.features.camera.components.BeautySelector
import com.picme.features.camera.test.CameraTestCommand
import com.picme.features.camera.test.CameraTestCommandConverters
import com.picme.features.camera.test.CameraTestCommandDispatcher
import com.picme.features.camera.test.CameraTestResult
import com.picme.features.gallery.MediaViewModel
import com.picme.features.common.chat.AgentMessage
import com.picme.features.common.chat.AiChatScreen
import com.picme.features.camera.voice.VoiceCommandCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun MediaPager(
    assets: List<MediaAsset>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (MediaAsset) -> Unit,
    onStartOcr: (String) -> Unit,
    onDismissOcr: () -> Unit,
    ocrState: StateFlow<MediaViewModel.OcrResult?>,
    photoEditState: StateFlow<MediaViewModel.PhotoEditState>,
    onPrepareEdit: (Bitmap) -> Unit,
    onProcessPhoto: (Bitmap, BeautySettings) -> Unit,
    onSavePhoto: (Bitmap) -> Unit,
    onClearEditState: () -> Unit,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { assets.size })
    var showInfo by remember { mutableStateOf(false) }
    var showLandmarkOverlay by remember { mutableStateOf(false) }
    var show468Points by remember { mutableStateOf(false) }
    var showBigBeauty106 by remember { mutableStateOf(false) }
    var currentPageZoomed by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editSettings by remember { mutableStateOf(BeautySettings()) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showAiChatPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentAsset = assets.getOrNull(pagerState.currentPage)
    val editState by photoEditState.collectAsState()

    // 启动照片编辑（长按或工具栏按钮共用）
    val startPhotoEdit = {
        val asset = currentAsset
        if (asset != null && asset.type == MediaType.PHOTO) {
            Log.d("PicMe:UX", "Start photo editing mode")
            isEditing = true
            editSettings = BeautySettings(enabled = true)
            processedBitmap = null
            loadedBitmap = null
            onClearEditState()

            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(asset.uri.toUri())?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    if (bitmap != null) {
                        loadedBitmap = bitmap
                        withContext(Dispatchers.Main) {
                            onPrepareEdit(bitmap)
                        }
                    } else {
                        Logger.e("PicMe:Gallery", "Failed to decode bitmap for editing")
                    }
                } catch (e: java.io.IOException) {
                    Logger.e("PicMe:Gallery", "IO error when loading bitmap: ${e.message}")
                } catch (e: OutOfMemoryError) {
                    Logger.e("PicMe:Gallery", "OOM when loading bitmap: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    Logger.e("PicMe:Gallery", "Invalid image data: ${e.message}")
                }
            }
        }
    }
    val landmarkState = rememberFaceLandmarkDetection(
        imageUri = currentAsset?.uri.orEmpty(),
        enabled = showLandmarkOverlay && currentAsset?.type == MediaType.PHOTO && !isEditing
    )

    LaunchedEffect(pagerState.currentPage) {
        currentPageZoomed = false
        if (currentAsset?.type != MediaType.PHOTO) {
            showLandmarkOverlay = false
        }
    }

    LaunchedEffect(editState) {
        when (val state = editState) {
            is MediaViewModel.PhotoEditState.Ready -> {
                processedBitmap = state.bitmap
            }
            is MediaViewModel.PhotoEditState.Idle -> {
                processedBitmap = null
                loadedBitmap = null
            }
            else -> {}
        }
    }

    // 实时预览：editSettings 变化后 debounce 200ms 自动触发处理
    LaunchedEffect(isEditing) {
        if (!isEditing) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { editSettings }
            .drop(1) // 跳过初始值，避免进入编辑模式时自动触发
            .debounce(200)
            .filter { it.enabled && it.hasAnyEffect() }
            .collect { settings ->
                loadedBitmap?.let { bitmap ->
                    onProcessPhoto(bitmap, settings)
                }
            }
    }

    // Gallery 测试命令收集器：接收 adb 广播命令并执行对应操作
    LaunchedEffect(Unit) {
        CameraTestCommandDispatcher.commandFlow.collect { command ->
            when (command) {
                is CameraTestCommand.EnterGallery -> {
                    Logger.i("PicMe:GalleryTest", "Already in gallery mode")
                    CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Success(command, "Already in gallery mode")
                    )
                }
                is CameraTestCommand.OpenPhoto -> {
                    val targetIndex = command.index.coerceIn(0, assets.size - 1)
                    pagerState.scrollToPage(targetIndex)
                    Logger.i("PicMe:GalleryTest", "OpenPhoto scrolled to index $targetIndex")
                    CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Success(command, "Scrolled to photo index $targetIndex")
                    )
                }
                is CameraTestCommand.LongPressPhoto,
                is CameraTestCommand.StartEdit -> {
                    if (!isEditing && currentAsset?.type == MediaType.PHOTO) {
                        startPhotoEdit()
                        Logger.i("PicMe:GalleryTest", "Photo edit started")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Photo edit started")
                        )
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "Cannot start edit: already editing or not a photo")
                        )
                    }
                }
                is CameraTestCommand.SaveEdit -> {
                    if (isEditing && processedBitmap != null) {
                        processedBitmap?.let { bitmap ->
                            onSavePhoto(bitmap)
                            isEditing = false
                            editSettings = BeautySettings()
                            processedBitmap = null
                            onClearEditState()
                            Logger.i("PicMe:GalleryTest", "Edit saved")
                            CameraTestCommandDispatcher.emitResult(
                                CameraTestResult.Success(command, "Edit saved")
                            )
                        }
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "Cannot save: not in edit mode or no processed bitmap")
                        )
                    }
                }
                is CameraTestCommand.CancelEdit -> {
                    if (isEditing) {
                        isEditing = false
                        editSettings = BeautySettings()
                        processedBitmap = null
                        onClearEditState()
                        Logger.i("PicMe:GalleryTest", "Edit cancelled")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Edit cancelled")
                        )
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "Cannot cancel: not in edit mode")
                        )
                    }
                }
                is CameraTestCommand.SetSmooth -> {
                    if (isEditing) {
                        editSettings = editSettings.copy(smoothing = command.value / 100f)
                        Logger.i("PicMe:GalleryTest", "Smooth set to ${command.value}")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Smooth set to ${command.value}")
                        )
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "Cannot set smooth: not in edit mode")
                        )
                    }
                }
                is CameraTestCommand.SetWhiten -> {
                    if (isEditing) {
                        editSettings = editSettings.copy(whitening = command.value / 100f)
                        Logger.i("PicMe:GalleryTest", "Whiten set to ${command.value}")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Whiten set to ${command.value}")
                        )
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "Cannot set whiten: not in edit mode")
                        )
                    }
                }
                is CameraTestCommand.SetEditFilter -> {
                    if (isEditing) {
                        val filter = CameraTestCommandConverters.parseFilterType(command.filter)
                        editSettings = editSettings.copy(colorFilter = filter)
                        Logger.i("PicMe:GalleryTest", "Filter set to ${command.filter}")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Filter set to ${command.filter}")
                        )
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "Cannot set filter: not in edit mode")
                        )
                    }
                }
                is CameraTestCommand.StartOcr -> {
                    currentAsset?.let { asset ->
                        onStartOcr(asset.uri)
                        Logger.i("PicMe:GalleryTest", "OCR started for ${asset.id}")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "OCR started")
                        )
                    } ?: CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Error(command, "No current asset for OCR")
                    )
                }
                is CameraTestCommand.DismissOcr -> {
                    onDismissOcr()
                    Logger.i("PicMe:GalleryTest", "OCR dismissed")
                    CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Success(command, "OCR dismissed")
                    )
                }
                is CameraTestCommand.ToggleLandmark -> {
                    showLandmarkOverlay = !showLandmarkOverlay
                    Logger.i("PicMe:GalleryTest", "Landmark overlay toggled to $showLandmarkOverlay")
                    CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Success(command, "Landmark overlay: $showLandmarkOverlay")
                    )
                }
                is CameraTestCommand.ToggleInfo -> {
                    showInfo = !showInfo
                    Logger.i("PicMe:GalleryTest", "Info toggled to $showInfo")
                    CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Success(command, "Info visibility: $showInfo")
                    )
                }
                is CameraTestCommand.DeletePhoto -> {
                    currentAsset?.let { asset ->
                        onDelete(asset)
                        Logger.i("PicMe:GalleryTest", "Delete requested for ${asset.id}")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Delete requested")
                        )
                    } ?: CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Error(command, "No current asset to delete")
                    )
                }
                is CameraTestCommand.SharePhoto -> {
                    currentAsset?.let { asset ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, asset.uri.toUri())
                            type = if (asset.type == MediaType.VIDEO) "video/*" else "image/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                        Logger.i("PicMe:GalleryTest", "Share requested for ${asset.id}")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Share requested")
                        )
                    } ?: CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Error(command, "No current asset to share")
                    )
                }
                else -> {
                    // Camera 相关命令在 Gallery 中不支持，静默忽略
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            userScrollEnabled = !currentPageZoomed && !isEditing
        ) { pageIndex ->
            val asset = assets[pageIndex]
            if (asset.type == MediaType.VIDEO) {
                VideoPlayer(uri = asset.uri)
            } else {
                val showProcessed = pageIndex == pagerState.currentPage && processedBitmap != null && isEditing
                if (showProcessed) {
                    Image(
                        bitmap = processedBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    ZoomableImage(
                        uri = asset.uri,
                        onClick = {
                            if (!isEditing) {
                                Log.d("PicMe:UX", "Toggle info visibility via click")
                                showInfo = !showInfo
                            }
                        },
                        onLongClick = {
                            if (!isEditing) {
                                Log.d("PicMe:UX", "Trigger photo edit via long press")
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                startPhotoEdit()
                            }
                        },
                        onZoomStateChanged = { scale ->
                            if (pageIndex == pagerState.currentPage) {
                                currentPageZoomed = scale > 1.02f
                            }
                        }
                    )
                }
            }
        }

        // Top Controls
        mediaPagerTopControls(
            onClose = {
                if (isEditing) {
                    isEditing = false
                    editSettings = BeautySettings()
                    processedBitmap = null
                    onClearEditState()
                } else if (showAiChatPanel) {
                    showAiChatPanel = false
                } else {
                    onClose()
                }
            },
            showInfo = showInfo && !isEditing,
            showLandmarkAction = currentAsset?.type == MediaType.PHOTO && !isEditing,
            showLandmarkOverlay = showLandmarkOverlay,
            isEditing = isEditing,
            onToggleInfo = {
                Log.d("PicMe:UX", "Toggle info visibility via button")
                showInfo = !showInfo
            },
            onToggleLandmarks = {
                showLandmarkOverlay = !showLandmarkOverlay
            },
            onStartEdit = startPhotoEdit,
            onDelete = {
                val selectedAsset = assets[pagerState.currentPage]
                Log.d("PicMe:UX", "Request delete media: ${selectedAsset.id}")
                onDelete(selectedAsset)
            },
            onShare = {
                val selectedAsset = assets[pagerState.currentPage]
                Log.d("PicMe:UX", "Share media from pager: ${selectedAsset.id}")
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, selectedAsset.uri.toUri())
                    type = if (selectedAsset.type == MediaType.VIDEO) "video/*" else "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, null))
            },
            onStartOcr = {
                val selectedAsset = assets[pagerState.currentPage]
                Log.d("PicMe:UX", "Trigger OCR via toolbar button for asset: ${selectedAsset.id}")
                onStartOcr(selectedAsset.uri)
            }
        )

        if (showLandmarkOverlay && currentAsset?.type == MediaType.PHOTO && !isEditing) {
            FaceLandmarkCanvasOverlay(
                state = landmarkState,
                show468Points = show468Points,
                showBigBeauty106 = showBigBeauty106
            )
            FaceLandmarkControlBar(
                state = landmarkState,
                show468Points = show468Points,
                showBigBeauty106 = showBigBeauty106,
                onToggle468Points = { show468Points = !show468Points },
                onToggleBigBeauty106 = { showBigBeauty106 = !showBigBeauty106 },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Source Info Overlay (Bottom Left)
        SourceInfoOverlay(
            visible = showInfo && currentAsset?.source != null && !showLandmarkOverlay && !isEditing,
            source = currentAsset?.source
        )

        // OCR Result Overlay
        OcrResultOverlay(
            ocrState = ocrState,
            onDismiss = {
                Log.d("PicMe:UX", "Dismiss OCR result overlay")
                onDismissOcr()
            }
        )

        // Photo Edit Panel
        if (isEditing && currentAsset?.type == MediaType.PHOTO) {
            photoEditPanel(
                editState = editState,
                settings = editSettings,
                onSettingsChanged = { editSettings = it },
                onSave = {
                    processedBitmap?.let { bitmap ->
                        onSavePhoto(bitmap)
                        isEditing = false
                        editSettings = BeautySettings()
                        processedBitmap = null
                        loadedBitmap = null
                        onClearEditState()
                    }
                },
                onCancel = {
                    isEditing = false
                    editSettings = BeautySettings()
                    processedBitmap = null
                    loadedBitmap = null
                    onClearEditState()
                }
            )
        }

        // AI Chat Panel - 右下角浮动按钮入口
        if (!isEditing && currentAsset?.type == MediaType.PHOTO) {
            if (!showAiChatPanel) {
                FloatingActionButton(
                    onClick = { showAiChatPanel = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                        .navigationBarsPadding(),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardVoice,
                        contentDescription = "AI Agent",
                        tint = Color.White
                    )
                }
            }

            val pagerMessages = remember { mutableStateOf<List<AgentMessage>>(emptyList()) }
            var pagerIsProcessing by remember { mutableStateOf(false) }

            AiChatScreen(
                visible = showAiChatPanel,
                messages = pagerMessages.value,
                isProcessing = pagerIsProcessing,
                onVisibleChange = { showAiChatPanel = it },
                voiceCoordinator = voiceCoordinator,
                onSendMessage = { input ->
                    pagerMessages.value = pagerMessages.value + AgentMessage.UserText(content = input)
                    pagerIsProcessing = true
                    // TODO: 集成图片编辑相关的 Agent 处理
                    scope.launch {
                        kotlinx.coroutines.delay(500)
                        pagerIsProcessing = false
                        pagerMessages.value = pagerMessages.value + AgentMessage.AgentText(
                            content = "我收到了您的指令：$input。图片编辑功能正在开发中..."
                        )
                    }
                },
                onCommand = { /* TODO: 处理图片编辑命令 */ }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    uri: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onZoomStateChanged: (Float) -> Unit
) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(uri) { mutableStateOf(IntSize.Zero) }

    fun clampOffset(nextOffset: Offset, nextScale: Float): Offset {
        if (nextScale <= 1f || containerSize.width == 0 || containerSize.height == 0) {
            return Offset.Zero
        }
        val maxX = (containerSize.width * (nextScale - 1f)) / 2f
        val maxY = (containerSize.height * (nextScale - 1f)) / 2f
        return Offset(
            x = nextOffset.x.coerceIn(-maxX, maxX),
            y = nextOffset.y.coerceIn(-maxY, maxY)
        )
    }

    SideEffect {
        onZoomStateChanged(scale)
    }

    val isZoomed = scale > 1.02f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerSize = size
                offset = clampOffset(offset, scale)
            }
            .then(
                if (isZoomed) {
                    Modifier.pointerInput(uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            val nextOffset = clampOffset(offset + pan, nextScale)
                            scale = nextScale
                            offset = if (abs(nextScale - 1f) < 0.01f) {
                                Offset.Zero
                            } else {
                                nextOffset
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun OcrResultOverlay(
    ocrState: StateFlow<MediaViewModel.OcrResult?>,
    onDismiss: () -> Unit
) {
    val result by ocrState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    AnimatedVisibility(
        visible = result != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 500.dp)
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* Stop propagation */ }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val ocrResult = result) {
                        null -> {}
                        MediaViewModel.OcrResult.Loading -> {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.ocr_progress),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        is MediaViewModel.OcrResult.Success -> {
                            Log.d("PicMe:UX", "OCR Result Displayed")
                            // Header with title, close button and actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.ocr_recognize),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${ocrResult.text.length} 字",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        maxLines = 1
                                    )
                                    IconButton(
                                        onClick = { onDismiss() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.close),
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Divider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )

                            val scrollState = rememberScrollState()
                            // Scrollable text content with constrained height
                            Text(
                                text = ocrResult.text,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 4.dp),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            // Action buttons at bottom
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        Log.d("PicMe:UX", "OCR Copy text action")
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        clipboardManager.setText(AnnotatedString(ocrResult.text))
                                        Toast.makeText(context, context.getString(R.string.ocr_copy_success), Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.ocr_copy),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Button(
                                    onClick = {
                                        Log.d("PicMe:UX", "OCR Share text action")
                                        val sendIntent: Intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, ocrResult.text)
                                            type = "text/plain"
                                        }
                                        val shareIntent = Intent.createChooser(sendIntent, null)
                                        context.startActivity(shareIntent)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.ocr_share),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        is MediaViewModel.OcrResult.Error -> {
                            Log.e("PicMe:UX", "OCR Result Error: ${ocrResult.message}")
                            Icon(
                                imageVector = Icons.Rounded.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = ocrResult.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun mediaPagerTopControls(
    onClose: () -> Unit,
    showInfo: Boolean,
    showLandmarkAction: Boolean,
    showLandmarkOverlay: Boolean,
    isEditing: Boolean,
    onToggleInfo: () -> Unit,
    onToggleLandmarks: () -> Unit,
    onStartEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
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
            onClick = {
                Log.d("PicMe:UX", if (isEditing) "Cancel editing mode" else "Close MediaPager")
                onClose()
            },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            )
        ) {
            Icon(
                imageVector = if (isEditing) Icons.Rounded.Close else Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                tint = Color.White
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showLandmarkAction) {
                IconButton(
                    onClick = onToggleLandmarks,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showLandmarkOverlay) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        } else {
                            Color.Black.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Icon(
                        Icons.Rounded.Face,
                        contentDescription = stringResource(R.string.landmark_overlay),
                        tint = if (showLandmarkOverlay) Color.Black else Color.White
                    )
                }
            }

            if (!isEditing) {
                IconButton(
                    onClick = onStartEdit,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        Icons.Rounded.AutoFixHigh,
                        contentDescription = stringResource(R.string.edit),
                        tint = Color.White
                    )
                }
            }

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

            IconButton(
                onClick = { onToggleInfo() },
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
                onClick = { onShare() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.ocr_share),
                    tint = Color.White
                )
            }

            IconButton(
                onClick = { onDelete() },
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
                    text = stringResource(R.string.media_source, source?.uppercase(Locale.getDefault()) ?: ""),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun photoEditPanel(
    editState: MediaViewModel.PhotoEditState,
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (editState is MediaViewModel.PhotoEditState.Analyzing) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = stringResource(R.string.analyzing_face),
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                BeautySelector(
                    settings = settings,
                    onSettingsChanged = onSettingsChanged
                )

                Spacer(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isProcessing = editState is MediaViewModel.PhotoEditState.Processing

                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = onSave,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.save),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
