package com.picme.features.camera.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 80.dp),
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
    var isListening by remember { mutableStateOf(false) }

    // 语音 recognizer（当没有外部 VoiceCommandCoordinator 时使用）
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 语音输入按钮
        VoiceInputButton(
            isListening = isListening,
            onStartListening = {
                isListening = true
                val coordinator = voiceCoordinator
                if (coordinator != null) {
                    coordinator.startPushToTalk { result ->
                        isListening = false
                        if (result.isNotBlank()) {
                            onSend(result)
                        }
                    }
                } else {
                    startVoiceRecognition(
                        context = context,
                        speechRecognizer = speechRecognizer,
                        onResult = { result ->
                            isListening = false
                            if (result.isNotBlank()) {
                                onSend(result)
                            }
                        },
                        onError = {
                            isListening = false
                        }
                    )
                }
            },
            onStopListening = {
                isListening = false
                val coordinator = voiceCoordinator
                if (coordinator != null) {
                    coordinator.stopPushToTalk()
                } else {
                    speechRecognizer.stopListening()
                }
            }
        )

        // 文字输入框
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
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
                onSend = {
                    if (text.isNotBlank() && !isProcessing) {
                        onSend(text.trim())
                        text = ""
                        keyboardController?.hide()
                    }
                }
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
            onClick = {
                if (text.isNotBlank() && !isProcessing) {
                    onSend(text.trim())
                    text = ""
                    keyboardController?.hide()
                }
            },
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
