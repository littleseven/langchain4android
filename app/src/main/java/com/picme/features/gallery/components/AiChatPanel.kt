package com.picme.features.gallery.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.picme.R
import com.picme.domain.model.MediaAsset

/**
 * AI Chat Panel - 自然语言图片编辑聊天界面
 * 
 * 功能：
 * 1. 用户通过自然语言描述想要对图片进行的编辑
 * 2. AI Agent 解析指令并执行相应的图片编辑操作
 * 3. 支持美颜调整、滤镜应用、裁剪旋转等编辑命令
 */
@Composable
fun AiChatPanel(
    currentAsset: MediaAsset,
    onDismiss: () -> Unit,
    onApplyEdit: (Bitmap) -> Unit
) {
    var userInput by remember { mutableStateOf("") }
    val messages = remember { mutableListOf<AiChatMessage>() }
    var isProcessing by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ai_chat_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Messages Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages) { message ->
                            AiChatMessageRow(message = message)
                        }
                    }

                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.ai_chat_processing),
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input Area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.ai_chat_input_hint),
                                color = Color.Gray
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.1f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color.Gray,
                            unfocusedPlaceholderColor = Color.Gray
                        ),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (userInput.isNotBlank() && !isProcessing) {
                                // Add user message
                                messages.add(AiChatMessage.User(userInput))
                                val input = userInput
                                userInput = ""

                                // Simulate AI processing (TODO: integrate with AiAgentUseCase)
                                isProcessing = true
                                processAiCommand(input) { response ->
                                    isProcessing = false
                                    messages.add(AiChatMessage.AI(response))
                                }
                            }
                        },
                        enabled = userInput.isNotBlank() && !isProcessing
                    ) {
                        Icon(
                            Icons.Rounded.Send,
                            contentDescription = stringResource(R.string.apply),
                            tint = if (isProcessing || userInput.isBlank()) Color.Gray else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiChatMessageRow(message: AiChatMessage) {
    val isUser = message is AiChatMessage.User
    val content = when (message) {
        is AiChatMessage.User -> message.content
        is AiChatMessage.AI -> message.content
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp)
        ) {
            Text(
                text = content,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

sealed class AiChatMessage {
    data class User(val content: String) : AiChatMessage()
    data class AI(val content: String) : AiChatMessage()
}

/**
 * 模拟 AI 命令处理（TODO: 集成真实的 AiAgentUseCase）
 */
private fun processAiCommand(command: String, onResponse: (String) -> Unit) {
    // 这里应该调用 AiAgentUseCase 来处理自然语言命令
    // 由于当前 AiAgentUseCase 是针对相机模式的，需要扩展支持图片编辑场景
    
    // 模拟一些常见命令的响应
    when {
        command.contains("磨皮") || command.contains("美白") || command.contains("美颜") -> {
            onResponse("好的，我已经为您调整了美颜参数，让皮肤更加光滑细腻~")
        }
        command.contains("滤镜") || command.contains("风格") -> {
            onResponse("已为您应用了喜欢的滤镜效果，看看喜欢吗？")
        }
        command.contains("瘦脸") || command.contains("大眼") -> {
            onResponse("面部轮廓已经优化完成，看起来更精致了呢~")
        }
        command.contains("保存") || command.contains("导出") -> {
            onResponse("好的，正在为您保存图片...")
            // TODO: 触发图片保存逻辑
        }
        else -> {
            onResponse("我理解您想编辑这张图片。您可以尝试说：\n• 磨皮美白\n• 应用滤镜\n• 瘦脸大眼\n• 调整亮度对比度\n• 保存修改")
        }
    }
}
