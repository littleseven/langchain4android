package com.picme.features.camera.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
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
import androidx.compose.foundation.interaction.MutableInteractionSource


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row


import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip


import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.picme.R
import com.picme.core.common.Logger
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.usecase.AiAgentUseCase
import com.picme.features.camera.voice.VoiceCommandCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * AI Agent 对话消息模型
 */
data class AiAgentMessage(
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * AI Agent 面板状态
 */
class AiAgentPanelState {
    var isVisible by mutableStateOf(false)
    var isExpanded by mutableStateOf(true)
    var messages by mutableStateOf<List<AiAgentMessage>>(emptyList())
    var isProcessing by mutableStateOf(false)

    fun addMessage(message: AiAgentMessage) {
        messages = messages + message
    }

    fun toggle() {
        isVisible = !isVisible
        if (isVisible) {
            isExpanded = true
        }
    }

    fun open() {
        isVisible = true
        isExpanded = true
    }

    fun close() {
        isVisible = false
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
    }
}

@Composable
fun rememberAiAgentPanelState(): AiAgentPanelState {
    return remember { AiAgentPanelState() }
}

/**
 * AI Agent 底部浮动面板
 *
 * - 底部 Sheet 样式，不遮挡顶部预览
 * - 支持折叠/展开
 * - 支持语音输入
 */
@Composable
fun AiAgentPanel(
    state: AiAgentPanelState,
    useCase: AiAgentUseCase,
    currentState: AiAgentUseCase.CameraStateSnapshot,
    onCommand: (AiAgentCommand) -> Unit,
    voiceCoordinator: VoiceCommandCoordinator? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically { height -> height },
        exit = slideOutVertically { height -> height },
        modifier = modifier
    ) {
        AiAgentPanelContent(
            state = state,
            useCase = useCase,
            currentState = currentState,
            onCommand = onCommand,
            voiceCoordinator = voiceCoordinator
        )
    }
}

/**
 * AI Agent 面板 - Dialog 版本
 *
 * 使用独立 Dialog 窗口渲染，输入法弹出时不会影响底层预览页面布局。
 * 底部距离计算参考 MnnLlmChat 的 fitsSystemWindows 行为：
 * - 导航栏高度通过 WindowInsets.navigationBars 获取
 * - 键盘高度通过 imePadding() 自动处理
 * - 底部预留 8.dp 基础间距（类似 MnnLlmChat 的 layout_marginBottom）
 */
@Composable
fun AiAgentDialogPanel(
    state: AiAgentPanelState,
    useCase: AiAgentUseCase,
    currentState: AiAgentUseCase.CameraStateSnapshot,
    onCommand: (AiAgentCommand) -> Unit,
    voiceCoordinator: VoiceCommandCoordinator? = null,
    modifier: Modifier = Modifier
) {
    if (state.isVisible) {
        Dialog(
            onDismissRequest = { state.close() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // 参考 MnnLlmChat activity_chat.xml 的底部输入栏设计：
            // 1. CoordinatorLayout + fitsSystemWindows="true" — 系统自动处理 insets
            // 2. 输入容器 layout_gravity="bottom" — 贴底对齐
            // 3. 不手动计算导航栏/键盘高度 — 完全依赖系统行为
            //
            // Compose 等价实现：
            // - navigationBarsPadding() 自动避让导航栏
            // - imePadding() 自动避让键盘
            // - 两者不要混用，imePadding 已经包含导航栏处理
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                AiAgentPanelContent(
                    state = state,
                    useCase = useCase,
                    currentState = currentState,
                    onCommand = onCommand,
                    voiceCoordinator = voiceCoordinator,
                    modifier = modifier
                )
            }
        }
    }
}

