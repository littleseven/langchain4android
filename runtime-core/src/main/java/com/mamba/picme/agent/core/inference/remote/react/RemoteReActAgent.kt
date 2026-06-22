package com.mamba.picme.agent.core.inference.remote.react

import android.view.WindowManager
import com.mamba.memory.ChatMemory
import com.mamba.model.chat.request.ToolChoice
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.DataStoreChatMemoryStore
import com.mamba.picme.agent.core.inference.remote.tool.PicMeToolService
import com.mamba.service.AiServices
import com.mamba.data.message.SystemMessage
import com.mamba.model.chat.listener.ChatModelListener
import com.mamba.model.chat.listener.ChatModelResponseContext
import com.mamba.model.output.TokenUsage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程 ReAct Agent（AiServices 版本）。
 *
 * <p>使用 AiServices 模式替代手动 ReAct loop：
 * <ul>
 *   <li>通过 {@link AiServices.Builder} 显式注入所有依赖</li>
 *   <li>工具调用由 AiServices 代理自动处理</li>
 *   <li>ChatMemory 自动维护对话历史</li>
 *   <li>无 SPI/ServiceLoader 依赖</li>
 * </ul>
 *
 * @see AiServices
 */
class RemoteReActAgent(
    private val config: RemoteReActAgentConfig,
    private val windowManager: WindowManager,
    private val callback: RemoteReActAgentCallback,
    private val appContext: android.content.Context? = null
) {
    companion object {
        private const val TAG = "RemoteReActAgent"
    }

    private val toolService = PicMeToolService(windowManager)

    private val chatModel by lazy {
        val remoteModelConfig = RemoteModelConfig(
            modelId = config.modelName,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            gatewayToken = config.gatewayToken ?: ""
        )

        val builder = RemoteModelFactory.createBuilder(remoteModelConfig)
            .logRequests(true)
            .logResponses(true)

        config.gatewayToken?.let {
            builder.customHeader("X-Gateway-Token", it)
        }

        builder.listeners(object : ChatModelListener {
            override fun onResponse(responseContext: ChatModelResponseContext) {
                val usage = responseContext.chatResponse().metadata()?.tokenUsage()
                if (usage != null) {
                    val current = accumulatedTokenUsage
                    accumulatedTokenUsage = if (current == null) {
                        usage
                    } else {
                        current.add(usage)
                    }
                    Logger.d(TAG, "Token usage accumulated: input=${usage.inputTokenCount()}, output=${usage.outputTokenCount()}, total=${usage.totalTokenCount()}")
                }
            }
        })

        builder.build()
    }

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    /** 记录每次执行的性能指标 */
    private var lastExecutionMetrics: AgentExecutionMetrics? = null

    /** 累计 Token 使用量（多轮工具调用时累加） */
    private var accumulatedTokenUsage: TokenUsage? = null

    /** 每个 session 最多保留最近 10 轮对话（5 个 user+assistant 对） */
    private val maxMemoryMessages = 10

    /** 飞书 p2p 会话固定 session ID */
    private val feishuSessionId = "feishu_p2p"

    /** DataStore 持久化存储 */
    private val chatMemoryStore by lazy {
        val ctx = appContext
            ?: throw IllegalStateException("No context available for DataStoreChatMemoryStore")
        DataStoreChatMemoryStore(ctx)
    }

    /** sessionId → ChatMemory 缓存 */
    private val sessionMemories = mutableMapOf<String, ChatMemory>()

    /** AiServices 代理缓存 */
    private var assistant: PicMeAssistant? = null

    /**
     * PicMe AI 助手接口契约（内联，避免单独文件）。
     */
    private interface PicMeAssistant {
        fun chat(message: String): String
    }

    /**
     * 获取或创建指定 session 的 ChatMemory
     */
    private fun getOrCreateMemory(sessionId: String): ChatMemory {
        return sessionMemories.getOrPut(sessionId) {
            DataStoreChatMemory(
                memoryId = sessionId,
                store = chatMemoryStore,
                maxMessages = maxMemoryMessages
            )
        }
    }

    /**
     * 获取或创建 PicMeAssistant（AiServices 代理）。
     */
    private fun getOrCreateAssistant(): PicMeAssistant {
        return assistant ?: run {
            val memory = getOrCreateMemory(feishuSessionId)
            val newAssistant = AiServices.builder(PicMeAssistant::class.java)
                .builder()
                .chatModel(chatModel)
                .chatMemory(memory)
                .tools(toolService)
                .systemMessageProvider { SystemMessage.from(config.systemPrompt) }
                .toolChoice(ToolChoice.AUTO)
                .maxIterations(config.maxIterations)
                .build()
            assistant = newAssistant
            newAssistant
        }
    }

    fun initialize() {
        Logger.i(TAG, "Remote ReAct Agent initialized: model=${config.modelName}")
    }

    /**
     * 获取最近一次执行的性能指标
     */
    fun getLastExecutionMetrics(): AgentExecutionMetrics? = lastExecutionMetrics

    fun executeTask(userPrompt: String, taskCallback: RemoteReActAgentCallback? = null) {
        if (running.get()) {
            (taskCallback ?: callback).onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        accumulatedTokenUsage = null

        executor.submit {
            try {
                runAgentWithAiServices(userPrompt, taskCallback)
            } catch (e: Exception) {
                Logger.e(TAG, "Agent execution error", e)
                (taskCallback ?: callback).onError(0, e, 0)
            } finally {
                running.set(false)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    fun isRunning(): Boolean = running.get()

    // ==================== AiServices 代理调用（替代手动 ReAct loop）====================

    private fun runAgentWithAiServices(userPrompt: String, taskCallback: RemoteReActAgentCallback? = null) {
        val cb = taskCallback ?: callback

        Logger.d(TAG, "runAgentWithAiServices start: userPrompt='$userPrompt'")
        cb.onLoopStart(1)

        val startTime = System.currentTimeMillis()

        try {
            // 获取 AiServices 代理（自动处理工具调用循环）
            val assistant = getOrCreateAssistant()

            // 调用 chat 方法，AiServices 内部自动处理：
            // 1. 添加 UserMessage 到 ChatMemory
            // 2. 调用 LLM 传入 toolSpecifications
            // 3. 如果返回 tool calls，自动执行工具并构建 ToolExecutionResultMessage
            // 4. 继续循环直到没有 tool calls 或达到 maxIterations
            val result = assistant.chat(userPrompt)

            val latencyMs = System.currentTimeMillis() - startTime
            val totalTokens = accumulatedTokenUsage
            val metrics = AgentExecutionMetrics(
                latencyMs = latencyMs,
                promptTokens = totalTokens?.inputTokenCount(),
                completionTokens = totalTokens?.outputTokenCount(),
                modelName = config.modelName
            )
            lastExecutionMetrics = metrics

            Logger.i(TAG, "Task complete: result='$result', latency=${latencyMs}ms, tokens=$totalTokens")
            cb.onComplete(1, result, totalTokens?.totalTokenCount() ?: 0, metrics)

        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            val metrics = AgentExecutionMetrics(
                latencyMs = latencyMs,
                promptTokens = accumulatedTokenUsage?.inputTokenCount(),
                completionTokens = accumulatedTokenUsage?.outputTokenCount(),
                modelName = config.modelName
            )
            lastExecutionMetrics = metrics

            if (cancelled.get()) {
                Logger.d(TAG, "Task cancelled")
                cb.onComplete(0, "Task cancelled", accumulatedTokenUsage?.totalTokenCount() ?: 0, metrics)
            } else {
                Logger.e(TAG, "Agent execution failed", e)
                cb.onError(0, e, accumulatedTokenUsage?.totalTokenCount() ?: 0, metrics)
            }
        }

        Logger.d(TAG, "runAgentWithAiServices end")
    }

    /**
     * 重置当前会话（清除 ChatMemory 和 Assistant）。
     * 用于开始新的对话或重置状态。
     */
    fun resetSession() {
        assistant = null
        sessionMemories[feishuSessionId]?.clear()
        Logger.d(TAG, "Session reset")
    }
}

/**
 * 基于 DataStore 的 ChatMemory 实现
 *
 * 实现 [com.mamba.memory.ChatMemory] 接口，
 * 使用 [DataStoreChatMemoryStore] 作为后端持久化器。
 * 支持最大消息数限制（滑动窗口）。
 *
 * @property memoryId 会话 ID
 * @property store DataStore 持久化器
 * @property maxMessages 最大消息数（超出时丢弃最早的消息）
 */
private class DataStoreChatMemory(
    private val memoryId: String,
    private val store: DataStoreChatMemoryStore,
    private val maxMessages: Int = 10
) : ChatMemory {

    override fun id(): Any = memoryId

    override fun messages(): MutableList<com.mamba.data.message.ChatMessage> {
        return store.getMessages(memoryId)
    }

    override fun add(message: com.mamba.data.message.ChatMessage) {
        val messages = store.getMessages(memoryId)

        // System message 必须始终位于对话开头
        if (message is com.mamba.data.message.SystemMessage) {
            // 如果已有 SystemMessage，先移除旧的再插入到开头
            val existingSystem = messages.indexOfFirst { it is com.mamba.data.message.SystemMessage }
            if (existingSystem >= 0) {
                messages.removeAt(existingSystem)
            }
            messages.add(0, message)
        } else {
            messages.add(message)
        }

        if (messages.size > maxMessages) {
            // 保留 SystemMessage（如果存在）确保对话结构有效
            val systemMsg = messages.filterIsInstance<com.mamba.data.message.SystemMessage>().firstOrNull()
            val trimmed = messages.takeLast(maxMessages).toMutableList()
            if (systemMsg != null && trimmed.firstOrNull() !is com.mamba.data.message.SystemMessage) {
                trimmed.add(0, systemMsg)
            }

            // 清理孤立的 tool result：删除找不到对应 assistant tool_calls 的 ToolExecutionResultMessage
            val assistantToolCallIds = trimmed.filterIsInstance<com.mamba.data.message.AiMessage>()
                .filter { it.hasToolExecutionRequests() }
                .flatMap { it.toolExecutionRequests() }
                .map { it.id() }
                .toSet()
            trimmed.removeAll { msg ->
                msg is com.mamba.data.message.ToolExecutionResultMessage &&
                    msg.id() != null &&
                    msg.id() !in assistantToolCallIds
            }

            store.updateMessages(memoryId, trimmed)
        } else {
            store.updateMessages(memoryId, messages)
        }
    }

    override fun clear() {
        store.deleteMessages(memoryId)
    }

    /**
     * 清空并重新设置消息列表（用于过滤历史消息）
     */
    fun clearAndSet(messages: MutableList<com.mamba.data.message.ChatMessage>) {
        store.updateMessages(memoryId, messages)
    }
}
