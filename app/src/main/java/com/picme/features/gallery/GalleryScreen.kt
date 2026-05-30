package com.picme.features.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.picme.core.common.Logger
import com.picme.features.camera.test.CameraTestCommand
import com.picme.features.camera.test.CameraTestCommandDispatcher
import com.picme.features.camera.test.CameraTestCommandReceiver
import com.picme.features.camera.test.CameraTestResult
import com.picme.features.gallery.agent.rememberGalleryAgentIntegration
import com.picme.features.gallery.components.DuplicateManagerScreen
import com.picme.features.gallery.components.DuplicateManagerTopBar
import com.picme.features.gallery.components.EmptyGalleryMessage
import com.picme.features.gallery.components.GalleryPermissionMessage
import com.picme.features.gallery.components.GalleryTopBar
import com.picme.features.gallery.components.MediaGrid
import com.picme.features.gallery.components.MediaPager
import com.picme.features.gallery.components.galleryReadPermissions
import com.picme.features.gallery.components.hasGalleryPermission
import androidx.core.net.toUri
import com.picme.features.gallery.components.shareMediaAssets
import com.picme.features.gallery.agent.GalleryAgentPanel
import com.picme.features.camera.voice.VoiceCommandCoordinator
import com.picme.features.common.chat.rememberAgentChatConfig
import com.picme.domain.agent.model.AgentScene

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

    val allFlatMedia = remember(groupedMedia) { groupedMedia.flatMap { group -> group.items } }
    val mediaById = remember(allFlatMedia) { allFlatMedia.associateBy { it.id } }
    val context = LocalContext.current
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
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                Log.d("PicMe:Gallery", "User granted API 29 delete permission")
                viewModel.executePendingDeletes()
            } else {
                Log.w("PicMe:Gallery", "User denied API 29 delete permission")
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
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                Log.d("PicMe:Gallery", "User granted delete permission")
                viewModel.executePendingDeletes()
            } else {
                Log.w("PicMe:Gallery", "User denied delete permission")
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

    val thumbnailPositions = remember { mutableStateMapOf<Long, Rect>() }
    var dragSelectionTargetSelected by remember { mutableStateOf(true) }
    val dragSelectionVisitedIds = remember { hashSetOf<Long>() }

    val view = LocalView.current

    // ===== Agent Chat 配置（使用公共组件）=====
    val agentChatConfig = rememberAgentChatConfig(
        context = context,
        logTag = "PicMe:Gallery",
        onCommand = { command ->
            Logger.i("PicMe:Gallery", "Voice command: ${command.javaClass.simpleName}")
        },
        onTranscript = { transcript ->
            Logger.d("PicMe:Gallery", "Voice transcript: $transcript")
        }
    )
    val voiceCoordinator = agentChatConfig.voiceCoordinator
    DisposableEffect(Unit) {
        onDispose {
            voiceCoordinator.release()
        }
    }

    // ===== Agent 集成 =====
    val agentIntegration = rememberGalleryAgentIntegration(
        context = context,
        onNavigateTo = { destination ->
            when (destination) {
                "camera" -> onNavigateBack()
                "settings" -> onNavigateToDebug()
            }
        },
        onNavigateBack = onNavigateBack
    )

    agentIntegration.registerCapabilities(
        viewModel = viewModel,
        allMedia = allFlatMedia,
        onViewMedia = { asset ->
            selectedMediaIndex = allFlatMedia.indexOfFirst { it.id == asset.id }
        },
        onDeleteMedia = { assets ->
            viewModel.deleteMediaByIds(assets.map { it.id })
        },
        onShareMedia = { assets ->
            shareMediaAssets(context, assets)
        },
        onSelectMedia = { asset, selected ->
            if (selected) {
                if (!selectedIds.contains(asset.id)) selectedIds.add(asset.id)
            } else {
                selectedIds.remove(asset.id)
            }
        },
        onSearchMedia = { query ->
            Log.d("PicMe:GalleryAgent", "Search query: $query")
        },
        onSwitchViewMode = { mode ->
            Log.d("PicMe:GalleryAgent", "Switch to view mode: $mode")
        },
        onFavoriteMedia = { asset, favorite ->
            Log.d("PicMe:GalleryAgent", "Favorite ${asset.id}: $favorite")
        }
    )

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 动态注册测试命令广播接收器
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == CameraTestCommandReceiver.ACTION_TEST_COMMAND) {
                    Logger.i("PicMe:GalleryTest", "Broadcast received: ${intent.getStringExtra("action")}")
                    CameraTestCommandDispatcher.dispatchFromIntent(intent)
                }
            }
        }
        val filter = IntentFilter(CameraTestCommandReceiver.ACTION_TEST_COMMAND)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Logger.i("PicMe:GalleryTest", "Test command receiver registered dynamically")

        onDispose {
            context.unregisterReceiver(receiver)
            Logger.i("PicMe:GalleryTest", "Test command receiver unregistered")
        }
    }

    // Gallery 测试命令收集器
    LaunchedEffect(Unit) {
        CameraTestCommandDispatcher.commandFlow.collect { command ->
            when (command) {
                is CameraTestCommand.EnterGallery -> {
                    Logger.i("PicMe:GalleryTest", "Already in gallery screen")
                    CameraTestCommandDispatcher.emitResult(
                        CameraTestResult.Success(command, "Already in gallery screen")
                    )
                }
                is CameraTestCommand.OpenPhoto -> {
                    val currentMedia = viewModel.allMedia.value
                    val maxIndex = (currentMedia.size - 1).coerceAtLeast(0)
                    val targetIndex = command.index.coerceIn(0, maxIndex)
                    if (currentMedia.isNotEmpty()) {
                        selectedMediaIndex = targetIndex
                        Logger.i("PicMe:GalleryTest", "OpenPhoto set index to $targetIndex")
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Success(command, "Opened photo at index $targetIndex")
                        )
                    } else {
                        CameraTestCommandDispatcher.emitResult(
                            CameraTestResult.Error(command, "No photos available, media library may still be loading")
                        )
                    }
                }
                else -> {
                    // 其他命令由 MediaPager 处理
                }
            }
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
                    onOpenTestDataTools = onNavigateToDebug
                )
            } else if (showDuplicateManager) {
                DuplicateManagerTopBar(
                    onNavigateBack = { viewModel.toggleDuplicateManager(false) },
                    onDeleteAllDuplicates = {
                        viewModel.deleteAllDuplicatesExceptOne()
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
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
                            Log.d("PicMe:UX", "Triggering OCR from Pager")
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