@Composable
private fun AiAgentPanelContent(
    state: AiAgentPanelState,
    useCase: AiAgentUseCase,
    currentState: AiAgentUseCase.CameraStateSnapshot,
    onCommand: (AiAgentCommand) -> Unit,
    voiceCoordinator: VoiceCommandCoordinator? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 标题栏：拖拽把手 + 折叠/关闭按钮
        AiAgentHeader(
            isExpanded = state.isExpanded,
            onToggleExpand = { state.toggleExpand() },
            onClose = { state.close() }
        )

        // 可折叠内容区
        AnimatedVisibility(
            visible = state.isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 消息列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.messages) { message ->
                        ChatBubble(message = message)
                    }
                }

                // 输入栏（文字 + 语音）
                ChatInputBar(
                    isProcessing = state.isProcessing,
                    onSend = { input ->
                        sendMessage(
                            scope = scope,
                            state = state,
                            useCase = useCase,
                            currentState = currentState,
                            input = input,
                            onCommand = onCommand
                        )
                    },
                    voiceCoordinator = voiceCoordinator
                )
            }
        }
    }
}

@Composable
private fun AiAgentHeader(
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 折叠/展开按钮
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

            // 关闭按钮
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

    // 拖拽把手（点击可折叠）
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
    message: AiAgentMessage,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    } else {
        Color.DarkGray.copy(alpha = 0.8f)
    }
    val textColor = Color.White
    val alignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Text(
            text = message.content,
            color = textColor,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * 输入模式枚举，参考 MnnLlmChat 的语音/文字切换设计
 * - TEXT: 文字输入模式（显示输入框 + 发送按钮）
 * - VOICE: 语音输入模式（显示"按住说话"大按钮）
 */
private enum class InputMode {
    TEXT,
    VOICE
}

/**
 * 录音状态枚举，显式优于隐式（Agent First 原则）
 */
private enum class RecordingState {
    IDLE,       // 未录音
    RECORDING,  // 正在录音
    CANCELING   // 手指移出，将取消
}

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    onSend: (String) -> Unit,
    voiceCoordinator: VoiceCommandCoordinator?,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var inputMode by remember { mutableStateOf(InputMode.TEXT) }
    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }

    // 语音 recognizer（当没有外部 VoiceCommandCoordinator 时使用）
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 语音录制提示（仅在语音模式且录音中时显示）
        // 参考 MnnLlmChat: text_voice_hint 在底部 150dp 处显示
        AnimatedVisibility(
            visible = inputMode == InputMode.VOICE && recordingState != RecordingState.IDLE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (recordingState == RecordingState.CANCELING) "松开取消" else "松开发送",
                    color = if (recordingState == RecordingState.CANCELING)
                        Color(0xFFE53935) else Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }

        // 输入栏主体 - 参考 MnnLlmChat 的切换方式
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
                recordingState = recordingState,
                onRecordingStateChange = { recordingState = it },
                onSwitchToText = {
                    inputMode = InputMode.TEXT
                    keyboardController?.show()
                },
                onVoiceResult = { result ->
                    if (result.isNotBlank()) {
                        onSend(result)
                    }
                },
                voiceCoordinator = voiceCoordinator,
                speechRecognizer = speechRecognizer,
                context = context
            )
        }
    }
}

/**
 * 文字输入模式
 * 参考 MnnLlmChat: EditText + 发送按钮 + 语音切换按钮
 */
