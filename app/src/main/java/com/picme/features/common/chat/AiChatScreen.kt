package com.picme.features.common.chat

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.core.common.Logger
import com.picme.domain.model.AiAgentCommand
import com.picme.features.camera.voice.VoiceCommandCoordinator

private tailrec fun Context.findActivityForWindow(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivityForWindow()
    else -> null
}

/**
 * AI Chat Screen - 统一聊天界面组件
 * 
 * 功能特性：
 * 1. 非模态浮层设计，不遮挡页面预览和底层交互
 * 2. 折叠/展开功能，节省屏幕空间
 * 3. 文字 + 语音输入切换
 * 4. 支持多种消息类型（UserText、AgentText、PlanPreview、PlanProgress、PlanResult）
 * 5. 优雅的动画效果
 * 6. 拖拽把手设计
 * 
 * 使用示例：
 * ```kotlin
 * AiChatScreen(
 *     visible = chatState.isVisible,
 *     onVisibleChange = { chatState.isVisible = it },
 *     messages = chatState.messages,
 *     isProcessing = chatState.isProcessing,
 *     onSendMessage = { input -> /* handle send */ },
 *     onCommand = { command -> /* execute command */ }
 * )
 * ```
 */
@Composable
fun AiChatScreen(
    visible: Boolean,
    messages: List<AgentMessage>,
    isProcessing: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    modifier: Modifier = Modifier,
    consumedBottomInset: Dp = 0.dp,
    autoExecutePlans: Boolean = true,
    onPlanConfirm: () -> Unit = {},
    onPlanCancel: () -> Unit = {},
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    val context = LocalContext.current

    DisposableEffect(visible, context) {
        if (!visible) {
            return@DisposableEffect onDispose {}
        }
        val activity = context.findActivityForWindow() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        onDispose {
            window.setSoftInputMode(previousSoftInputMode)
        }
    }

    BackHandler(enabled = visible) {
        onVisibleChange(false)
    }

    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val hiddenPanelBottomPadding = (navBottomDp - consumedBottomInset).coerceAtLeast(0.dp)
    val panelBottomPadding = if (imeBottomPx > 0) imeBottomDp else hiddenPanelBottomPadding

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = panelBottomPadding)
            ) {
                AiChatScreenContent(
                    messages = messages,
                    isProcessing = isProcessing,
                    onSendMessage = onSendMessage,
                    onCommand = onCommand,
                    autoExecutePlans = autoExecutePlans,
                    onDismiss = { onVisibleChange(false) },
                    onPlanConfirm = onPlanConfirm,
                    onPlanCancel = onPlanCancel,
                    voiceCoordinator = voiceCoordinator
                )
            }
        }
    }
}

@Composable
private fun AiChatScreenContent(
    messages: List<AgentMessage>,
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    autoExecutePlans: Boolean,
    onDismiss: () -> Unit,
    onPlanConfirm: () -> Unit,
    onPlanCancel: () -> Unit,
    modifier: Modifier = Modifier,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    var isExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header with drag handle
        AiChatHeader(
            isExpanded = isExpanded,
            onToggleExpand = { isExpanded = !isExpanded },
            onClose = onDismiss
        )

        // Expandable content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Messages list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages) { message ->
                        ChatBubble(
                            message = message,
                            onConfirm = onPlanConfirm,
                            onCancel = onPlanCancel
                        )
                    }
                }

                // Input bar
                ChatInputBar(
                    isProcessing = isProcessing,
                    onSend = onSendMessage,
                    onCommand = onCommand,
                    voiceCoordinator = voiceCoordinator
                )
            }
        }
    }
}

@Composable
private fun AiChatHeader(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 180f,
        label = "expandRotation"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.ai_agent),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Expand/Collapse button
            IconButton(
                onClick = onToggleExpand,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    // Drag handle
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onToggleExpand() }
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
        )
    }
}

@Composable
private fun ChatBubble(
    message: AgentMessage,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    when (message) {
        is AgentMessage.UserText -> UserTextBubble(message, modifier)
        is AgentMessage.AgentText -> AgentTextBubble(message, modifier)
        is AgentMessage.PlanPreview -> PlanPreviewBubble(message, modifier, onConfirm, onCancel)
        is AgentMessage.PlanProgress -> PlanProgressBubble(message, modifier)
        is AgentMessage.PlanResult -> PlanResultBubble(message, modifier)
    }
}

@Composable
private fun UserTextBubble(
    message: AgentMessage.UserText,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = message.content,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AgentTextBubble(
    message: AgentMessage.AgentText,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message.content,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray.copy(alpha = 0.8f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PlanPreviewBubble(
    message: AgentMessage.PlanPreview,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    // Check if this is a plan with actions
    val hasActions = message.content.contains("📋") || message.content.contains("执行计划")
    
    if (hasActions && (onConfirm != null || onCancel != null)) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray.copy(alpha = 0.8f))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message.content,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = onConfirm != null
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Confirm",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        enabled = onCancel != null
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Cancel",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PlanProgressBubble(
    message: AgentMessage.PlanProgress,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message.content,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.1f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PlanResultBubble(
    message: AgentMessage.PlanResult,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message.content,
            color = MaterialTheme.colorScheme.tertiary,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * 输入模式枚举
 */
private enum class InputMode {
    TEXT,
    VOICE
}

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    onSend: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    modifier: Modifier = Modifier,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var inputMode by remember { mutableStateOf(InputMode.VOICE) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (inputMode) {
            InputMode.TEXT -> TextInputMode(
                text = text,
                onTextChange = { text = it },
                isProcessing = isProcessing,
                onSend = {
                    if (text.isNotBlank() && !isProcessing) {
                        onSend(text.trim())
                        text = ""
                        keyboardController?.hide()
                    }
                },
                onSwitchToVoice = {
                    inputMode = InputMode.VOICE
                    keyboardController?.hide()
                }
            )
            InputMode.VOICE -> VoiceInputMode(
                onSwitchToText = {
                    inputMode = InputMode.TEXT
                    keyboardController?.show()
                },
                onVoiceResult = { result ->
                    if (result.isNotBlank()) {
                        onSend(result)
                    }
                },
                context = context,
                voiceCoordinator = voiceCoordinator
            )
        }
    }
}

@Composable
private fun TextInputMode(
    text: String,
    onTextChange: (String) -> Unit,
    isProcessing: Boolean,
    onSend: () -> Unit,
    onSwitchToVoice: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Voice switch button
        IconButton(
            onClick = onSwitchToVoice,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardVoice,
                contentDescription = "Switch to voice",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }

        // Text input
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    stringResource(R.string.ai_agent_input_hint),
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { onSend() }
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 13.sp
            ),
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
            )
        )

        // Send button
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank() && !isProcessing,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (text.isNotBlank() && !isProcessing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Gray.copy(alpha = 0.5f)
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

@Composable
private fun VoiceInputMode(
    onSwitchToText: () -> Unit,
    onVoiceResult: (String) -> Unit,
    context: Context,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    var isListening by remember { mutableStateOf(false) }
    var isCancelRecord by remember { mutableStateOf(false) }

    // 检查语音协调器是否可用
    val isVoiceAvailable = voiceCoordinator != null

    LaunchedEffect(isListening, isCancelRecord) {
        // Track state
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Text switch button
        IconButton(
            onClick = onSwitchToText,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = "Switch to keyboard",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }

        // Hold to speak button
        val buttonBackground = when {
            isListening && !isCancelRecord -> MaterialTheme.colorScheme.primary
            isListening && isCancelRecord -> Color(0xFFE53935)
            else -> Color.White.copy(alpha = 0.12f)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(buttonBackground)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            Logger.d("PicMe:Voice", "Pointer event: ${event.type}, changes=${event.changes.size}")
                            event.changes.forEach { change ->
                                Logger.d("PicMe:Voice", "Change: pressed=${change.pressed}, consumed=${change.isConsumed}, pos=${change.position}")
                                when {
                                    // 手指按下
                                    change.pressed && !change.isConsumed && !isListening -> {
                                        Logger.d("PicMe:Voice", "Finger DOWN detected -> voiceCoordinator=${voiceCoordinator != null}, isVoiceAvailable=$isVoiceAvailable")
                                        if (!isVoiceAvailable) {
                                            Logger.w("PicMe:Voice", "Voice coordinator not available")
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "语音识别未初始化，请检查配置",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            return@forEach
                                        }
                                        isListening = true
                                        isCancelRecord = false
                                        Logger.d("PicMe:Voice", "Using VoiceCommandCoordinator for push-to-talk")
                                        voiceCoordinator?.startPushToTalk { result ->
                                            Logger.i("PicMe:Voice", "VoiceCoordinator result: '$result'")
                                            isListening = false
                                            isCancelRecord = false
                                            if (result.isNotBlank()) {
                                                onVoiceResult(result)
                                            }
                                        }
                                    }
                                    // 手指抬起
                                    !change.pressed -> {
                                        Logger.d("PicMe:Voice", "Finger UP detected -> isListening=$isListening")
                                        if (isListening) {
                                            isListening = false
                                            voiceCoordinator?.stopPushToTalk()
                                            isCancelRecord = false
                                        }
                                    }
                                }
                                // ACTION_MOVE: 检测是否移出按钮区域以取消
                                if (isListening && change.pressed) {
                                    val bounds = this@pointerInput.size
                                    val x = change.position.x
                                    val y = change.position.y
                                    val newCancel = x < 0 || x > bounds.width || y < 0 || y > bounds.height
                                    if (newCancel != isCancelRecord) {
                                        Logger.d("PicMe:Voice", "Cancel state changed: $isCancelRecord -> $newCancel")
                                        isCancelRecord = newCancel
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    !isVoiceAvailable -> "语音不可用"
                    isListening -> "松开结束"
                    else -> "按住说话"
                },
                color = if (isListening) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }

        // Placeholder for balance
        Box(modifier = Modifier.size(40.dp))
    }
}
