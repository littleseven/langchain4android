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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.usecase.AiAgentUseCase
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
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
                        }
                    )
                }
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
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }

    // 语音 recognizer
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
            },
            onStopListening = {
                isListening = false
                speechRecognizer.stopListening()
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

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed && !isListening) {
            onStartListening()
        } else if (!isPressed && isListening) {
            onStopListening()
        }
    }

    IconButton(
        onClick = { },
        interactionSource = interactionSource,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (isListening || isPressed) Color(0xFFE53935) else Color.White.copy(alpha = 0.15f)
            )
    ) {
        Icon(
            imageVector = if (isListening || isPressed) Icons.Rounded.Stop else Icons.Rounded.KeyboardVoice,
            contentDescription = if (isListening || isPressed) "Recording..." else "Hold to speak",
            tint = if (isListening || isPressed) Color.White else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun sendMessage(
    scope: CoroutineScope,
    state: AiAgentPanelState,
    useCase: AiAgentUseCase,
    currentState: AiAgentUseCase.CameraStateSnapshot,
    input: String,
    onCommand: (AiAgentCommand) -> Unit
) {
    state.addMessage(AiAgentMessage(content = input, isFromUser = true))
    state.isProcessing = true

    scope.launch {
        val result = useCase.processInput(input, currentState)
        state.isProcessing = false

        result.onSuccess { command ->
            when (command) {
                is AiAgentCommand.TextReply -> {
                    state.addMessage(
                        AiAgentMessage(content = command.message, isFromUser = false)
                    )
                }
                else -> {
                    state.addMessage(
                        AiAgentMessage(
                            content = "Done!",
                            isFromUser = false
                        )
                    )
                    onCommand(command)
                }
            }
        }.onFailure { error ->
            state.addMessage(
                AiAgentMessage(
                    content = "Error: ${error.message}",
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