@Composable
private fun TextInputMode(
    text: String,
    onTextChange: (String) -> Unit,
    isProcessing: Boolean,
    onSend: () -> Unit,
    onSwitchToVoice: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 语音模式切换按钮（参考 MnnLlmChat 的 bt_switch_audio）
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

        // 文字输入框
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

        // 发送按钮
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

/**
 * 语音输入模式
 * 参考 MnnLlmChat VoiceRecordingModule:
 * - 大的"按住说话"按钮（类似 btn_voice_recording）
 * - 键盘切换按钮（类似 bt_switch_audio 切换到键盘图标）
 * - OnTouchListener 处理 DOWN/UP/MOVE
 * - 移出按钮区域变红取消（isCancelRecord）
 */
@Composable
private fun VoiceInputMode(
    recordingState: RecordingState,
    onRecordingStateChange: (RecordingState) -> Unit,
    onSwitchToText: () -> Unit,
    onVoiceResult: (String) -> Unit,
    voiceCoordinator: VoiceCommandCoordinator?,
    speechRecognizer: SpeechRecognizer,
    context: Context
) {
    var isListening by remember { mutableStateOf(false) }
    var isCancelRecord by remember { mutableStateOf(false) }

    // 同步 Compose state 到 RecordingState
    LaunchedEffect(isListening, isCancelRecord) {
        onRecordingStateChange(
            when {
                isListening && isCancelRecord -> RecordingState.CANCELING
                isListening -> RecordingState.RECORDING
                else -> RecordingState.IDLE
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 键盘切换按钮（参考 MnnLlmChat: bt_switch_audio 切换到 ic_keyboard）
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

        // 按住说话按钮（参考 MnnLlmChat 的 btn_voice_recording）
        // 使用 pointerInput 模拟 OnTouchListener 的 DOWN/UP/MOVE
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
                            event.changes.forEach { change ->
                                when {
                                    // ACTION_DOWN: 开始录音
                                    change.pressed && !change.isConsumed -> {
                                        if (!isListening) {
                                            isListening = true
                                            isCancelRecord = false
                                            startVoiceRecordingInternal(
                                                context = context,
                                                voiceCoordinator = voiceCoordinator,
                                                speechRecognizer = speechRecognizer,
                                                onResult = { result ->
                                                    isListening = false
                                                    isCancelRecord = false
                                                    if (result.isNotBlank()) {
                                                        onVoiceResult(result)
                                                    }
                                                },
                                                onError = {
                                                    isListening = false
                                                    isCancelRecord = false
                                                }
                                            )
                                        }
                                    }
                                    // ACTION_UP: 结束录音
                                    !change.pressed -> {
                                        if (isListening) {
                                            isListening = false
                                            if (!isCancelRecord) {
                                                stopVoiceRecordingInternal(
                                                    voiceCoordinator = voiceCoordinator,
                                                    speechRecognizer = speechRecognizer
                                                )
                                            } else {
                                                cancelVoiceRecordingInternal(
                                                    voiceCoordinator = voiceCoordinator,
                                                    speechRecognizer = speechRecognizer
                                                )
                                            }
                                            isCancelRecord = false
                                        }
                                    }
                                }
                                // ACTION_MOVE: 检测是否移出按钮区域
                                if (isListening && change.pressed) {
                                    val bounds = this@pointerInput.size
                                    val x = change.position.x
                                    val y = change.position.y
                                    isCancelRecord = x < 0 || x > bounds.width ||
                                            y < 0 || y > bounds.height
                                }
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isListening) "松开结束" else "按住说话",
                color = if (isListening) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }

        // 占位保持布局平衡（参考 MnnLlmChat 的对称设计）
        Box(modifier = Modifier.size(40.dp))
    }
}

/**
 * 开始语音录音（内部辅助函数）
 */
private fun startVoiceRecordingInternal(
    context: Context,
    voiceCoordinator: VoiceCommandCoordinator?,
    speechRecognizer: SpeechRecognizer,
    onResult: (String) -> Unit,
    onError: () -> Unit
) {
    val coordinator = voiceCoordinator
    if (coordinator != null) {
        coordinator.startPushToTalk { result ->
            if (result.isNotBlank()) {
                onResult(result)
            } else {
                onError()
            }
        }
    } else {
        startVoiceRecognition(
            context = context,
            speechRecognizer = speechRecognizer,
            onResult = { result ->
                if (result.isNotBlank()) {
                    onResult(result)
                } else {
                    onError()
                }
            },
            onError = onError
        )
    }
}

/**
 * 停止语音录音（内部辅助函数）
 */
private fun stopVoiceRecordingInternal(
    voiceCoordinator: VoiceCommandCoordinator?,
    speechRecognizer: SpeechRecognizer
) {
    val coordinator = voiceCoordinator
    if (coordinator != null) {
        coordinator.stopPushToTalk()
    } else {
        speechRecognizer.stopListening()
    }
}

/**
 * 取消语音录音（内部辅助函数）
 */
private fun cancelVoiceRecordingInternal(
    voiceCoordinator: VoiceCommandCoordinator?,
    speechRecognizer: SpeechRecognizer
) {
    val coordinator = voiceCoordinator
    if (coordinator != null) {
        coordinator.stopPushToTalk()
    } else {
        speechRecognizer.cancel()
    }
}

@Composable
private fun VoiceInputButton(
    isListening: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isListening) {
        Color(0xFFE53935)
    } else {
        Color.White.copy(alpha = 0.15f)
    }

    var isLongPressing by remember { mutableStateOf(false) }

    // 监听长按状态变化，触发录音控制
    LaunchedEffect(isLongPressing) {
        if (isLongPressing) {
            onStartListening()
        } else {
            onStopListening()
        }
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (isListening || isLongPressing) Color(0xFFE53935) else Color.White.copy(alpha = 0.15f)
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            when {
                                // 手指按下且未消费
                                change.pressed && !change.isConsumed -> {
                                    if (!isLongPressing) {
                                        isLongPressing = true
                                    }
                                }
                                // 手指抬起（任何状态都视为抬起）
                                !change.pressed -> {
                                    if (isLongPressing) {
                                        isLongPressing = false
                                    }
                                }
                            }
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening || isLongPressing) Icons.Rounded.Stop else Icons.Rounded.KeyboardVoice,
            contentDescription = if (isListening || isLongPressing) "Recording..." else "Hold to speak",
            tint = if (isListening || isLongPressing) Color.White else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp)
        )
    }
}

