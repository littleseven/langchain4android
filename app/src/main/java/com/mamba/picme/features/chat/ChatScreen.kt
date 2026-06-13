package com.mamba.picme.features.chat

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import androidx.compose.material.icons.rounded.Menu
import androidx.activity.compose.BackHandler
import com.mamba.picme.features.chat.ChatThreadSidebar
import com.mamba.picme.features.chat.components.ModelSelector
import com.mamba.picme.features.chat.components.QuickActionBar

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
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onNavigateToCamera: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToEditor: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val messages by viewModel.displayMessages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val threads by viewModel.threads.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showQuickActions by remember { mutableStateOf(false) }
    var isSidebarOpen by remember { mutableStateOf(false) }

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
                        ChatMessageItem(message = message)
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
                    onEditorClick = {
                        showQuickActions = false
                        onNavigateToEditor()
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
                onThreadSelected = { sessionId ->
                    viewModel.switchSession(sessionId)
                    isSidebarOpen = false
                },
                onDismiss = { isSidebarOpen = false }
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

@Composable
private fun ChatMessageItem(message: ChatMessageUi) {
    val isUser = message.type == ChatMessageType.USER_TEXT

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
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            if (message.modelUsed != null) {
                Text(
                    text = message.modelUsed,
                    color = if (isUser) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            message.performance?.let { perf ->
                Text(
                    text = "prompt ${perf.promptLen} · decode ${perf.decodeLen} " +
                        "· prefill ${perf.prefillTimeMs}ms · decode ${perf.decodeTimeMs}ms " +
                        "· ${String.format("%.1f", perf.decodeSpeed)} tok/s",
                    color = if (isUser) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatInputArea(
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onModelSwitch: (ChatModelOption) -> Unit,
    onSendMessage: (String) -> Unit,
    onToggleQuickActions: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 模型选择器
            ModelSelector(
                currentModel = currentModel,
                onModelSelected = onModelSwitch
            )

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
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )

            // 发送按钮
            IconButton(
                onClick = {
                    if (text.isNotBlank() && !isProcessing) {
                        onSendMessage(text.trim())
                        text = ""
                    }
                },
                enabled = text.isNotBlank() && !isProcessing,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank() && !isProcessing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Gray.copy(alpha = 0.3f)
                        }
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 快捷入口展开面板 — 悬浮在右下角
 */
@Composable
private fun QuickActionPanel(
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onEditorClick: () -> Unit,
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
                label = "编辑",
                icon = Icons.Rounded.Edit,
                onClick = onEditorClick
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
