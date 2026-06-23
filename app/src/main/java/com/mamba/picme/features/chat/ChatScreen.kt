package com.mamba.picme.features.chat

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import android.provider.MediaStore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.util.Locale
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import androidx.compose.material.icons.rounded.Menu
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import com.mamba.picme.features.chat.ChatThreadSidebar
import com.mamba.picme.features.chat.components.QuickActionBar
import dev.jeziellago.compose.markdowntext.MarkdownText

private const val TAG = "ChatScreen"

/**
 * Chat 首页 — AI 对话核心入口
 *
 * 布局：
 * - 顶部栏：Logo + 设置 + 清空
 * - 消息列表：LazyColumn 展示对话历史
 * - 输入区：ModelSelector + 输入框 + 发送按钮
 * - 快捷入口：相机 / 相册 / 编辑
 */
@Suppress("LongMethod") // Top-level Compose screen: scaffold + list + input + sidebar
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onNavigateToCamera: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToModelCenter: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val messages by viewModel.displayMessages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val threads by viewModel.filteredThreads.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showQuickActions by remember { mutableStateOf(false) }
    var isSidebarOpen by remember { mutableStateOf(false) }
    // 图片预览状态
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }

    BackHandler(enabled = isSidebarOpen) {
        isSidebarOpen = false
    }

    // 自动滚动到底部：列表条数变化或最后一条内容变化时触发（支持流式打字效果）
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 沉浸式模式：隐藏系统栏
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                onOpenSidebar = { isSidebarOpen = true },
                onNavigateToSettings = onNavigateToSettings,
                onClearChat = { viewModel.clearChat() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 消息列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            onImageClick = { imageUri -> previewImageUri = imageUri }
                        )
                    }
                }

                // 输入区
                ChatInputArea(
                    currentModel = currentModel,
                    isProcessing = isProcessing,
                    onModelSwitch = { viewModel.switchModel(it) },
                    onSendMessage = { text ->
                        viewModel.sendMessage(text)
                    },
                    onImagePicked = { uri ->
                        viewModel.sendImageMessage(uri)
                    },
                    onToggleQuickActions = { showQuickActions = !showQuickActions }
                )
            }

            // 快捷入口 FAB — 右下角浮动
            FloatingActionButton(
                onClick = { showQuickActions = !showQuickActions },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp)
                    .navigationBarsPadding()
                    .size(52.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Quick Actions",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 快捷入口展开面板
            if (showQuickActions) {
                QuickActionPanel(
                    onCameraClick = {
                        showQuickActions = false
                        onNavigateToCamera()
                    },
                    onGalleryClick = {
                        showQuickActions = false
                        onNavigateToGallery()
                    },
                    onModelDownloadClick = {
                        showQuickActions = false
                        onNavigateToModelCenter()
                    },
                    onDismiss = { showQuickActions = false },
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            // 侧边栏
            ChatThreadSidebar(
                visible = isSidebarOpen,
                threads = threads,
                currentSessionId = currentSessionId,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onThreadSelected = { sessionId ->
                    viewModel.switchSession(sessionId)
                    isSidebarOpen = false
                },
                onNewChat = {
                    viewModel.newSession()
                    isSidebarOpen = false
                },
                onRename = { sessionId, newTitle ->
                    viewModel.renameSession(sessionId, newTitle)
                },
                onDelete = { sessionId ->
                    viewModel.deleteSession(sessionId)
                },
                onDismiss = { isSidebarOpen = false }
            )

            // 图片全屏预览
            ImagePreviewOverlay(
                imageUri = previewImageUri,
                onDismiss = { previewImageUri = null }
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    onOpenSidebar: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClearChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onOpenSidebar) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open sidebar",
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = "PicMe",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClearChat) {
                Text(
                    text = stringResource(R.string.clear_chat),
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageItem(message: ChatMessageUi, onImageClick: (Uri) -> Unit = {}) {
    val isUser = message.type == ChatMessageType.USER_TEXT || message.type == ChatMessageType.USER_IMAGE
    val isImage = message.type == ChatMessageType.AGENT_IMAGE || message.type == ChatMessageType.USER_IMAGE
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                    }
                )
                .padding(
                    horizontal = if (isImage) 5.dp else 14.dp,
                    vertical = if (isImage) 3.dp else 10.dp
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
        ) {
            when {
                isImage -> {
                    // 显示图片（可点击进入全屏预览）
                    // 高度固定 200dp，宽度按原始比例自适应，不超 260dp
                    AsyncImage(
                        model = message.content,
                        contentDescription = "图片",
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier
                            .height(200.dp)
                            .widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val uri = Uri.parse(message.content)
                                val resolvedUri = if (uri.scheme != null) uri
                                    else java.io.File(message.content).toUri()
                                onImageClick(resolvedUri)
                            }
                    )
                }
                isUser -> {
                    Text(
                        text = message.content,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                else -> {
                    MarkdownText(
                        markdown = message.content,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
            if (message.modelUsed != null) {
                Text(
                    text = message.modelUsed,
                    color = if (isUser) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            message.performance?.let { perf ->
                val metricTint = if (isUser) {
                    Color.White.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                }
                FlowRow(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    PerformanceMetric(
                        icon = Icons.AutoMirrored.Rounded.ShortText,
                        value = "${perf.promptLen}",
                        tint = metricTint
                    )
                    PerformanceMetric(
                        icon = Icons.Rounded.ChatBubble,
                        value = "${perf.decodeLen}",
                        tint = metricTint
                    )
                    PerformanceMetric(
                        icon = Icons.Rounded.Bolt,
                        value = "${perf.prefillTimeMs}ms",
                        tint = metricTint
                    )
                    PerformanceMetric(
                        icon = Icons.Rounded.Timer,
                        value = "${perf.decodeTimeMs}ms",
                        tint = metricTint
                    )
                    PerformanceMetric(
                        icon = Icons.Rounded.Speed,
                        value = "${String.format(Locale.ROOT, "%.1f", perf.decodeSpeed)}",
                        tint = metricTint
                    )
                }
            }
        }
    }
}

/**
 * 单个性能指标：图标 + 数值，紧凑展示，避免一行文字过长。
 */
@Composable
private fun PerformanceMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(10.dp)
        )
        Text(
            text = value,
            color = tint,
            fontSize = 9.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputArea(
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onModelSwitch: (ChatModelOption) -> Unit,
    onSendMessage: (String) -> Unit,
    onImagePicked: (Uri) -> Unit = {},
    onToggleQuickActions: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 模型切换图标（点击展开下拉菜单）
            Box {
                IconButton(
                    onClick = { showModelMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(currentModel.indicatorColor)
                    )
                }
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(ChatModelOption.Local.indicatorColor)
                                )
                                Text("本地模型 (Qwen3.5-2B)")
                            }
                        },
                        onClick = {
                            onModelSwitch(ChatModelOption.Local)
                            showModelMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(ChatModelOption.Remote.indicatorColor)
                                )
                                Text("远程模型 (DeepSeek)")
                            }
                        },
                        onClick = {
                            onModelSwitch(ChatModelOption.Remote)
                            showModelMenu = false
                        }
                    )
                }
            }

            // 输入框
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        stringResource(R.string.chat_input_hint),
                        fontSize = 14.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp),
                minLines = 1,
                maxLines = 4,
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )

            // 图片选择 + 发送按钮组（间距 2dp）
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图片选择按钮（打开内置相册选取）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(enabled = !isProcessing) { showPhotoPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "选择图片",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 发送按钮
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotBlank() && !isProcessing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Gray.copy(alpha = 0.3f)
                            }
                        )
                        .clickable(enabled = text.isNotBlank() && !isProcessing) {
                            if (text.isNotBlank() && !isProcessing) {
                                onSendMessage(text.trim())
                                text = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    // 内置相册选取底部弹窗
    if (showPhotoPicker) {
        InAppPhotoPicker(
            sheetState = sheetState,
            context = context,
            onImageSelected = { uri ->
                onImagePicked(uri)
                showPhotoPicker = false
            },
            onDismiss = { showPhotoPicker = false }
        )
    }
}

/**
 * 快捷入口展开面板 — 悬浮在右下角
 */
@Composable
private fun QuickActionPanel(
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onModelDownloadClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(end = 16.dp, bottom = 140.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            QuickActionFabItem(
                label = "相机",
                icon = Icons.Rounded.CameraAlt,
                onClick = onCameraClick
            )
            QuickActionFabItem(
                label = "相册",
                icon = Icons.Rounded.PhotoLibrary,
                onClick = onGalleryClick
            )
            QuickActionFabItem(
                label = "模型下载",
                icon = Icons.Rounded.Download,
                onClick = onModelDownloadClick
            )
        }
    }
}