internal fun sendMessage(
    scope: CoroutineScope,
    state: AiAgentPanelState,
    useCase: AiAgentUseCase,
    currentState: AiAgentUseCase.CameraStateSnapshot,
    input: String,
    onCommand: (AiAgentCommand) -> Unit
) {
    Logger.i("PicMe:AiAgent", "sendMessage called with input='$input'")
    state.addMessage(AiAgentMessage(content = input, isFromUser = true))
    state.isProcessing = true

    scope.launch {
        Logger.i("PicMe:AiAgent", "Calling useCase.processInput...")
        val result = useCase.processInput(input, currentState)
        Logger.i("PicMe:AiAgent", "processInput returned: ${result.isSuccess}")
        state.isProcessing = false

        result.onSuccess { command ->
            Logger.i("PicMe:AiAgent", "Command parsed: ${command.javaClass.simpleName}")
            if (command is AiAgentCommand.TextReply) {
                Logger.i("PicMe:AiAgent", "TextReply: ${command.message}")
                state.addMessage(
                    AiAgentMessage(content = command.message, isFromUser = false)
                )
            } else {
                val commandName = when (command) {
                    is AiAgentCommand.AdjustBeauty -> "已调整美颜参数"
                    is AiAgentCommand.SwitchFilter -> "已切换滤镜"
                    is AiAgentCommand.SwitchStyle -> "已切换风格"
                    is AiAgentCommand.SwitchScene -> "已切换场景"
                    is AiAgentCommand.SwitchRatio -> "已切换画幅比例"
                    is AiAgentCommand.AdjustExposure -> "已调整曝光"
                    is AiAgentCommand.AdjustZoom -> "已调整变焦"
                    is AiAgentCommand.FlipCamera -> "已翻转摄像头"
                    is AiAgentCommand.CapturePhoto -> "已拍照"
                    is AiAgentCommand.ToggleRecording -> "已切换录像状态"
                    is AiAgentCommand.SwitchMode -> "已切换拍摄模式"
                    is AiAgentCommand.TextReply -> ""
                }
                Logger.i("PicMe:AiAgent", "Executing command: $commandName")
                state.addMessage(
                    AiAgentMessage(
                        content = commandName,
                        isFromUser = false
                    )
                )
                Logger.i("PicMe:AiAgent", "Calling onCommand callback")
                onCommand(command)
                Logger.i("PicMe:AiAgent", "onCommand callback returned")
            }
        }.onFailure { error ->
            Logger.e("PicMe:AiAgent", "AI processing failed: ${error.message}", error)
            state.addMessage(
                AiAgentMessage(
                    content = "处理出错了：${error.message ?: "未知错误"}",
                    isFromUser = false
                )
            )
        }
    }
}

private fun startVoiceRecognition(
    context: Context,
    speechRecognizer: SpeechRecognizer,
    onResult: (String) -> Unit,
    onError: () -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onError()
        return
    }

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            onError()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            onResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let {
                // 可选：实时显示部分结果
            }
        }
    })

    speechRecognizer.startListening(intent)
}
