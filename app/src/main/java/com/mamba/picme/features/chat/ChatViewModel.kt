package com.mamba.picme.features.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.platform.llm.local.LlmGenerationMetrics
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
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
     * 发送用户消息，通过 Agent 编排器执行命令或返回闲聊回复
     *
     * 流程：
     * 1. 保存用户消息到 Room
     * 2. 构建 Agent 上下文
     * 3. 根据当前模型选择本地/远程推理，解析为 AgentCommand
     * 4. 分发到 Capability 执行，并将结果保存到 Room
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

                // 3. 调用 Agent 编排器
                processAgentInput(text, sessionId)

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

    /**
     * 统一处理用户输入：本地/远程模型都走 processInputWithRouter。
     *
     * 本地 Qwen3-2B 已做过 OpenAI tool_calls 训练，因此 chat 页面统一通过 Tool Calling
     * 路径输出 OpenAI 格式指令；远程模型同样走此路径。
     */
    private suspend fun processAgentInput(text: String, sessionId: String) {
        val agentContext = AgentContext(
            scene = AgentScene.CHAT,
            memorySessionId = sessionId
        )
        val modelLabel = when (_currentModel.value) {
            is ChatModelOption.Local -> "local_qwen3.5_2b"
            is ChatModelOption.Remote -> "remote_deepseek"
        }

        val inferenceResult = orchestrator.processInputWithRouter(text, agentContext)
        val performance = if (_currentModel.value is ChatModelOption.Local) {
            orchestrator.getLastLocalGenerationMetrics()?.toLlmPerformance()
        } else {
            null
        }

        when (inferenceResult) {
            is com.mamba.picme.agent.core.runtime.execution.InferenceResult.Chat -> {
                insertAgentMessage(sessionId, inferenceResult.message, modelLabel, performance)
            }
            is com.mamba.picme.agent.core.runtime.execution.InferenceResult.Local -> {
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(inferenceResult.command, agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
            is com.mamba.picme.agent.core.runtime.execution.InferenceResult.Batch -> {
                val commands = inferenceResult.commands
                val finalCommand = if (commands.size > 1) {
                    AgentCommand.BatchExecute(commands = commands)
                } else {
                    commands.firstOrNull() ?: AgentCommand.TextReply(message = "没有可执行的命令")
                }
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(finalCommand, agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
            is com.mamba.picme.agent.core.runtime.execution.InferenceResult.Plan -> {
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(AgentCommand.ExecutePlan(plan = inferenceResult.plan), agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
        }
    }

    /**
     * 将 AgentAction 渲染为聊天消息
     */
    private suspend fun handleAgentAction(
        action: AgentAction?,
        sessionId: String,
        modelLabel: String,
        performance: LlmPerformance? = null
    ) {
        when (action) {
            is AgentAction.TextReply -> {
                insertAgentMessage(sessionId, action.message, modelLabel, performance)
            }
            is AgentAction.Success -> {
                insertAgentMessage(sessionId, describeCommandResult(action.command), "command", performance)
            }
            is AgentAction.Error -> {
                insertAgentMessage(sessionId, "❌ ${action.message}", "error", performance)
            }
            is AgentAction.BatchResult -> {
                val summary = action.results.joinToString("\n") { subAction ->
                    when (subAction) {
                        is AgentAction.Success -> describeCommandResult(subAction.command)
                        is AgentAction.Error -> "❌ ${subAction.message}"
                        is AgentAction.TextReply -> subAction.message
                        else -> ""
                    }
                }
                insertAgentMessage(sessionId, summary.ifBlank { "批量操作已完成" }, "command", performance)
            }
            null -> {
                insertAgentMessage(sessionId, "未获取到执行结果", "error", performance)
            }
        }
    }

    /**
     * 把命令执行结果转成用户友好的自然语言
     */
    private fun describeCommandResult(command: AgentCommand): String {
        return when (command) {
            is AgentCommand.NavigateTo -> "✅ 已切换到 ${command.destination}"
            is AgentCommand.GoBack -> "✅ 已返回上一页"
            is AgentCommand.LaunchApp -> {
                val target = command.appName ?: command.packageName ?: "应用"
                "✅ 已打开 $target"
            }
            is AgentCommand.OpenSystemSettings -> "✅ 已打开 ${command.setting} 设置"
            is AgentCommand.PerformAccessibilityAction -> {
                val target = command.target?.value?.let { "「$it」" } ?: ""
                "✅ 已执行 ${command.action} $target"
            }
            is AgentCommand.BatchExecute -> "✅ 已执行批量操作"
            else -> "✅ 已执行 ${AgentCommand.getMethodName(command)}"
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
     * 插入 AI 回复/命令结果到 Room
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

    private fun LlmGenerationMetrics.toLlmPerformance(): LlmPerformance {
        return LlmPerformance(
            promptLen = promptLen,
            decodeLen = decodeLen,
            prefillTimeMs = prefillTime / 1000,
            decodeTimeMs = decodeTime / 1000,
            prefillSpeed = prefillSpeed,
            decodeSpeed = decodeSpeed
        )
    }
}
