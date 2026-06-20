package com.mamba.picme.agent.core.react

import android.view.WindowManager
import com.mamba.android.MambaAgentFactory
import com.mamba.android.MambaChatHelper
import com.mamba.chat.ChatMemoryStore
import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessage
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.ToolExecutionResultMessage
import com.mamba.data.message.UserMessage
import com.mamba.memory.ChatMemory
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.request.ToolChoice
import com.mamba.model.openai.OpenAiChatRequestParameters
import com.mamba.model.chat.response.ChatResponse
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.DataStoreChatMemoryStore
import com.mamba.picme.agent.core.remote.tool.PicMeToolService
import com.mamba.picme.agent.core.remote.tool.ToolSpecificationExtractor
import com.mamba.tool.ToolExecutionRequest
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内 ReAct Agent 服务（简化版）。
 *
 * 使用 MambaAgentFactory 创建 ChatModel，MambaChatHelper 管理对话，
 * ChatMemory 自动维护对话历史，无需手动维护 ReAct 循环。
 *
 * 工具通过 PicMeToolService 的 @Tool 注解方法注册，
 * ToolSpecification 由 ToolSpecificationExtractor 从注解自动生成。
 */
class InAppAgentService(
    private val config: InAppAgentConfig,
    private val windowManager: WindowManager,
    private val callback: InAppAgentCallback,
    private val appContext: android.content.Context? = null
) {
    companion object {
        private const val TAG = "InAppAgent"
        private const val LOOP_DETECT_WINDOW = 4
    }

    private val toolService = PicMeToolService(windowManager)
    private val toolSpecs by lazy { ToolSpecificationExtractor.extract(toolService) }

    private val chatModel by lazy {
        val effectiveApiKey = config.apiKey.ifEmpty { "gateway-auth" }
        val builder = MambaAgentFactory.builder()
            .apiKey(effectiveApiKey)
            .baseUrl(config.baseUrl)
            .model(config.modelName)
            .temperature(config.temperature)
            .logRequests(true)
            .logResponses(true)

        config.gatewayToken?.let {
            builder.customHeader("X-Gateway-Token", it)
        }

        builder.build()
    }

    private val chatHelper by lazy { MambaChatHelper(chatModel) }
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    /** 每个 session 最多保留最近 10 轮对话（5 个 user+assistant 对） */
    private val maxMemoryMessages = 10

    /** 飞书 p2p 会话固定 session ID */
    private val feishuSessionId = "feishu_p2p"

    /** DataStore 持久化存储 */
    private val chatMemoryStore: ChatMemoryStore by lazy {
        val ctx = appContext
            ?: throw IllegalStateException("No context available for DataStoreChatMemoryStore")
        DataStoreChatMemoryStore(ctx)
    }

    /** sessionId → ChatMemory 缓存 */
    private val sessionMemories = mutableMapOf<String, ChatMemory>()

    /** 当前迭代计数（用于回调） */
    private var currentIteration = 0

    /** 死循环检测历史 */
    private val loopHistory = LinkedList<RoundFingerprint>()
    private var lastScreenHash = 0

    /** 当前回调（用于工具执行回调中访问） */
    private var currentCallback: InAppAgentCallback? = null

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

    fun initialize() {
        Logger.i(TAG, "ReAct Agent initialized: model=${config.modelName}, tools=${toolSpecs.size}")
    }

    fun executeTask(userPrompt: String, taskCallback: InAppAgentCallback? = null) {
        if (running.get()) {
            (taskCallback ?: callback).onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        currentIteration = 0
        loopHistory.clear()
        lastScreenHash = 0

        executor.submit {
            try {
                runAgentLoop(userPrompt, taskCallback)
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

    // ==================== ReAct 主循环（使用 MambaChatHelper + ChatMemory）====================

    private fun runAgentLoop(userPrompt: String, taskCallback: InAppAgentCallback? = null) {
        val cb = taskCallback ?: callback
        currentCallback = cb

        Logger.d(TAG, "runAgentLoop start: userPrompt='$userPrompt'")

        // 获取或创建 ChatMemory
        val memory = getOrCreateMemory(feishuSessionId)

        // 确保 ChatMemory 中有 SystemMessage
        val hasSystemMessage = memory.messages().any { it is SystemMessage }
        if (!hasSystemMessage) {
            memory.add(SystemMessage.from(config.systemPrompt))
            Logger.d(TAG, "Added SystemMessage to ChatMemory")
        }

        // 添加用户消息到 ChatMemory
        memory.add(UserMessage.from(userPrompt))
        Logger.d(TAG, "Added user message to ChatMemory: '$userPrompt'")

        var iteration = 0
        val maxIterations = config.maxIterations

        try {
            while (iteration < maxIterations && !cancelled.get()) {
                iteration++
                currentIteration = iteration
                cb.onLoopStart(iteration)
                Logger.d(TAG, "=== Iteration #$iteration ===")

                // 获取当前消息列表
                val messages = memory.messages()
                Logger.d(TAG, "Current message count: ${messages.size}")

                // 使用 ChatModel 直接调用 LLM（必须传入 toolSpecifications）
                Logger.d(TAG, "Calling LLM with tools, toolSpecs=${toolSpecs.size}")
                val request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecs)
                    .parameters(
                        OpenAiChatRequestParameters.builder()
                            .toolChoice(ToolChoice.AUTO)
                            .customParameters(mapOf("thinking" to mapOf("type" to "disabled")))
                            .build()
                    )
                    .build()

                // 打印 Request 详情（用于诊断）
                val requestLog = buildString {
                    appendLine("=== LLM Request ===")
                    appendLine("model=${config.modelName}, tools=${toolSpecs.size}")
                    messages.forEachIndexed { idx, msg ->
                        val type = msg::class.simpleName?.replace("Message", "") ?: "Unknown"
                        val text = when (msg) {
                            is UserMessage -> msg.singleText()
                            is SystemMessage -> msg.text()
                            is AiMessage -> msg.text()?.take(200) ?: ""
                            is ToolExecutionResultMessage -> msg.text().take(200)
                            else -> msg.toString().take(200)
                        }
                        appendLine("  [$idx] $type: $text")
                    }
                    appendLine("===================")
                }
                Logger.d(TAG, requestLog)

                val response = chatModel.chat(request)
                val aiMessage = response.aiMessage()

                // 打印 Response 详情（用于诊断）
                val responseLog = buildString {
                    appendLine("=== LLM Response ===")
                    appendLine("content='${aiMessage.text()?.take(200) ?: ""}'")
                    appendLine("hasToolCalls=${aiMessage.hasToolExecutionRequests()}")
                    val toolRequests = aiMessage.toolExecutionRequests()
                    if (toolRequests != null && toolRequests.isNotEmpty()) {
                        toolRequests.forEach { req ->
                            appendLine("  tool: ${req.name()}(${req.arguments()?.take(100) ?: ""})")
                        }
                    }
                    appendLine("====================")
                }
                Logger.d(TAG, responseLog)

                // 将 AI 响应添加到 ChatMemory
                memory.add(aiMessage)

                // 检查是否有工具调用请求
                val toolRequests = aiMessage.toolExecutionRequests()
                if (toolRequests == null || toolRequests.isEmpty()) {
                    // 没有工具调用，任务完成
                    val responseText = aiMessage.text() ?: "任务完成"
                    Logger.i(TAG, "Task complete (no tool calls), $iteration rounds")
                    cb.onComplete(iteration, responseText, 0)
                    break
                }

                // 处理工具调用
                for (request in toolRequests) {
                    val toolName = request.name()
                    val toolArgs = request.arguments() ?: "{}"
                    Logger.d(TAG, "Tool call: $toolName($toolArgs)")
                    cb.onToolCall(iteration, toolName, toolArgs)

                    // 执行工具
                    val toolResult = try {
                        executeTool(toolName, toolArgs)
                    } catch (e: Exception) {
                        Logger.e(TAG, "Tool execution error: $toolName", e)
                        "Tool execution failed: ${e.message}"
                    }

                    Logger.d(TAG, "Tool result: $toolName -> ${toolResult.take(100)}")
                    cb.onToolResult(iteration, toolName, toolResult)

                    // 构建 ToolExecutionResultMessage 并添加到 ChatMemory
                    val resultMessage = ToolExecutionResultMessage.builder()
                        .id(request.id())
                        .toolName(toolName)
                        .text(toolResult)
                        .build()
                    memory.add(resultMessage)

                    // 检查 finish 工具
                    if (toolName == "finish") {
                        Logger.i(TAG, "finish called, task complete")
                        cb.onComplete(iteration, toolResult, 0)
                        return
                    }

                    // 死循环检测
                    if (toolName == "get_screen_info") {
                        lastScreenHash = toolResult.hashCode()
                    } else {
                        loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                        if (loopHistory.size > LOOP_DETECT_WINDOW) {
                            loopHistory.removeFirst()
                        }
                    }

                    if (isStuckInLoop(loopHistory)) {
                        Logger.w(TAG, "Loop detected, forcing termination")
                        loopHistory.clear()
                        cb.onComplete(iteration, "Loop detected, task terminated", 0)
                        return
                    }
                }
            }

            // 达到最大迭代次数
            if (iteration >= maxIterations) {
                Logger.w(TAG, "Reached max iterations $maxIterations")
                cb.onComplete(iteration, "Reached max iterations, task terminated", 0)
            }
        } catch (e: Exception) {
            if (cancelled.get()) {
                Logger.d(TAG, "Task cancelled")
                cb.onComplete(iteration, "Task cancelled", 0)
            } else {
                Logger.e(TAG, "Agent execution failed", e)
                cb.onError(iteration, e, 0)
            }
        }

        Logger.d(TAG, "runAgentLoop end")
    }

    private fun executeTool(toolName: String, argsJson: String): String {
        return toolService.callTool(toolName, argsJson)
    }

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
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
    private val store: ChatMemoryStore,
    private val maxMessages: Int = 10
) : ChatMemory {

    override fun id(): Any = memoryId

    override fun messages(): MutableList<ChatMessage> {
        return store.getMessages(memoryId)
    }

    override fun add(message: ChatMessage) {
        val messages = store.getMessages(memoryId)
        messages.add(message)
        if (messages.size > maxMessages) {
            val trimmed = messages.takeLast(maxMessages).toMutableList()
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
    fun clearAndSet(messages: MutableList<ChatMessage>) {
        store.updateMessages(memoryId, messages)
    }
}