@Composable
private fun QuickActionFabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 本地 LLM 性能指标（展示用）
 */
data class LlmPerformance(
    val promptLen: Long,
    val decodeLen: Long,
    val prefillTimeMs: Long,
    val decodeTimeMs: Long,
    val prefillSpeed: Float,
    val decodeSpeed: Float
)

/**
 * 聊天消息 UI 数据类
 */
data class ChatMessageUi(
    val id: String,
    val type: ChatMessageType,
    val content: String,
    val modelUsed: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val performance: LlmPerformance? = null
)

enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
    AGENT_IMAGE,
    COMMAND,
    PLAN_PREVIEW
}

/**
 * 模型选项
 */
sealed class ChatModelOption(val label: String, val indicatorColor: Color) {
    data object Local : ChatModelOption("本地", Color(0xFF4CAF50))
    data object Remote : ChatModelOption("远程", Color(0xFF2196F3))
}

/**
 * 图片全屏预览浮层
 */
@Composable
private fun ImagePreviewOverlay(
    imageUri: Uri?,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = imageUri != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = "图片预览",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentScale = ContentScale.Fit
            )

            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 内置相册选取器 — 底部弹出网格，从 MediaStore 加载最近照片
 *
 * 替代系统图片选择器，保持应用内一致的视觉体验。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InAppPhotoPicker(
    sheetState: androidx.compose.material3.SheetState,
    context: android.content.Context,
    onImageSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    // 加载最近照片
    val photos = remember {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        val uris = mutableListOf<Uri>()
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                uris.add(Uri.withAppendedPath(uri, id.toString()))
            }
        }
        uris
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = "选择图片",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(photos) { photoUri ->
                Card(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onImageSelected(photoUri) },
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photoUri)
                            .size(256)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
