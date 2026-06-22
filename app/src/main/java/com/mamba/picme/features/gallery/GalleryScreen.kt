package com.mamba.picme.features.gallery

import android.os.Build
import android.provider.MediaStore

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mamba.picme.core.common.Logger

import com.mamba.picme.features.gallery.agent.rememberGalleryAgentIntegration
import com.mamba.picme.features.gallery.components.DuplicateManagerScreen
import com.mamba.picme.features.gallery.components.DuplicateManagerTopBar
import com.mamba.picme.features.gallery.components.EmptyGalleryMessage
import com.mamba.picme.features.gallery.components.GalleryPermissionMessage
import com.mamba.picme.features.gallery.components.GalleryTopBar
import com.mamba.picme.features.gallery.components.MediaGrid
import com.mamba.picme.features.gallery.components.MediaPager
import com.mamba.picme.features.gallery.components.galleryReadPermissions
import com.mamba.picme.features.gallery.components.hasGalleryPermission
import androidx.core.net.toUri
import com.mamba.picme.features.gallery.components.shareMediaAssets
import com.mamba.picme.features.gallery.agent.GalleryAgentPanel
import com.mamba.picme.features.common.chat.rememberAgentChatConfig
import android.app.Activity
import com.mamba.picme.features.gallery.capability.GalleryCapability
import com.mamba.picme.features.common.SearchField
import kotlinx.coroutines.launch

private const val TAG = "Gallery"
private const val TAG_AGENT = "GalleryAgent"

