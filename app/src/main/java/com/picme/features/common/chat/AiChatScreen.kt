package com.picme.features.common.chat

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.picme.R
import com.picme.core.common.Logger
import com.picme.domain.model.AiAgentCommand

/**
 * AI Chat Screen - 统一聊天界面组件
 * 
 * 功能特性：
 * 1. ModalBottomSheet 设计，系统自动处理键盘 insets
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    visible: Boolean,
    messages: List<AgentMessage>,
    isProcessing: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    autoExecutePlans: Boolean = true,
    onPlanConfirm: () -> Unit = {},
    onPlanCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    LaunchedEffect(visible) {
        if (visible) {
            sheetState.expand()
        } else {
            sheetState.hide()
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = { onVisibleChange(false) },
            sheetState = sheetState,
            containerColor = Color.Transparent,
            contentColor = Color.Transparent,
            scrimColor = Color.Black.copy(alpha = 0.32f),
            dragHandle = null
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
                modifier = modifier
            )
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
    modifier: Modifier = Modifier
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
                    onCommand = onCommand
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
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var inputMode by remember { mutableStateOf(InputMode.VOICE) }
    
    // Voice recognizer
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
                speechRecognizer = speechRecognizer,
                context = context
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
    speechRecognizer: SpeechRecognizer,
    context: Context
) {
    var isListening by remember { mutableStateOf(false) }
    var isCancelRecord by remember { mutableStateOf(false) }

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

        // Hold to speak button - 使用 PressInteractionSource 实现可靠的按住说话
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        // 监听按下状态变化
        LaunchedEffect(isPressed) {
            if (isPressed) {
                if (!isListening) {
                    isListening = true
                    isCancelRecord = false
                    startVoiceRecognition(
                        context = context,
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
            } else {
                if (isListening) {
                    isListening = false
                    speechRecognizer.stopListening()
                    isCancelRecord = false
                }
            }
        }

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
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { /* 按住说话，单击无操作 */ }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isListening) "松开结束" else "按住说话",
                color = if (isListening) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }

        // Placeholder for balance
        Box(modifier = Modifier.size(40.dp))
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
        override fun onError(error: Int) { onError() }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            onResult(matches?.firstOrNull() ?: "")
        }
        override fun onPartialResults(partialResults: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}
