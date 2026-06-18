package com.mamba.picme.agent.core.react

import android.util.Log
import android.view.WindowManager
import com.mamba.picme.agent.core.platform.storage.DataStoreChatMemoryStore
import com.mamba.picme.agent.core.react.llm.InAppLlmClient
import com.mamba.picme.agent.core.react.llm.LangChain4jToolBridge
import com.mamba.picme.agent.core.react.tool.ToolRegistry
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.store.memory.chat.ChatMemoryStore
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内 ReAct Agent 服务。
 * 参照 ApkClaw DefaultAgentService 实现，但：
 * - 无障碍服务 → in-app View 树遍历
 * - 跨应用 UI 操作 → 应用内 performClick/setText/smoothScroll
 * - 保留完整的 LangChain4j 集成模式
 */
class InAppAgentService(
    private val config: InAppAgentConfig,
    private val windowManager: WindowManager,
    private val callback: InAppAgentCallback,
    private val appContext: android.content.Context? = null
) {
    companion object {
        private const val TAG = "InAppAgent"
        private const val MAX_API_RETRIES = 3
        private const val LOOP_DETECT_WINDOW = 4
        private const val KEEP_RECENT_ROUNDS = 3

        private val OBSERVATION_PLACEHOLDERS = mapOf(
            "get_screen_info" to "[屏幕信息已省略]"
        )
    }

    private val llmClient = InAppLlmClient(
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        modelName = config.modelName,
        temperature = config.temperature,
        gatewayToken = config.gatewayToken
    )
    private var toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
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
        ToolRegistry.getInstance().registerAllTools(windowManager)
        toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        Log.i(TAG, "ReAct Agent initialized: model=${config.modelName}, tools=${toolSpecs.size}")
    }

    fun executeTask(userPrompt: String, taskCallback: InAppAgentCallback? = null) {
        if (running.get()) {
            (taskCallback ?: callback).onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)

        executor.submit {
            try {
                runAgentLoop(userPrompt, taskCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution error", e)
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

    // ==================== ReAct 主循环 ====================

    private fun runAgentLoop(userPrompt: String, taskCallback: InAppAgentCallback? = null) {
        val cb = taskCallback ?: callback
        val memory = getOrCreateMemory(feishuSessionId)
        val messages = mutableListOf<ChatMessage>()

        // 加载持久化历史
        val historyMessages = memory.messages()
        if (historyMessages.isNotEmpty()) {
            Log.d(TAG, "加载历史消息: ${historyMessages.size} 条")
            messages.addAll(historyMessages)
        }

        messages.add(SystemMessage.from(config.systemPrompt))
        messages.add(UserMessage.from(userPrompt))

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            cb.onLoopStart(iterations)
            Log.d(TAG, "=== 迭代 #$iterations ===")

            // 1. 压缩历史（节省 token）
            compressHistoryForSend(messages)

            // 2. LLM 调用（带重试）
            val llmResponse: InAppLlmClient.LlmResponse
            try {
                llmResponse = chatWithRetry(messages, iterations)
            } catch (e: Exception) {
                Log.e(TAG, "LLM API call failed after retries", e)
                cb.onError(iterations, e, totalTokens)
                return
            }

            totalTokens += llmResponse.totalTokens

            // 3. 构造 AiMessage 添加到历史
            val aiMessage = if (llmResponse.toolExecutionRequests.isNotEmpty()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // 4. 推送思考内容（仅当 text 非空且有实质内容时）
            if (!llmResponse.text.isNullOrBlank()) {
                cb.onContent(iterations, llmResponse.text)
                Log.d(TAG, "思考: ${llmResponse.text.take(200)}")
            }

            // 5. 如果没有工具调用 → 任务完成，保存对话历史
            if (llmResponse.toolExecutionRequests.isEmpty()) {
                cb.onComplete(iterations, llmResponse.text ?: "任务完成", totalTokens)
                Log.i(TAG, "任务完成（无工具调用），共 $iterations 轮，$totalTokens tokens")
                saveConversationToMemory(messages)
                return
            }

            // 6. 执行工具调用
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    cb.onComplete(iterations, "任务已取消", totalTokens)
                    saveConversationToMemory(messages)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val toolArgs = toolRequest.arguments() ?: "{}"

                cb.onToolCall(iterations, toolName, toolArgs)
                Log.d(TAG, "工具调用: $toolName(${toolArgs.take(100)})")

                // 执行
                val resultJson = LangChain4jToolBridge.executeToolRequest(toolRequest)
                cb.onToolResult(iterations, toolName, resultJson)

                // finish 工具 → 任务完成
                if (toolName == "finish") {
                    val finishData = try {
                        org.json.JSONObject(resultJson).optString("data", "任务完成")
                    } catch (_: Exception) { "任务完成" }
                    cb.onComplete(iterations, finishData, totalTokens)
                    Log.i(TAG, "finish 调用，任务结束，共 $iterations 轮，$totalTokens tokens")
                    saveConversationToMemory(messages)
                    return
                }

                // 记录指纹用于死循环检测
                if (toolName == "get_screen_info") {
                    lastScreenHash = resultJson.hashCode()
                } else {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) {
                        loopHistory.removeFirst()
                    }
                }

                // 添加工具结果到历史
                messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))
            }

            // 7. 死循环检测
            if (isStuckInLoop(loopHistory)) {
                Log.w(TAG, "检测到死循环，注入换策略提示")
                messages.add(
                    UserMessage.from(
                        "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                        "请尝试完全不同的方法：返回上级页面重新操作，或换个方式寻找目标。" +
                        "如果确实无法完成任务，请调用 finish 说明原因。"
                    )
                )
                loopHistory.clear()
            }
        }

        // 超过最大迭代次数或取消，保存对话历史
        saveConversationToMemory(messages)
        if (cancelled.get()) {
            cb.onComplete(iterations, "任务已取消", totalTokens)
        } else {
            cb.onError(iterations, RuntimeException("已达最大迭代次数 ($maxIterations)"), totalTokens)
        }
    }

    /**
     * 将对话历史保存到 DataStoreChatMemory
     */
    private fun saveConversationToMemory(messages: List<ChatMessage>) {
        try {
            val memory = getOrCreateMemory(feishuSessionId)
            // 清空旧历史，写入完整新历史（避免重复追加）
            memory.clear()
            for (msg in messages) {
                memory.add(msg)
            }
            Log.d(TAG, "对话历史已保存: ${messages.size} 条消息")
        } catch (e: Exception) {
            Log.w(TAG, "保存对话历史失败", e)
        }
    }

    // ==================== LLM 调用（带重试+指数退避） ====================

    private fun chatWithRetry(messages: List<ChatMessage>, iteration: Int): InAppLlmClient.LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException("任务已取消")
            try {
                return llmClient.chat(messages, toolSpecs)
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Token 耗尽或认证失败不重试
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                Log.w(TAG, "LLM 调用失败 (${attempt + 1}/$MAX_API_RETRIES)，${delay}ms 后重试: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** 大输出观察类工具 → 压缩后占位符 */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        val charsBefore = messages.sumOf { msg -> messageLength(msg) }
        val msgCountBefore = messages.size

        // get_screen_info 特殊处理：全局只保留最新一条完整结果
        val placeholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != placeholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            }
        }

        // 保护区外的 ToolResult 压缩
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size
        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break

            val aiIndex = aiIndices[roundIdx]
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                val msg = messages[j] as ToolExecutionResultMessage
                val text = msg.text()
                if (text.length > 100) {
                    val ph = OBSERVATION_PLACEHOLDERS[msg.toolName()]
                    if (ph != null) {
                        messages[j] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), ph)
                    } else {
                        messages[j] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(),
                            if (text.length > 80) text.take(80) + "..." else text)
                    }
                }
                j++
            }
        }

        val charsAfter = messages.sumOf { msg -> messageLength(msg) }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            Log.d(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符, 消息数=$msgCountBefore")
        }
    }

    private fun messageLength(msg: ChatMessage): Int {
        return when (msg) {
            is AiMessage -> (msg.text()?.length ?: 0) +
                (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
            is ToolExecutionResultMessage -> msg.text().length
            is UserMessage -> msg.singleText().length
            is SystemMessage -> msg.text().length
            else -> 0
        }
    }
}

/**
 * 基于 DataStore 的 ChatMemory 实现
 *
 * 实现 [dev.langchain4j.memory.ChatMemory] 接口，
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
}
