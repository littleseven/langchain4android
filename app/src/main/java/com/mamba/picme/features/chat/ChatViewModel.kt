package com.mamba.picme.features.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.api.context.ChatMessage
import com.mamba.picme.agent.core.api.context.ChatRole
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val MAX_MESSAGES = 500
private const val CHAT_SYSTEM_PROMPT = "You are a helpful AI assistant. Respond concisely and naturally in the same language as the user."

/**
 * Chat 首页 ViewModel — 管理聊天状态与数据流
 *
 * 职责：
 * - 维护消息列表（从 Room 加载）
 * - 处理用户发送消息，通过 LLM 推理获取真实回复
 * - 管理模型切换状态（本地/远程）
 * - 提供处理中状态（isProcessing）
 * - 管理会话列表和当前会话切换
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    context: Context,
    private val chatMessageDao: ChatMessageDao
) : ViewModel() {

    private val orchestrator = AgentOrchestrator.getInstance(context.applicationContext)

    private val _currentSessionId = MutableStateFlow("default")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentModel = MutableStateFlow<ChatModelOption>(ChatModelOption.Local)
    val currentModel: StateFlow<ChatModelOption> = _currentModel.asStateFlow()

    private val _threads = MutableStateFlow<List<ChatThreadUi>>(emptyList())
    val threads: StateFlow<List<ChatThreadUi>> = _threads.asStateFlow()

    init {
        loadMessages()
        loadThreads()
    }

    private fun loadMessages() {
        viewModelScope.launch {
            try {
                _currentSessionId
                    .flatMapLatest { sessionId ->
                        chatMessageDao.getMessagesBySession(sessionId)
                    }
                    .collect { entities ->
                        _messages.value = entities.map { it.toUiModel() }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load messages", e)
            }
        }
    }

    private fun loadThreads() {
        viewModelScope.launch {
            try {
                chatMessageDao.getAllSessionIds()
                    .collect { sessionIds ->
                        _threads.value = sessionIds.map { id ->
                            ChatThreadUi(
                                sessionId = id,
                                title = if (id == "default") "New Chat" else id,
                                isSelected = id == _currentSessionId.value
                            )
                        }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load threads", e)
            }
        }
    }

    /**
     * 切换当前会话
     */
    fun switchSession(sessionId: String) {
        _currentSessionId.value = sessionId
        Logger.i(TAG, "Switched to session: $sessionId")
    }

    /**
     * 发送用户消息，通过 LLM 推理获取真实回复
     *
     * 流程：
     * 1. 保存用户消息到 Room
     * 2. 构建对话历史
     * 3. 调用 LLM 推理（本地/远程）
     * 4. 保存 AI 回复到 Room
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                val sessionId = _currentSessionId.value

                // 1. 保存用户消息
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "user_text",
                    content = text,
                    modelUsed = null
                )
                chatMessageDao.insertMessage(userMessage)

                // 2. 触发处理状态
                _isProcessing.value = true

                // 3. 构建对话历史并调用 LLM
                val history = buildChatHistory(sessionId)
                val responseText = when (_currentModel.value) {
                    is ChatModelOption.Local -> generateLocalResponse(history)
                    is ChatModelOption.Remote -> generateRemoteResponse(history, text)
                }

                val modelLabel = when (_currentModel.value) {
                    is ChatModelOption.Local -> "local_qwen3.5_2b"
                    is ChatModelOption.Remote -> "remote_deepseek"
                }

                val agentMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "agent_text",
                    content = responseText,
                    modelUsed = modelLabel
                )
                chatMessageDao.insertMessage(agentMessage)

                // 4. 清理超限消息
                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send message", e)
                // 保存错误提示
                val errorMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = _currentSessionId.value,
                    type = "agent_text",
                    content = "推理出错：${e.message ?: "未知错误"}",
                    modelUsed = "error"
                )
                chatMessageDao.insertMessage(errorMessage)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * 构建对话历史（system + 最近 10 轮对话）
     */
    private suspend fun buildChatHistory(sessionId: String): List<ChatMessage> {
        val messages = chatMessageDao.getRecentMessages(sessionId, limit = 20)
        val history = mutableListOf<ChatMessage>()
        history.add(ChatMessage(role = ChatRole.SYSTEM, content = CHAT_SYSTEM_PROMPT))
        messages.reversed().forEach { entity ->
            val role = when (entity.type) {
                "user_text", "user_image" -> ChatRole.USER
                "agent_text", "agent_image", "command", "plan_preview" -> ChatRole.ASSISTANT
                else -> ChatRole.ASSISTANT
            }
            history.add(ChatMessage(role = role, content = entity.content))
        }
        return history
    }

    /**
     * 本地模型推理
     */
    private suspend fun generateLocalResponse(history: List<ChatMessage>): String {
        return try {
            // 确保模型已加载
            if (!orchestrator.isModelLoaded) {
                Logger.i(TAG, "Local model not loaded, attempting to load...")
                val loadResult = orchestrator.loadModel()
                if (loadResult.isFailure) {
                    return "模型未加载，请前往设置 → AI 模型管理下载本地模型"
                }
            }

            // 使用 generateWithHistory 进行对话推理
            val engine = getLocalLlmEngine()
            if (engine == null) {
                return "本地推理引擎不可用"
            }

            val result = engine.generateWithHistory(
                messages = history,
                maxTokens = 512
            )
            result.getOrElse { "推理失败：${it.message}" }
        } catch (e: Exception) {
            Logger.e(TAG, "Local inference failed", e)
            "本地推理出错：${e.message ?: "未知错误"}"
        }
    }

    /**
     * 远程模型推理（通过 InferenceRouter）
     */
    private suspend fun generateRemoteResponse(history: List<ChatMessage>, userInput: String): String {
        return try {
            // 构建简单 prompt 用于远程推理
            val prompt = buildString {
                appendLine(CHAT_SYSTEM_PROMPT)
                appendLine()
                history.drop(1).forEach { msg ->
                    val prefix = when (msg.role) {
                        ChatRole.USER -> "User"
                        ChatRole.ASSISTANT -> "Assistant"
                        ChatRole.SYSTEM -> "System"
                    }
                    appendLine("$prefix: ${msg.content}")
                }
                appendLine("User: $userInput")
                appendLine("Assistant:")
            }

            // 通过 AgentOrchestrator 的远程能力进行推理
            // 简化：使用 processInputWithRouter 获取 Chat 结果
            val agentContext = com.mamba.picme.agent.core.api.context.AgentContext(
                scene = com.mamba.picme.agent.core.api.context.AgentScene.CAMERA,
                memorySessionId = _currentSessionId.value
            )
            val inferenceResult = orchestrator.processInputWithRouter(
                input = userInput,
                agentContext = agentContext
            )

            when (inferenceResult) {
                is com.mamba.picme.agent.core.runtime.execution.InferenceResult.Chat -> inferenceResult.message
                is com.mamba.picme.agent.core.runtime.execution.InferenceResult.Local -> {
                    when (val cmd = inferenceResult.command) {
                        is com.mamba.picme.agent.core.api.command.AgentCommand.TextReply -> cmd.message
                        else -> "收到命令：${cmd::class.simpleName}"
                    }
                }
                else -> "推理完成"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Remote inference failed", e)
            "远程推理出错：${e.message ?: "未知错误"}"
        }
    }

    /**
     * 获取 LocalLlmEngine 实例（通过反射访问 orchestrator 内部）
     * 注意：这是临时方案，长期应通过公开 API 暴露
     */
    private fun getLocalLlmEngine(): com.mamba.picme.agent.core.platform.llm.local.LocalLlmEngine? {
        return try {
            val configuratorField = AgentOrchestrator::class.java.getDeclaredField("configurator")
            configuratorField.isAccessible = true
            val configurator = configuratorField.get(orchestrator)

            val engineField = configurator.javaClass.getDeclaredField("localLlmEngine")
            engineField.isAccessible = true
            engineField.get(configurator) as? com.mamba.picme.agent.core.platform.llm.local.LocalLlmEngine
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to access LocalLlmEngine", e)
            null
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
                val sessionId = _currentSessionId.value
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                _messages.value = emptyList()
                Logger.i(TAG, "Chat cleared for session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to clear chat", e)
            }
        }
    }

    /**
     * 如果消息数超过上限，删除最早的消息
     */
    private suspend fun cleanupIfNeeded(sessionId: String) {
        try {
            val count = chatMessageDao.getMessageCount(sessionId)
            if (count > MAX_MESSAGES) {
                val excess = count - MAX_MESSAGES
                chatMessageDao.deleteOldestMessages(sessionId, excess)
                Logger.i(TAG, "Cleaned up $excess old messages for session $sessionId")
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
    private val dependencies: ChatViewModelDependencies
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(
                context = dependencies.context,
                chatMessageDao = dependencies.chatMessageDao
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * ChatViewModel 依赖
 */
class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: com.mamba.picme.data.local.ChatMessageDao
)
