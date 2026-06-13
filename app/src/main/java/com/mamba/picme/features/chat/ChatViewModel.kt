package com.mamba.picme.features.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.api.context.ChatMessage
import com.mamba.picme.agent.core.api.context.ChatRole
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.agent.core.platform.llm.local.StreamEvent
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val MAX_MESSAGES = 500
private const val MAX_PREVIEW_LENGTH = 60
private const val CHAT_SYSTEM_PROMPT = "You are a helpful AI assistant. Respond concisely and naturally in the same language as the user. " +
    "Do not output any thinking process. Do not use <think>, </think>, or <thinking> tags."

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
@Suppress("TooManyFunctions") // UI 状态协调器，函数数量由会话管理辅助方法驱动
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel() {

    private val context = dependencies.context.applicationContext
    private val chatMessageDao = dependencies.chatMessageDao
    private val chatSessionDao = dependencies.chatSessionDao

    private val orchestrator = AgentOrchestrator.getInstance(context)

    private val _currentSessionId = MutableStateFlow("default")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    /**
     * 当前正在流式生成的 AI 消息（未落库），用于实时展示 token。
     */
    private val _streamingMessage = MutableStateFlow<ChatMessageUi?>(null)
    val streamingMessage: StateFlow<ChatMessageUi?> = _streamingMessage.asStateFlow()

    /**
     * UI 实际展示的消息列表：已持久化消息 + 流式临时消息。
     */
    val displayMessages: StateFlow<List<ChatMessageUi>> = combine(_messages, _streamingMessage) { messages, streaming ->
        if (streaming != null) messages + streaming else messages
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentModel = MutableStateFlow<ChatModelOption>(ChatModelOption.Local)
    val currentModel: StateFlow<ChatModelOption> = _currentModel.asStateFlow()

    private val _threads = MutableStateFlow<List<ChatThreadUi>>(emptyList())
    val threads: StateFlow<List<ChatThreadUi>> = _threads.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 过滤后的线程列表
     */
    val filteredThreads: StateFlow<List<ChatThreadUi>> = combine(
        _threads,
        _searchQuery
    ) { threads, query ->
        if (query.isBlank()) threads
        else threads.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                chatSessionDao.getAllSessions()
                    .collect { sessions ->
                        val threads = sessions.map { session ->
                            val lastMessage = chatMessageDao.getLastMessageForSession(session.sessionId)
                            ChatThreadUi(
                                sessionId = session.sessionId,
                                title = resolveThreadTitle(session),
                                lastMessagePreview = lastMessage?.content?.take(MAX_PREVIEW_LENGTH) ?: "",
                                updatedAt = session.updatedAt,
                                isSelected = session.sessionId == _currentSessionId.value
                            )
                        }
                        _threads.value = threads
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load threads", e)
            }
        }
    }

    private fun resolveThreadTitle(session: ChatSessionEntity): String {
        return when {
            session.sessionId == "default" && session.title == "default" -> "New Chat"
            session.title.isBlank() -> "Chat"
            else -> session.title
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
     * 创建新会话并切换过去
     */
    fun newSession() {
        val sessionId = UUID.randomUUID().toString()
        viewModelScope.launch {
            try {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = sessionId,
                        title = "New Chat"
                    )
                )
                _currentSessionId.value = sessionId
                Logger.i(TAG, "Created new session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create session", e)
            }
        }
    }

    /**
     * 重命名会话
     */
    fun renameSession(sessionId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            try {
                val trimmed = newTitle.trim()
                chatSessionDao.updateTitle(sessionId, trimmed)
                Logger.i(TAG, "Renamed session $sessionId to: $trimmed")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to rename session", e)
            }
        }
    }

    /**
     * 删除会话及其消息；如果删除的是当前会话，切回 default
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.deleteSession(sessionId)
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = "default"
                }
                Logger.i(TAG, "Deleted session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete session", e)
            }
        }
    }

    /**
     * 更新搜索关键字（在内存中过滤线程列表）
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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

                // 0. 确保会话元数据存在（兼容旧数据或 default）
                ensureSessionExists(sessionId)

                // 1. 保存用户消息
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "user_text",
                    content = text,
                    modelUsed = null
                )
                chatMessageDao.insertMessage(userMessage)

                // 刷新会话活跃时间，确保线程列表排序正确
                chatSessionDao.touchSession(sessionId)

                // 2. 触发处理状态
                _isProcessing.value = true

                // 3. 构建对话历史并调用 LLM
                val history = buildChatHistory(sessionId)
                val modelLabel = when (_currentModel.value) {
                    is ChatModelOption.Local -> "local_qwen3.5_2b"
                    is ChatModelOption.Remote -> "remote_deepseek"
                }

                when (_currentModel.value) {
                    is ChatModelOption.Local -> generateLocalResponse(history, modelLabel, sessionId)
                    is ChatModelOption.Remote -> {
                        val responseText = generateRemoteResponse(history, text)
                        val agentMessage = ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            type = "agent_text",
                            content = responseText,
                            modelUsed = modelLabel
                        )
                        chatMessageDao.insertMessage(agentMessage)
                        chatSessionDao.touchSession(sessionId)
                    }
                }

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
                chatSessionDao.touchSession(_currentSessionId.value)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun ensureSessionExists(sessionId: String) {
        val existing = chatSessionDao.getSession(sessionId)
        if (existing == null) {
            chatSessionDao.insertSession(
                ChatSessionEntity(
                    sessionId = sessionId,
                    title = if (sessionId == "default") "New Chat" else "Chat"
                )
            )
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
     * 本地模型推理（流式）
     *
     * 通过 [LocalLlmEngine.generateStreamWithHistory] 实时获取 token，
     * 在 UI 上展示流式临时消息，生成结束后清理 think 标签并落库。
     */
    private suspend fun generateLocalResponse(
        history: List<ChatMessage>,
        modelLabel: String,
        sessionId: String
    ) {
        try {
            // 确保模型已加载
            if (!orchestrator.isModelLoaded) {
                Logger.i(TAG, "Local model not loaded, attempting to load...")
                val loadResult = orchestrator.loadModel()
                if (loadResult.isFailure) {
                    insertAgentMessage(sessionId, "模型未加载，请前往设置 → AI 模型管理下载本地模型", modelLabel)
                    return
                }
            }

            val engine = getLocalLlmEngine()
            if (engine == null) {
                insertAgentMessage(sessionId, "本地推理引擎不可用", modelLabel)
                return
            }

            // 创建流式临时消息
            val streamingId = UUID.randomUUID().toString()
            _streamingMessage.value = ChatMessageUi(
                id = streamingId,
                type = ChatMessageType.AGENT_TEXT,
                content = "",
                modelUsed = modelLabel
            )

            var rawResponse = ""
            var performance: LlmPerformance? = null
            engine.generateStreamWithHistory(messages = history, maxTokens = 512).collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        rawResponse = event.accumulatedText
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = cleanThinkTags(rawResponse)
                        )
                    }
                    is StreamEvent.Complete -> {
                        rawResponse = event.response
                        performance = LlmPerformance(
                            promptLen = event.promptLen,
                            decodeLen = event.decodeLen,
                            prefillTimeMs = event.prefillTime / 1000L,
                            decodeTimeMs = event.decodeTime / 1000L,
                            prefillSpeed = event.prefillSpeed,
                            decodeSpeed = event.decodeSpeed
                        )
                    }
                    is StreamEvent.Error -> {
                        throw RuntimeException(event.message)
                    }
                }
            }

            val cleanedResponse = cleanThinkTags(rawResponse)
            _streamingMessage.value = null
            insertAgentMessage(sessionId, cleanedResponse, modelLabel, performance)
        } catch (e: Exception) {
            Logger.e(TAG, "Local inference failed", e)
            _streamingMessage.value = null
            insertAgentMessage(sessionId, "本地推理出错：${e.message ?: "未知错误"}", "error")
        }
    }

    /**
     * 插入 AI 回复到 Room
     */
    private suspend fun insertAgentMessage(
        sessionId: String,
        content: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = performance?.let {
            JSONObject().apply {
                put("prompt_len", it.promptLen)
                put("decode_len", it.decodeLen)
                put("prefill_time_ms", it.prefillTimeMs)
                put("decode_time_ms", it.decodeTimeMs)
                put("prefill_speed", it.prefillSpeed.toDouble())
                put("decode_speed", it.decodeSpeed.toDouble())
            }.toString()
        }
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_text",
                content = content,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    /**
     * 清理 LLM 响应中的 think 标签（Qwen3 的 <think>...</think>）
     * 以及 <thinking>...</thinking>、思考...思考 等标记
     *
     * 参考 AgentCommandParser.cleanLlmResponse，针对闲聊场景做简化。
     */
    private fun cleanThinkTags(response: String): String {
        var cleaned = response.trim()

        val thinkTags = listOf(
            "<think>" to "</think>",
            "<thinking>" to "</thinking>",
            "思考" to "思考"
        )
        for ((startTag, endTag) in thinkTags) {
            // 1. 移除成对的 think 标签及其中间内容
            if (startTag == endTag) {
                // 中文“思考...思考”：start 与 end 是同一字符串，需按顺序配对
                while (true) {
                    val start = cleaned.indexOf(startTag)
                    if (start < 0) break
                    val end = cleaned.indexOf(endTag, start + startTag.length)
                    if (end < 0) break
                    cleaned = cleaned.removeRange(start, end + endTag.length).trim()
                }
            } else {
                while (true) {
                    val start = cleaned.indexOf(startTag)
                    val end = cleaned.indexOf(endTag)
                    if (start >= 0 && end > start) {
                        cleaned = cleaned.removeRange(start, end + endTag.length).trim()
                    } else {
                        break
                    }
                }
            }
            // 2. 处理未闭合的开始标签：优先保留标签之后的正文
            val orphanStart = cleaned.indexOf(startTag)
            if (orphanStart >= 0) {
                val afterTag = cleaned.substring(orphanStart + startTag.length).trim()
                val beforeTag = cleaned.substring(0, orphanStart).trim()
                cleaned = if (afterTag.isNotBlank()) afterTag else beforeTag
            }
            // 3. 移除残留的结束标签
            cleaned = cleaned.replace(endTag, "").trim()
        }

        // 移除 markdown 代码块标记
        cleaned = cleaned.replace(Regex("^```\\w*\\n?"), "").replace(Regex("\\n?```\\s*$"), "").trim()

        // 移除流式生成可能残留的 <eop> end-of-prefill 标记
        cleaned = cleaned.replace("<eop>", "").trim()

        return cleaned.ifBlank { "你好，我是小觅，有什么可以帮你的吗？" }
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
                chatSessionDao.updateTitle(sessionId, "New Chat")
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
        val performance = metadata?.let { parsePerformanceMetadata(it) }
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
            timestamp = timestamp,
            performance = performance
        )
    }

    /**
     * 从 metadata JSON 解析本地 LLM 性能指标
     */
    private fun parsePerformanceMetadata(metadata: String): LlmPerformance? {
        return try {
            val json = JSONObject(metadata)
            LlmPerformance(
                promptLen = json.optLong("prompt_len", 0),
                decodeLen = json.optLong("decode_len", 0),
                prefillTimeMs = json.optLong("prefill_time_ms", 0),
                decodeTimeMs = json.optLong("decode_time_ms", 0),
                prefillSpeed = json.optDouble("prefill_speed", 0.0).toFloat(),
                decodeSpeed = json.optDouble("decode_speed", 0.0).toFloat()
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse performance metadata", e)
            null
        }
    }
}
