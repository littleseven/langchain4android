package com.mamba.picme.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val SESSION_ID = "default"
private const val MAX_MESSAGES = 500

/**
 * Chat 首页 ViewModel — 管理聊天状态与数据流
 *
 * 职责：
 * - 维护消息列表（从 Room 加载）
 * - 处理用户发送消息
 * - 管理模型切换状态（本地/远程）
 * - 提供处理中状态（isProcessing）
 */
class ChatViewModel(
    private val chatMessageDao: ChatMessageDao
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentModel = MutableStateFlow<ChatModelOption>(ChatModelOption.Local)
    val currentModel: StateFlow<ChatModelOption> = _currentModel.asStateFlow()

    init {
        loadMessages()
    }

    private fun loadMessages() {
        viewModelScope.launch {
            try {
                chatMessageDao.getMessagesBySession(SESSION_ID)
                    .collect { entities ->
                        _messages.value = entities.map { it.toUiModel() }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load messages", e)
            }
        }
    }

    /**
     * 发送用户消息
     *
     * 流程：
     * 1. 保存用户消息到 Room
     * 2. 触发 AI 处理（占位，后续接入 AgentOrchestrator）
     * 3. 保存 AI 回复到 Room
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                // 1. 保存用户消息
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = SESSION_ID,
                    type = "user_text",
                    content = text,
                    modelUsed = null
                )
                chatMessageDao.insertMessage(userMessage)

                // 2. 触发处理状态
                _isProcessing.value = true

                // 3. 模拟 AI 回复（占位，后续接入真实 Agent）
                // TODO: 接入 AgentOrchestrator 进行真实推理
                val modelLabel = when (_currentModel.value) {
                    is ChatModelOption.Local -> "local_qwen3.5_2b"
                    is ChatModelOption.Remote -> "remote_deepseek"
                }

                val responseText = when (_currentModel.value) {
                    is ChatModelOption.Local -> "[本地模型] 已收到：$text"
                    is ChatModelOption.Remote -> "[远程模型] 已收到：$text"
                }

                val agentMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = SESSION_ID,
                    type = "agent_text",
                    content = responseText,
                    modelUsed = modelLabel
                )
                chatMessageDao.insertMessage(agentMessage)

                // 4. 清理超限消息
                cleanupIfNeeded()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send message", e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * 切换当前模型
     */
    fun switchModel(model: ChatModelOption) {
        _currentModel.value = model
        Logger.i(TAG, "Model switched to: ${model.label}")
    }

    /**
     * 清空当前会话
     */
    fun clearChat() {
        viewModelScope.launch {
            try {
                chatMessageDao.deleteAllMessagesBySession(SESSION_ID)
                _messages.value = emptyList()
                Logger.i(TAG, "Chat cleared")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to clear chat", e)
            }
        }
    }

    /**
     * 如果消息数超过上限，删除最早的消息
     */
    private suspend fun cleanupIfNeeded() {
        try {
            val count = chatMessageDao.getMessageCount(SESSION_ID)
            if (count > MAX_MESSAGES) {
                val excess = count - MAX_MESSAGES
                chatMessageDao.deleteOldestMessages(SESSION_ID, excess)
                Logger.i(TAG, "Cleaned up $excess old messages")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to cleanup messages", e)
        }
    }

    private fun ChatMessageEntity.toUiModel(): ChatMessageUi {
        return ChatMessageUi(
            id = id,
            type = when (type) {
                "user_text" -> ChatMessageType.USER_TEXT
                "agent_text" -> ChatMessageType.AGENT_TEXT
                "user_image" -> ChatMessageType.USER_IMAGE
                "agent_image" -> ChatMessageType.AGENT_IMAGE
                "command" -> ChatMessageType.COMMAND
                "plan_preview" -> ChatMessageType.PLAN_PREVIEW
                else -> ChatMessageType.AGENT_TEXT
            },
            content = content,
            modelUsed = modelUsed,
            timestamp = timestamp
        )
    }
}

/**
 * ChatViewModel 工厂
 */
class ChatViewModelFactory(
    private val chatMessageDao: ChatMessageDao
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(chatMessageDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
