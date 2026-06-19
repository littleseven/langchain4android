package com.mamba.picme.agent.core.react

import android.util.Log
import android.view.WindowManager
import com.mamba.picme.agent.core.platform.storage.DataStoreChatMemoryStore
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.android.RemoteProtocol
import com.mamba.picme.agent.core.platform.llm.remote.LangChain4jOpenAiClient
import com.mamba.picme.agent.core.react.llm.LangChain4jToolBridge
import com.mamba.picme.agent.core.react.tool.InAppToolSet
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage as DataSystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage as DataUserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters
import dev.langchain4j.store.memory.chat.ChatMemoryStore
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内 ReAct Agent 服务（手动实现 ReAct 循环）。
 *
 * 绕过 LangChain4j AiServices.builder() 的 ServiceLoader 阻塞问题，
 * 直接调用 ChatModel.doChat() 并手动管理 tool calling 轮次。
 *
 * 功能：
 * - 手动管理 tool calling 轮次
 * - 自动将工具结果返回给 LLM
 * - 支持最大迭代次数限制
 * - 死循环检测
 * - 直接意图映射（fallback）
 * - DataStore 持久化 ChatMemory
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

    private val llmClient = LangChain4jOpenAiClient(
        RemoteModelConfig(
            modelId = config.modelName,
            protocol = RemoteProtocol.OPENAI,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            gatewayToken = config.gatewayToken ?: ""
        )
    )
    private val toolSet = InAppToolSet(windowManager)
    private val toolSpecs = LangChain4jToolBridge.buildToolSpecifications(toolSet)
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
        Log.i(TAG, "ReAct Agent initialized: model=${config.modelName}, tools=${toolSpecs.size}")
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
                runAgentLoopWithAiServices(userPrompt, taskCallback)
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

    // ==================== 手动 ReAct 主循环（绕过 AiServices.builder() 的 ServiceLoader 阻塞）====================

    private fun runAgentLoopWithAiServices(userPrompt: String, taskCallback: InAppAgentCallback? = null) {
        val cb = taskCallback ?: callback
        currentCallback = cb

        Log.d(TAG, "runAgentLoopWithAiServices 开始: userPrompt='$userPrompt'")

        // 获取或创建 ChatMemory
        val memory = getOrCreateMemory(feishuSessionId)

        // 确保 ChatMemory 中有 SystemMessage
        val hasSystemMessage = memory.messages().any { it is DataSystemMessage }
        if (!hasSystemMessage) {
            memory.add(DataSystemMessage(config.systemPrompt))
            Log.d(TAG, "添加 SystemMessage 到 ChatMemory")
        }

        // 添加用户消息到 ChatMemory
        memory.add(DataUserMessage(userPrompt))
        Log.d(TAG, "添加用户消息到 ChatMemory: '$userPrompt'")

        // 手动 ReAct 循环（绕过 AiServices.builder() 的 ServiceLoader 阻塞问题）
        var iteration = 0
        val maxIterations = config.maxIterations

        try {
            while (iteration < maxIterations && !cancelled.get()) {
                iteration++
                currentIteration = iteration
                cb.onLoopStart(iteration)
                Log.d(TAG, "=== 迭代 #$iteration ===")

                // 获取当前消息列表
                val messages = memory.messages()
                Log.d(TAG, "当前消息数: ${messages.size}")

                // 构建 ChatRequest
                val chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecs)
                    .parameters(
                        DefaultChatRequestParameters.builder()
                            .modelName(config.modelName)
                            .temperature(0.7)
                            .build()
                    )
                    .build()

                Log.d(TAG, "调用 LLM chat(), toolSpecs=${toolSpecs.size}")

                // 调用 LLM
                val chatResponse = llmClient.doChat(chatRequest)
                val aiMessage = chatResponse.aiMessage()

                Log.d(TAG, "LLM 响应: content='${aiMessage.text().take(100)}', hasTools=${aiMessage.hasToolExecutionRequests()}")

                // 将 AI 响应添加到 ChatMemory
                memory.add(aiMessage)

                // 检查是否有工具调用请求
                val toolRequests = aiMessage.toolExecutionRequests()
                if (toolRequests == null || toolRequests.isEmpty()) {
                    // 没有工具调用，任务完成
                    val response = aiMessage.text() ?: "任务完成"
                    Log.i(TAG, "任务完成（无工具调用），共 $iteration 轮")
                    cb.onComplete(iteration, response, 0)
                    break
                }

                // 处理工具调用
                for (request in toolRequests) {
                    val toolName = request.name()
                    val toolArgs = request.arguments() ?: "{}"
                    Log.d(TAG, "工具调用: $toolName($toolArgs)")
                    cb.onToolCall(iteration, toolName, toolArgs)

                    // 执行工具
                    val toolResult = try {
                        LangChain4jToolBridge.executeToolRequest(request)
                    } catch (e: Exception) {
                        Log.e(TAG, "工具执行错误: $toolName", e)
                        "工具执行失败: ${e.message}"
                    }

                    Log.d(TAG, "工具结果: $toolName -> ${toolResult.take(100)}")
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
                        val finishData = try {
                            org.json.JSONObject(toolResult).optString("data", "任务完成")
                        } catch (_: Exception) { "任务完成" }
                        Log.i(TAG, "finish 调用，任务结束")
                        cb.onComplete(iteration, finishData, 0)
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
                        Log.w(TAG, "检测到死循环，强制终止")
                        loopHistory.clear()
                        cb.onComplete(iteration, "检测到死循环，任务终止", 0)
                        return
                    }
                }
            }

            // 达到最大迭代次数
            if (iteration >= maxIterations) {
                Log.w(TAG, "达到最大迭代次数 $maxIterations")
                cb.onComplete(iteration, "达到最大迭代次数，任务终止", 0)
            }
        } catch (e: Exception) {
            if (cancelled.get()) {
                Log.d(TAG, "任务被取消")
                cb.onComplete(iteration, "任务已取消", 0)
            } else {
                Log.e(TAG, "Agent 执行失败", e)
                cb.onError(iteration, e, 0)
            }
        }

        Log.d(TAG, "runAgentLoopWithAiServices 结束")
    }

    // ==================== 直接意图映射（LLM 不调用工具时的 Fallback）====================

    private data class DirectTool(val name: String, val args: String)

    /**
     * 根据用户输入直接解析意图并映射到工具。
     * 当 LLM 不发起 function calling 时，作为 fallback 直接执行。
     */
    private fun resolveDirectTool(userPrompt: String): DirectTool? {
        val prompt = userPrompt.trim().lowercase()

        // 导航类意图
        return when {
            // 打开相机
            prompt.contains("打开相机") || prompt.contains("相机") || prompt.contains("拍照") ||
            prompt.contains("打开摄像头") || prompt.contains("摄像") ->
                DirectTool("navigate_to", "{\"destination\":\"camera\"}")

            // 打开相册
            prompt.contains("打开相册") || prompt.contains("相册") || prompt.contains("照片") ||
            prompt.contains("图片") || prompt.contains("查看照片") ->
                DirectTool("navigate_to", "{\"destination\":\"gallery\"}")

            // 打开设置
            prompt.contains("打开设置") || prompt.contains("设置") || prompt.contains("配置") ->
                DirectTool("navigate_to", "{\"destination\":\"settings\"}")

            // 打开调试
            prompt.contains("打开调试") || prompt.contains("调试") || prompt.contains("debug") ->
                DirectTool("navigate_to", "{\"destination\":\"debug\"}")

            // 返回/后退
            prompt.contains("返回") || prompt.contains("后退") || prompt.contains("go back") ||
            prompt.contains("回去") ->
                DirectTool("go_back", "{}")

            // 拍照
            prompt.contains("拍") || prompt.contains("咔嚓") || prompt.contains("capture") ->
                DirectTool("capture", "{}")

            // 翻转摄像头
            prompt.contains("翻转") || prompt.contains("切换摄像头") || prompt.contains("前后") ||
            prompt.contains("自拍") ->
                DirectTool("flip_camera", "{}")

            // 录像
            prompt.contains("录像") || prompt.contains("录制") || prompt.contains("录视频") ||
            prompt.contains("开始录") || prompt.contains("停止录") ->
                DirectTool("toggle_recording", "{}")

            // 切换模式
            prompt.contains("拍照模式") || prompt.contains("照片模式") ->
                DirectTool("switch_mode", "{\"mode\":\"PHOTO\"}")
            prompt.contains("录像模式") || prompt.contains("视频模式") ->
                DirectTool("switch_mode", "{\"mode\":\"VIDEO\"}")
            prompt.contains("专业模式") || prompt.contains("pro模式") ->
                DirectTool("switch_mode", "{\"mode\":\"PRO\"}")
            prompt.contains("文档模式") ->
                DirectTool("switch_mode", "{\"mode\":\"DOCUMENT\"}")

            // 切换滤镜（暖色/冷色等简单描述）
            prompt.contains("暖色") || prompt.contains("暖色调") || prompt.contains("warm") ->
                DirectTool("switch_filter", "{\"filter\":\"WARM\"}")
            prompt.contains("冷色") || prompt.contains("冷色调") || prompt.contains("cool") ->
                DirectTool("switch_filter", "{\"filter\":\"COOL\"}")
            prompt.contains("黑白") || prompt.contains("黑白滤镜") ->
                DirectTool("switch_filter", "{\"filter\":\"LEICA_BW\"}")
            prompt.contains("复古") || prompt.contains(" vintage") ->
                DirectTool("switch_filter", "{\"filter\":\"VINTAGE\"}")
            prompt.contains("徕卡") || prompt.contains("leica") ->
                DirectTool("switch_filter", "{\"filter\":\"LEICA_CLASSIC\"}")
            prompt.contains("无滤镜") || prompt.contains("关闭滤镜") || prompt.contains("去掉滤镜") ->
                DirectTool("switch_filter", "{\"filter\":\"NONE\"}")

            // 切换场景
            prompt.contains("夜景") || prompt.contains("夜晚") ->
                DirectTool("switch_scene", "{\"scene\":\"night\"}")
            prompt.contains("月亮") || prompt.contains("月球") || prompt.contains("moon") ->
                DirectTool("switch_scene", "{\"scene\":\"moon\"}")
            prompt.contains("普通模式") || prompt.contains("标准模式") ->
                DirectTool("switch_scene", "{\"scene\":\"none\"}")

            // 切换比例
            prompt.contains("4:3") || prompt.contains("四比三") ->
                DirectTool("switch_ratio", "{\"ratio\":\"4:3\"}")
            prompt.contains("16:9") || prompt.contains("十六比九") ->
                DirectTool("switch_ratio", "{\"ratio\":\"16:9\"}")
            prompt.contains("全屏") || prompt.contains("全面屏") ->
                DirectTool("switch_ratio", "{\"ratio\":\"full\"}")

            // 美颜相关
            prompt.contains("磨皮") || prompt.contains("美肤") ->
                DirectTool("adjust_beauty", "{\"smoothing\":80}")
            prompt.contains("美白") ->
                DirectTool("adjust_beauty", "{\"whitening\":80}")
            prompt.contains("瘦脸") ->
                DirectTool("adjust_beauty", "{\"slim_face\":30}")
            prompt.contains("大眼") ->
                DirectTool("adjust_beauty", "{\"big_eyes\":50}")
            prompt.contains("重置美颜") || prompt.contains("关闭美颜") || prompt.contains("去掉美颜") ->
                DirectTool("adjust_beauty", "{\"smoothing\":0,\"whitening\":0,\"slim_face\":0,\"big_eyes\":0,\"lip_color\":0,\"blush\":0,\"eyebrow\":0}")

            // 变焦
            prompt.contains("放大") || prompt.contains("zoom in") || prompt.contains("拉近") ->
                DirectTool("adjust_zoom", "{\"zoom\":2.0}")
            prompt.contains("缩小") || prompt.contains("zoom out") || prompt.contains("拉远") ->
                DirectTool("adjust_zoom", "{\"zoom\":1.0}")

            // 曝光
            prompt.contains("曝光") || prompt.contains("亮度") ->
                DirectTool("adjust_exposure", "{\"exposure\":0.5}")

            // 无法直接映射
            else -> null
        }
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

    /**
     * 清空并重新设置消息列表（用于过滤历史消息）
     */
    fun clearAndSet(messages: MutableList<ChatMessage>) {
        store.updateMessages(memoryId, messages)
    }
}