@Composable
fun GalleryScreen(
    viewModel: MediaViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
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

    // 搜索状态
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchResultMedia by remember { mutableStateOf<List<com.mamba.picme.agent.core.model.context.MediaAsset>>(emptyList()) }
    val searchEngine = remember { GalleryCapability.getInstance().searchEngine }
    val searchScope = rememberCoroutineScope()

    val allFlatMedia by remember { derivedStateOf { groupedMedia.flatMap { group -> group.items } } }
    val mediaById = remember(allFlatMedia) { allFlatMedia.associateBy { it.id } }
    val context = LocalContext.current
    val app = context.applicationContext as com.mamba.picme.PicMeApplication
    val thumbnailPrefetcher = remember { app.container.thumbnailPrefetcher }
    val deleteAuthRequest by viewModel.deleteAuthRequest.collectAsState()

    var hasMediaPermission by remember { mutableStateOf(hasGalleryPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMediaPermission = hasGalleryPermission(context)
    }

    // Android 10 (API 29) 恢复性删除权限请求 launcher
    val api29DeleteLauncher = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted API 29 delete permission")
                viewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied API 29 delete permission")
                viewModel.clearPendingRecoverable()
                viewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }

    // Android 11+ 删除权限请求 launcher
    val deletePermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted delete permission")
                viewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied delete permission")
                viewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }

    LaunchedEffect(deleteAuthRequest) {
        deleteAuthRequest?.let { request ->
            when (request) {
                is MediaViewModel.DeleteAuthRequest.Api29 -> {
                    api29DeleteLauncher?.launch(
                        IntentSenderRequest.Builder(request.intentSender).build()
                    )
                }
                is MediaViewModel.DeleteAuthRequest.Api30 -> {
                    val intent = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        request.uris
                    )
                    deletePermissionLauncher?.launch(
                        IntentSenderRequest.Builder(intent).build()
                    )
                }
            }
            viewModel.consumeDeleteAuthRequest()
        }
    }

    LaunchedEffect(hasMediaPermission) {
        if (hasMediaPermission) {
            viewModel.refreshMediaLibrary()
        }
    }

    // 进入 Gallery 时触发后台图片标签索引 + 人脸聚类
    // Workers 内部有 isActive 守卫，可安全重复调用
    val indexingWorker = remember { app.container.mediaIndexingWorker }
    val faceClusteringWorker = remember { app.container.faceClusteringWorker }
    var isIndexing by remember { mutableStateOf(false) }
    // 人脸聚类状态
    var isReclustering by remember { mutableStateOf(false) }

    // 监听聚类完成
    LaunchedEffect(isReclustering) {
        if (isReclustering) {
            while (faceClusteringWorker.isRunning) {
                kotlinx.coroutines.delay(1000)
            }
            isReclustering = false
            viewModel.refreshMediaLibrary()
        }
    }

    LaunchedEffect(allFlatMedia.size) {
        if (hasMediaPermission && allFlatMedia.isNotEmpty() && !isIndexing) {
            indexingWorker.start()
            isIndexing = true
        }
    }

    // 人脸聚类与索引独立，在媒体数据加载完成后触发
    // 内部自动跳过已完成的媒体，只处理增量
    LaunchedEffect(allFlatMedia.size) {
        if (hasMediaPermission && allFlatMedia.isNotEmpty()) {
            faceClusteringWorker.start()
        }
    }

    // 定期刷新索引状态
    LaunchedEffect(isIndexing) {
        if (isIndexing) {
            while (indexingWorker.isRunning) {
                kotlinx.coroutines.delay(3000)
            }
            isIndexing = false
        }
    }

    val thumbnailPositions = remember { mutableStateMapOf<Long, Rect>() }
    var dragSelectionTargetSelected by remember { mutableStateOf(true) }
    val dragSelectionVisitedIds = remember { hashSetOf<Long>() }

    val view = LocalView.current

    // ===== Agent Chat 配置（使用公共组件）=====
    val agentChatConfig = rememberAgentChatConfig(
        context = context,
        logTag = TAG,
        onCommand = { command ->
            Logger.i(TAG, "Voice command: ${command.javaClass.simpleName}")
        },
        onTranscript = { transcript ->
            Logger.d(TAG, "Voice transcript: $transcript")
        }
    )
    val voiceCoordinator = agentChatConfig.voiceCoordinator
    DisposableEffect(Unit) {
        onDispose {
            // 修复 P0-1：不应该完全释放 voiceCoordinator，因为它在多个 Chat 屏幕间共享
            // 而应该只进行"软释放"（停止监听但保留引擎）
            Logger.i(TAG, "Gallery screen disposed - performing soft release of voice coordinator")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
            // 注意：不调用 voiceCoordinator.release() 以避免破坏 ASR 引擎状态
        }
    }

    // ===== Agent 集成 =====
    val agentIntegration = rememberGalleryAgentIntegration(
        context = context,
        onNavigateTo = { destination ->
            when (destination.lowercase()) {
                "camera" -> onNavigateBack()
                "gallery" -> { /* 已在相册页，无需导航 */ }
                "settings" -> onNavigateToSettings()
                "debug" -> onNavigateToDebug()
                "model_center" -> onNavigateToSettings() // 从相册到模型中心需先进入设置
                "llm_model_manager", "asr_model_manager" -> onNavigateToSettings()
                else -> Logger.w(TAG, "Unknown navigation destination: $destination")
            }
        },
        onNavigateBack = onNavigateBack
    )

    // 绑定 GalleryCapability 的 delegate，确保生命周期绑定
    // 使用 Unit 作为 key，确保只在页面进入/离开时绑定/解绑
    DisposableEffect(Unit) {
        Logger.i(TAG, "Binding GalleryCapability delegate, mediaCount=${allFlatMedia.size}")

        val galleryCapability = GalleryCapability.getInstance()
        galleryCapability.bindDelegate(object : GalleryCapability.Delegate {
            override fun onViewMedia(mediaId: String?) {
                mediaId?.let { id ->
                    val index = allFlatMedia.indexOfFirst { it.id.toString() == id }
                    if (index >= 0) selectedMediaIndex = index
                }
            }
            override fun onDeleteMedia(mediaIds: List<String>) {
                val ids = mediaIds.mapNotNull { it.toLongOrNull() }
                viewModel.deleteMediaByIds(ids)
            }
            override fun onShareMedia(mediaIds: List<String>) {
                val assets = allFlatMedia.filter { it.id.toString() in mediaIds }
                shareMediaAssets(context, assets)
            }
            override fun onSelectMedia(mediaId: String, selected: Boolean) {
                val id = mediaId.toLongOrNull() ?: return
                if (selected) {
                    if (!selectedIds.contains(id)) selectedIds.add(id)
                } else {
                    selectedIds.remove(id)
                }
            }
            override fun onSearch(query: String) {
                Logger.d(TAG_AGENT, "Search query: $query")
            }
            override fun onSwitchViewMode(mode: GalleryCapability.ViewMode) {
                Logger.d(TAG_AGENT, "Switch to view mode: $mode")
            }
            override fun onFavoriteMedia(mediaId: String, favorite: Boolean) {
                Logger.d(TAG_AGENT, "Favorite $mediaId: $favorite")
            }
        })
        Logger.i(TAG, "GalleryCapability delegate bound")

        onDispose {
            Logger.i(TAG, "Unbinding GalleryCapability delegate")
            galleryCapability.unbindDelegate()
        }
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
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

    val currentMedia = selectedMediaIndex?.let { allFlatMedia.getOrNull(it) }
    val selectedItems = selectedIds.mapNotNull { mediaById[it] }
    val pageContext = agentIntegration.buildPageContext(
        currentMedia = currentMedia,
        selectedItems = selectedItems,
        isSelectionMode = isSelectionMode,
        allMedia = allFlatMedia
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            when {
                isSearchActive -> {
                    // 搜索模式：显示搜索框
                    com.mamba.picme.features.gallery.components.SearchTopBar(
                        searchQuery = searchQuery,
                        onQueryChange = { query ->
                            searchQuery = query
                            if (query.isNotBlank()) {
                                searchScope.launch {
                                    val engine = searchEngine ?: return@launch
                                    val result = engine.search(query)
                                    searchResultMedia = result.media
                                }
                            } else {
                                searchResultMedia = emptyList()
                            }
                        },
                        onClose = {
                            searchQuery = ""
                            searchResultMedia = emptyList()
                            isSearchActive = false
                        },
                        resultCount = if (searchQuery.isNotBlank()) searchResultMedia.size else null
                    )
                }
                selectedMediaIndex == null && !showDuplicateManager -> {
                    GalleryTopBar(
                        isSelectionMode = isSelectionMode,
                        selectedCount = selectedIds.size,
                        groupingMode = groupingMode,
                        isReclustering = isReclustering,
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
                            val idsToDelete = selectedIds.toList()
                            viewModel.deleteMediaByIds(idsToDelete)
                            isSelectionMode = false
                            selectedIds.clear()
                        },
                        onShareSelected = {
                            val selectedAssets = selectedIds.mapNotNull { mediaById[it] }
                            shareMediaAssets(context, selectedAssets)
                        },
                        onGroupingModeSelected = { mode -> viewModel.setGroupingMode(mode) },
                        onManageDuplicates = { viewModel.toggleDuplicateManager(true) },
                        onOpenTestDataTools = onNavigateToDebug,
                        onSearchClick = {
                            isSearchActive = true
                            searchResultMedia = emptyList()
                        },
                        onRecluster = {
                            isReclustering = true
                            faceClusteringWorker.forceRecluster()
                        }
                    )
                }
                showDuplicateManager -> {
                    DuplicateManagerTopBar(
                        onNavigateBack = { viewModel.toggleDuplicateManager(false) },
                        onDeleteAllDuplicates = {
                            viewModel.deleteAllDuplicatesExceptOne()
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 索引状态指示器
            if (isIndexing) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when {
                isSearchActive && searchQuery.isNotBlank() -> {
                    if (searchResultMedia.isEmpty()) {
                        EmptyGalleryMessage(message = "未找到匹配 \"$searchQuery\" 的照片")
                    } else {
                        val searchGroup = com.mamba.picme.domain.model.GroupedMedia(
                            titleType = com.mamba.picme.domain.model.GroupTitleType.SEARCH,
                            titleValue = "\"$searchQuery\"",
                            items = searchResultMedia
                        )
                        MediaGrid(
                            context = context,
                            groupedMedia = listOf(searchGroup),
                            selectedIds = selectedIds,
                            isSelectionMode = false,
                            thumbnailPositions = thumbnailPositions,
                            mediaById = searchResultMedia.associateBy { it.id },
                            onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                            onMediaClick = { asset ->
                                val index = searchResultMedia.indexOfFirst { it.id == asset.id }
                                if (index >= 0) selectedMediaIndex = index
                            },
                            onMediaLongClick = { },
                            onDragSelectionStart = { },
                            onDragSelectionItem = { },
                            onDragSelectionEnd = { }
                        )
                    }
                }

                showDuplicateManager -> {
                    DuplicateManagerScreen(
                        duplicateGroups = duplicateGroups,
                        isScanning = isScanningDuplicates,
                        onDeleteGroup = { group ->
                            viewModel.deleteDuplicateGroup(group, 0)
                        }
                    )
                }

                !hasMediaPermission -> {
                    GalleryPermissionMessage(
                        onGrantPermission = {
                            permissionLauncher.launch(galleryReadPermissions())
                        }
                    )
                }

                allFlatMedia.isEmpty() -> {
                    EmptyGalleryMessage()
                }

                else -> {
                    MediaGrid(
                        context = context,
                        groupedMedia = groupedMedia,
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        thumbnailPositions = thumbnailPositions,
                        mediaById = mediaById,
                        thumbnailPrefetcher = thumbnailPrefetcher,
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
            }

            val activeMedia = selectedMediaIndex?.let { allFlatMedia.getOrNull(it) }
            val rect = activeMedia?.let { thumbnailPositions[it.id] }

            // Agent Chat 入口 - 右下角浮动按钮
            if (selectedMediaIndex == null && !showDuplicateManager) {
                GalleryAgentPanel(
                    integration = agentIntegration,
                    pageContext = pageContext,
                    voiceCoordinator = voiceCoordinator,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

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
                            Logger.d(TAG, "Triggering OCR from Pager")
                            viewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                        },
                        onDismissOcr = {
                            viewModel.clearOcrResult()
                        },
                        ocrState = viewModel.ocrState,
                        photoEditState = viewModel.photoEditState,
                        onPrepareEdit = { bitmap ->
                            viewModel.preparePhotoEdit(bitmap)
                        },
                        onProcessPhoto = { bitmap, settings ->
                            viewModel.processPhoto(bitmap, settings)
                        },
                        onSavePhoto = { bitmap ->
                            viewModel.saveProcessedPhoto(context, bitmap)
                        },
                        onClearEditState = {
                            viewModel.clearPhotoEditState()
                        },
                        voiceCoordinator = voiceCoordinator
                    )
                }
            }
        }
    }
}
