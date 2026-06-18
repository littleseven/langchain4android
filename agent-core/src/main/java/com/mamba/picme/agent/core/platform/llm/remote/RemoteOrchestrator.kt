package com.mamba.picme.agent.core.platform.llm.remote

import android.content.Context
import com.mamba.picme.agent.core.api.execution.ExecutionPlan
import com.mamba.picme.agent.core.api.execution.PlanStep
import com.mamba.picme.agent.core.api.execution.WaitCondition
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.remote.prompt.RemotePromptBuilder
import com.mamba.picme.agent.core.remote.parser.ToolCallCommandParser
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.api.context.MediaType
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.LlmChatLanguageModel
import com.mamba.picme.agent.core.api.LlmChatRequest
import com.mamba.picme.agent.core.api.LlmChatResponse
import com.mamba.picme.agent.core.api.ChatResponseMetadata
import com.mamba.picme.agent.core.api.StreamingLlmChatLanguageModel
import com.mamba.picme.agent.core.api.StreamingChatResponseHandler
import com.mamba.picme.agent.core.api.ToolExecutionRequest
import com.mamba.picme.agent.core.api.ToolSpecification
import com.mamba.picme.agent.core.api.ToolParameters
import com.mamba.picme.agent.core.api.JsonSchemaProperty
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import org.json.JSONArray
import org.json.JSONObject
import com.mamba.picme.agent.core.platform.storage.DataStoreChatMemoryStore
import dev.langchain4j.data.message.AiMessage as LcAiMessage
import dev.langchain4j.data.message.ChatMessage as LcChatMessage
import dev.langchain4j.data.message.SystemMessage as LcSystemMessage
import dev.langchain4j.data.message.UserMessage as LcUserMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage as LcToolExecutionResultMessage
import dev.langchain4j.memory.ChatMemory
// MessageWindowChatMemory 不在 langchain4j-core 1.12.2 中，使用自定义 DataStoreChatMemory 实现
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 远程 LLM 推理参数配置
 * 从 assets/remote_llm_config.json 加载，支持按层级覆盖
 */
object RemoteLlmConfig {
    private var config: JSONObject? = null

    data class Config(
        val l2Temperature: Double = 0.1,
        val l2MaxTokens: Int = 2048,
        val l3Temperature: Double = 0.3,
        val l3MaxTokens: Int = 2048,
        val l4Temperature: Double = 0.7,
        val l4MaxTokens: Int = 2048
    )

    fun load(context: Context): Config {
        if (config == null) {
            try {
                val json = context.assets.open("remote_llm_config.json")
                    .bufferedReader().use { it.readText() }
                config = JSONObject(json)
                Logger.i("RemoteLlmConfig", "Loaded remote_llm_config.json")
            } catch (e: Exception) {
                Logger.w("RemoteLlmConfig", "remote_llm_config.json not found, using defaults")
                config = JSONObject()
            }
        }

        val root = config ?: JSONObject()
        return Config(
            l2Temperature = root.optDouble("l2_temperature", 0.1),
            l2MaxTokens = root.optInt("l2_max_tokens", 2048),
            l3Temperature = root.optDouble("l3_temperature", 0.3),
            l3MaxTokens = root.optInt("l3_max_tokens", 2048),
            l4Temperature = root.optDouble("l4_temperature", 0.7),
            l4MaxTokens = root.optInt("l4_max_tokens", 2048)
        )
    }
}

/**
 * 远程编排器
 *
 * 负责执行 L2 Batch FC、L3 Plan-and-Execute、L4 Chat 三种远程推理模式。
 * 通过 [UnifiedRemoteClient] 自动适配 Claude/OpenAI 协议。
 *
 * **线程模型**：
 * - **网络线程**（PicMe-Network-Thread）：由 [ThreadPoolManager] 集中管理的专用单线程，
 *   负责所有 HTTP API 调用。与编排线程、DataStore 线程和 LLM 推理线程完全隔离。
 * - 同步 HTTP 调用（OkHttp `execute()` / Retrofit）在此网络线程上执行，
 *   不阻塞编排线程。
 * - 异步 HTTP 调用（OkHttp `enqueue()` 流式）由 OkHttp 内部线程池处理。
 *
 * @param context Application Context，用于加载本地配置
 * @param remoteConfig 远程模型配置
 * @param promptBuilder Prompt 构建器
 */
class RemoteOrchestrator(
    private val context: Context,
    private val remoteConfig: RemoteModelConfig,
    private val promptBuilder: RemotePromptBuilder,
    val chatLanguageModel: LlmChatLanguageModel = UnifiedRemoteClient(remoteConfig)
) {

    private val tag = "RemoteOrchestrator"

    private val networkDispatcher = ThreadPoolManager.getInstance().networkDispatcher
    private val chatMemoryStore = DataStoreChatMemoryStore(context)

    /**
     * 每个 session 最多保留最近 10 轮对话（5 个 user+assistant 对）
     */
    private val maxMemoryMessages = 10

    /**
     * sessionId → ChatMemory 缓存
     */
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

    /**
     * 将 langchain4j 的 ChatMessage 列表转换为标准格式
     */
    private fun normalizeMessages(lcMessages: List<LcChatMessage>): List<LcChatMessage> {
        return lcMessages
    }

    /**
     * 从 ChatMemory 构建带历史上下文的 messages 列表
     * 格式：[System, History..., User]
     */
    private fun buildMessagesWithHistory(
        systemPrompt: String,
        userInput: String,
        sessionId: String
    ): List<LcChatMessage> {
        val memory = getOrCreateMemory(sessionId)
        val historyMessages = memory.messages()
        
        val messages = mutableListOf<LcChatMessage>()
        messages.add(LcSystemMessage.from(systemPrompt))
        if (historyMessages.isNotEmpty()) {
            Logger.d(tag, "Inserting ${historyMessages.size} history messages from ChatMemory (session=$sessionId)")
            messages.addAll(historyMessages)
        }
        messages.add(LcUserMessage.from(userInput))
        return messages
    }

    /**
     * 将用户输入和助手回复保存到 ChatMemory
     */
    private fun saveToMemory(sessionId: String, userInput: String, assistantResponse: String) {
        if (assistantResponse.isBlank()) return
        val memory = getOrCreateMemory(sessionId)
        memory.add(LcUserMessage.from(userInput))
        memory.add(LcAiMessage.from(assistantResponse))
        Logger.d(tag, "Saved exchange to ChatMemory (session=$sessionId)")
    }

    /**
     * 流式聊天语言模型（兼容层）
     */
    private val streamingChatModel: StreamingLlmChatLanguageModel
        get() = chatLanguageModel as? StreamingLlmChatLanguageModel
            ?: error("StreamingLlmChatLanguageModel not supported")

    /**
     * 远程推理参数配置
     * 从本地 JSON 文件加载，支持按层级覆盖
     */
    private val remoteLlmConfig by lazy {
        RemoteLlmConfig.load(context)
    }

    // ── L2: Batch Function Calling ─────────────────────────────

    /**
     * L2 批量命令解析
     *
     * 将用户输入解析为命令数组，支持单指令和多指令。
     * 输出格式为 JSON 数组，每个元素映射为一个 [AgentCommand]。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @return 解析后的批量命令结果
     */
    suspend fun processBatch(
        userInput: String,
        context: AgentContext
    ): InferenceResult.Batch {
        val startTime = System.currentTimeMillis()
        try {
            val systemPrompt = promptBuilder.buildBatchPrompt(userInput, context)
    
            Logger.d(tag, "[L2-BATCH] REQ: input=\"$userInput\", model=${remoteConfig.modelId}")
    
            val chatRequest = LlmChatRequest(
                messages = buildMessagesWithHistory(systemPrompt, userInput, context.memorySessionId),
                toolSpecifications = buildL2ToolSpecifications(),
                temperature = remoteLlmConfig.l2Temperature,
                maxTokens = remoteLlmConfig.l2MaxTokens
            )

            val response = try {
                withContext(networkDispatcher) {
                    chatLanguageModel.chat(chatRequest)
                }
            } catch (error: Exception) {
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L2-BATCH] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return InferenceResult.Batch(
                    commands = listOf(AgentCommand.Error(reason = "Batch API error: ${error.message}"))
                )
            }

            val latencyMs = System.currentTimeMillis() - startTime
            
            // 优先使用 tool_calls（标准 OpenAI 协议）
            val toolRequests = response.aiMessage.toolExecutionRequests().map {
                com.mamba.picme.agent.core.api.ToolExecutionRequest(
                    id = it.id(),
                    name = it.name(),
                    arguments = it.arguments()
                )
            }
            if (toolRequests.isNotEmpty()) {
                val commands = parseToolCalls(toolRequests, context)
                Logger.d(
                    tag,
                    "[L2-BATCH] tool_calls OK latency=${latencyMs}ms, ${toolRequests.size} calls -> ${commands.size} commands"
                )
                // 保存 user input 到 ChatMemory（tool_calls 无文本回复也保留交互记录）
                saveToMemory(context.memorySessionId, userInput, "[tool_calls: ${toolRequests.size}]")
                return InferenceResult.Batch(commands = commands)
            }
            
            // 没有 tool_calls：尝试从文本内容中回退解析 tool_calls JSON
            val textContent = response.aiMessage.text()
            if (textContent.isNotBlank()) {
                val fallbackCommands = parseFallbackToolCalls(textContent, context)
                if (fallbackCommands.isNotEmpty()) {
                    Logger.w(tag, "[L2-BATCH] tool_calls missing in API response, fallback parsed ${fallbackCommands.size} commands from content")
                    saveToMemory(context.memorySessionId, userInput, textContent)
                    return InferenceResult.Batch(commands = fallbackCommands)
                }
                Logger.w(tag, "[L2-BATCH] RSP: latency=${latencyMs}ms, content=\"$textContent\", treating as text reply")
            } else {
                Logger.w(tag, "[L2-BATCH] RSP: latency=${latencyMs}ms, content is blank AND no tool_calls — possible proxy/cors stripping")
            }
            saveToMemory(context.memorySessionId, userInput, textContent.ifBlank { "收到，有什么其他需要帮忙的吗？" })
            return InferenceResult.Batch(
                commands = listOf(
                    AgentCommand.TextReply(
                        message = textContent.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                    )
                )
            )
        } catch (exception: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L2-BATCH] ERR: latency=${latencyMs}ms, ${exception.message}", exception)
            return InferenceResult.Batch(
                commands = listOf(AgentCommand.Error(reason = "Batch parsing error: ${exception.message}"))
            )
        }
    }

    // ── L3: Plan-and-Execute ───────────────────────────────────

    /**
     * L3 生成执行计划
     *
     * 将用户输入解析为结构化执行计划，支持条件判断和多步骤编排。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @return 解析后的计划结果
     */
    suspend fun processPlan(
        userInput: String,
        context: AgentContext
    ): InferenceResult.Plan {
        val startTime = System.currentTimeMillis()
        try {
            val systemPrompt = promptBuilder.buildPlanPrompt(userInput, context)
    
            Logger.d(tag, "[L3-PLAN] REQ: input=\"$userInput\", model=${remoteConfig.modelId}")
    
            val chatRequest = LlmChatRequest(
                messages = buildMessagesWithHistory(systemPrompt, userInput, context.memorySessionId),
                temperature = remoteLlmConfig.l3Temperature,
                maxTokens = remoteLlmConfig.l3MaxTokens
            )

            val content = try {
                withContext(networkDispatcher) {
                    chatLanguageModel.chat(chatRequest)
                }.aiMessage.text()
            } catch (error: Exception) {
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L3-PLAN] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return InferenceResult.Plan(
                    plan = ExecutionPlan(
                        planId = "error_${System.currentTimeMillis()}",
                        steps = emptyList(),
                        description = "Plan API error: ${error.message}"
                    )
                )
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L3-PLAN] RSP: latency=${latencyMs}ms, content=\"$content\"")

            val plan = parseExecutionPlan(content, context)
            Logger.d(tag, "[L3-PLAN] parsed plan with ${plan.steps.size} steps")
            saveToMemory(context.memorySessionId, userInput, content)
            return InferenceResult.Plan(plan = plan)
        } catch (exception: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L3-PLAN] ERR: latency=${latencyMs}ms, ${exception.message}", exception)
            return InferenceResult.Plan(
                plan = ExecutionPlan(
                    planId = "error_${System.currentTimeMillis()}",
                    steps = emptyList(),
                    description = "Plan parsing error: ${exception.message}"
                )
            )
        }
    }

    // ── L4: Chat ───────────────────────────────────────────────

    /**
     * L4 聊天回复
     *
     * 当用户输入为开放式问题或 L2/L3 失败时，生成友好文本回复。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @return 聊天回复结果
     */
    suspend fun processChat(
        userInput: String,
        context: AgentContext
    ): InferenceResult.Chat {
        val startTime = System.currentTimeMillis()
        try {
            val systemPrompt = promptBuilder.buildChatPrompt(userInput, context)
    
            Logger.d(tag, "[L4-CHAT] REQ: input=\"$userInput\", model=${remoteConfig.modelId}")
    
            val chatRequest = LlmChatRequest(
                messages = buildMessagesWithHistory(systemPrompt, userInput, context.memorySessionId),
                temperature = remoteLlmConfig.l4Temperature,
                maxTokens = remoteLlmConfig.l4MaxTokens
            )

            val content = try {
                withContext(networkDispatcher) {
                    chatLanguageModel.chat(chatRequest)
                }.aiMessage.text()
            } catch (error: Exception) {
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L4-CHAT] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return InferenceResult.Chat(
                    message = "抱歉，服务暂时不可用，请稍后再试。（错误：${error.message}）"
                )
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L4-CHAT] RSP: latency=${latencyMs}ms, content=\"$content\"")

            saveToMemory(context.memorySessionId, userInput, content)
            return InferenceResult.Chat(message = content)
        } catch (exception: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L4-CHAT] ERR: latency=${latencyMs}ms, ${exception.message}", exception)
            return InferenceResult.Chat(
                message = "抱歉，处理出错了：${exception.message ?: "未知错误"}"
            )
        }
    }

    // ── L4 Streaming Chat ─────────────────────────────────────

    /**
     * L4 流式聊天
     *
     * 通过 SSE 流式输出 token，适用开放自由聊天场景。
     * 使用 suspendCoroutine 将回调式流桥接到协程。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @param onToken 每收到一个 token 的回调
     * @return 流式结果（完整文本 + 性能指标）
     */
    suspend fun processChatStreaming(
        userInput: String,
        context: AgentContext,
        onToken: (String) -> Unit
    ): Result<StreamChatResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val systemPrompt = promptBuilder.buildChatPrompt(userInput, context)
    
            Logger.d(tag, "[L4-STREAM] REQ: input=\"$userInput\"")
    
            val chatRequest = LlmChatRequest(
                messages = buildMessagesWithHistory(systemPrompt, userInput, context.memorySessionId),
                temperature = remoteLlmConfig.l4Temperature,
                maxTokens = remoteLlmConfig.l4MaxTokens
            )

            val result = suspendCoroutine<Result<StreamChatResult>> { continuation ->
                streamingChatModel.chat(chatRequest, object : StreamingChatResponseHandler {
                    override fun onPartialResponse(partialResponse: String) {
                        onToken(partialResponse)
                    }
                
                    override fun onCompleteResponse(completeResponse: LlmChatResponse) {
                        val latencyMs = System.currentTimeMillis() - startTime
                        val fullText = completeResponse.aiMessage.text()
                        Logger.d(tag, "[L4-STREAM] OK latency=${latencyMs}ms, tokens=${completeResponse.metadata?.completionTokens ?: "?"}")
                        saveToMemory(context.memorySessionId, userInput, fullText)
                        continuation.resume(
                            Result.success(
                                StreamChatResult(
                                    fullResponse = completeResponse.aiMessage.text(),
                                    metrics = StreamMetrics(
                                        latencyMs = latencyMs,
                                        promptTokens = completeResponse.metadata?.promptTokens,
                                        completionTokens = completeResponse.metadata?.completionTokens
                                    )
                                )
                            )
                        )
                    }

                    override fun onError(error: Throwable) {
                        val latencyMs = System.currentTimeMillis() - startTime
                        Logger.e(tag, "[L4-STREAM] ERR: latency=${latencyMs}ms", error)
                        continuation.resume(Result.failure(error))
                    }
                })
            }

            result
        } catch (exception: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L4-STREAM] SETUP ERR: latency=${latencyMs}ms", exception)
            Result.failure(exception)
        }
    }

    // ── 解析器 ─────────────────────────────────────────────────

    /**
     * 从文本内容中提取命令（公开方法，供流式完成后解析使用）
     *
     * 在流式场景中，LLM 可能将 tool_calls JSON 输出到文本 content 中（而非标准
     * tool_calls 协议）。此方法从文本中回退解析命令。
     *
     * @param content LLM 返回的完整文本内容
     * @param context 当前 Agent 上下文
     * @return 解析出的命令列表（空列表表示未找到命令）
     */
    fun parseCommandsFromText(
        content: String,
        context: AgentContext
    ): List<AgentCommand> {
        return parseFallbackToolCalls(content, context)
    }

    /**
     * 当 API 响应缺少 tool_calls 字段时，尝试从文本内容中回退解析
     *
     * 某些代理/网关（如 SCF）可能剥离 tool_calls 字段，仅保留文本内容。
     * 如果 LLM 产出的 tool_calls JSON 被输出到 content 字段中，此方法可以兜底恢复。
     */
    private fun parseFallbackToolCalls(
        content: String,
        context: AgentContext
    ): List<AgentCommand> {
        val cleaned = cleanJsonContent(content)

        // 尝试 1: 解析为 {tool_calls: [...]} 结构
        try {
            val jsonObj = JSONObject(cleaned)
            val toolCallsArray = jsonObj.optJSONArray("tool_calls")
            if (toolCallsArray != null && toolCallsArray.length() > 0) {
                val requests = mutableListOf<ToolExecutionRequest>()
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.getJSONObject(i)
                    val func = tc.optJSONObject("function") ?: continue
                    val arguments = when (val raw = func.opt("arguments")) {
                        is JSONObject -> raw.toString()
                        is String -> raw
                        else -> "{}"
                    }
                    Logger.d(tag, "[Fallback] Tool call #$i: name=${func.optString("name", "")}, arguments=$arguments")
                    requests.add(
                        ToolExecutionRequest(
                            id = tc.optString("id", "fallback_$i"),
                            name = func.optString("name", ""),
                            arguments = arguments
                        )
                    )
                }
                if (requests.isNotEmpty()) {
                    return parseToolCalls(requests, context)
                }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    /**
     * 构建 L2 Batch 模式的 ToolSpecifications
     *
     * 将可用命令映射为标准 OpenAI function calling tools，
     * 使远程 LLM 以 tool_calls 协议返回命令，而非文本 JSON。
     */
    private fun buildL2ToolSpecifications(): List<ToolSpecification> {
        return listOf(
            ToolSpecification(
                name = "capture",
                description = "拍照",
                parameters = ToolParameters()
            ),
            ToolSpecification(
                name = "toggle_recording",
                description = "切换录像（开始/停止）",
                parameters = ToolParameters()
            ),
            ToolSpecification(
                name = "flip_camera",
                description = "切换前后摄像头",
                parameters = ToolParameters()
            ),
            ToolSpecification(
                name = "adjust_beauty",
                description = "调整美颜参数（磨皮、美白、瘦脸、大眼等）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "smoothing" to JsonSchemaProperty(type = "integer", description = "磨皮 0-100"),
                        "whitening" to JsonSchemaProperty(type = "integer", description = "美白 0-100"),
                        "slim_face" to JsonSchemaProperty(type = "integer", description = "瘦脸 -50-50"),
                        "big_eyes" to JsonSchemaProperty(type = "integer", description = "大眼 0-100"),
                        "lip_color" to JsonSchemaProperty(type = "integer", description = "唇色 0-100"),
                        "blush" to JsonSchemaProperty(type = "integer", description = "腮红 0-100"),
                        "eyebrow" to JsonSchemaProperty(type = "integer", description = "眉毛 0-100")
                    )
                )
            ),
            ToolSpecification(
                name = "switch_filter",
                description = "切换滤镜（冷色/暖色/复古/胶片金等）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "filter" to JsonSchemaProperty(
                            type = "string",
                            description = "滤镜名称",
                            enum = listOf("NONE", "LEICA_CLASSIC", "LEICA_VIBRANT", "LEICA_BW", "FILM_GOLD", "FILM_FUJI", "VINTAGE", "COOL", "WARM")
                        )
                    ),
                    required = listOf("filter")
                )
            ),
            ToolSpecification(
                name = "switch_style",
                description = "切换艺术风格（漫画/素描/海报等）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "style" to JsonSchemaProperty(
                            type = "string",
                            description = "风格名称",
                            enum = listOf("NONE", "TOON", "SKETCH", "POSTERIZE", "EMBOSS", "CROSSHATCH")
                        )
                    ),
                    required = listOf("style")
                )
            ),
            ToolSpecification(
                name = "switch_scene",
                description = "切换场景模式（夜景/月亮/普通）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "scene" to JsonSchemaProperty(
                            type = "string",
                            description = "场景名称",
                            enum = listOf("night", "moon", "none")
                        )
                    ),
                    required = listOf("scene")
                )
            ),
            ToolSpecification(
                name = "switch_ratio",
                description = "切换画面比例",
                parameters = ToolParameters(
                    properties = mapOf(
                        "ratio" to JsonSchemaProperty(
                            type = "string",
                            description = "比例",
                            enum = listOf("4:3", "16:9", "full")
                        )
                    ),
                    required = listOf("ratio")
                )
            ),
            ToolSpecification(
                name = "adjust_exposure",
                description = "调整曝光补偿",
                parameters = ToolParameters(
                    properties = mapOf(
                        "exposure" to JsonSchemaProperty(type = "integer", description = "曝光值 -2 到 2")
                    ),
                    required = listOf("exposure")
                )
            ),
            ToolSpecification(
                name = "adjust_zoom",
                description = "调整变焦倍数",
                parameters = ToolParameters(
                    properties = mapOf(
                        "zoom" to JsonSchemaProperty(type = "number", description = "变焦倍数 0.5-10")
                    ),
                    required = listOf("zoom")
                )
            ),
            ToolSpecification(
                name = "switch_mode",
                description = "切换拍摄模式",
                parameters = ToolParameters(
                    properties = mapOf(
                        "mode" to JsonSchemaProperty(
                            type = "string",
                            description = "拍摄模式",
                            enum = listOf("PHOTO", "VIDEO", "PRO", "DOCUMENT")
                        )
                    ),
                    required = listOf("mode")
                )
            ),
            ToolSpecification(
                name = "delay",
                description = "延迟执行（必须先执行delay，再执行后续命令）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "delay_ms" to JsonSchemaProperty(type = "integer", description = "延迟毫秒数")
                    ),
                    required = listOf("delay_ms")
                )
            ),
            ToolSpecification(
                name = "navigate_to",
                description = "导航到指定页面",
                parameters = ToolParameters(
                    properties = mapOf(
                        "destination" to JsonSchemaProperty(
                            type = "string",
                            description = "目标页面",
                            enum = listOf("camera", "gallery", "settings", "debug")
                        )
                    ),
                    required = listOf("destination")
                )
            ),
            ToolSpecification(
                name = "go_back",
                description = "返回上一页",
                parameters = ToolParameters()
            ),
            ToolSpecification(
                name = "text_reply",
                description = "文本回复（闲聊/问答/无法执行时使用）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "message" to JsonSchemaProperty(type = "string", description = "回复内容")
                    ),
                    required = listOf("message")
                )
            ),
            ToolSpecification(
                name = "launch_app",
                description = "打开本机应用",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package_name" to JsonSchemaProperty(type = "string", description = "应用包名"),
                        "app_name" to JsonSchemaProperty(type = "string", description = "应用名称")
                    )
                )
            ),
            ToolSpecification(
                name = "open_system_settings",
                description = "打开系统设置页面",
                parameters = ToolParameters(
                    properties = mapOf(
                        "setting" to JsonSchemaProperty(
                            type = "string",
                            description = "设置项",
                            enum = listOf("wifi", "bluetooth", "accessibility", "display", "location", "app_notifications")
                        )
                    ),
                    required = listOf("setting")
                )
            ),
            ToolSpecification(
                name = "perform_accessibility_action",
                description = "在其他应用执行无障碍操作（点击/长按/输入/滑动/返回/主页/最近任务）",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to JsonSchemaProperty(
                            type = "string",
                            description = "操作类型",
                            enum = listOf("click", "long_click", "input", "scroll_forward", "scroll_backward", "back", "home", "recent")
                        ),
                        "target" to JsonSchemaProperty(type = "object", description = "目标元素 {type, value}"),
                        "text" to JsonSchemaProperty(type = "string", description = "输入文本")
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    /**
     * 将 tool_execution_requests 转换为 AgentCommand 列表
     *
     * 使用 [ToolCallCommandParser] 直接解析，不经过 method/params 中间格式。
     * 远程推理链路与本地 LLM 的 method/params 协议完全隔离。
     */
    private fun parseToolCalls(
        toolRequests: List<ToolExecutionRequest>,
        context: AgentContext
    ): List<AgentCommand> {
        Logger.d(tag, "[parseToolCalls] Processing ${toolRequests.size} tool calls via ToolCallCommandParser")
        return ToolCallCommandParser.parseAll(toolRequests, context)
    }

    /**
     * 解析 L3 ExecutionPlan
     */
    private fun parseExecutionPlan(
        content: String,
        context: AgentContext
    ): ExecutionPlan {
        val cleaned = cleanJsonContent(content)

        return try {
            val jsonObject = JSONObject(cleaned)
            val planId = jsonObject.optString("plan_id", "plan_${System.currentTimeMillis()}")
            val description = jsonObject.optString("description", "")

            val stepsArray = jsonObject.optJSONArray("steps")
            val steps = mutableListOf<PlanStep>()

            if (stepsArray != null) {
                for (index in 0 until stepsArray.length()) {
                    val stepObject = stepsArray.getJSONObject(index)
                    val stepNum = stepObject.optInt("step", index + 1)
                    val condition = stepObject.optString("condition", "").takeIf { it.isNotBlank() }
                    val stepDescription = stepObject.optString("description", "")
                    val delayMs = stepObject.optLong("delayMs", 0L)
                    val repeatCount = stepObject.optInt("repeat_count", 1).coerceAtLeast(1)

                    // 解析等待条件
                    val waitCondition = parseWaitCondition(stepObject.optJSONObject("wait_condition"))

                    // 解析命令：L3 plan 使用 command 字段，格式为 {name, arguments}
                    // 标准 tool_calls 格式，与 L2 Batch 保持一致
                    val commandObj = stepObject.optJSONObject("command")
                    val action = if (commandObj != null) {
                        val name = commandObj.optString("name", "")
                        val args = commandObj.optJSONObject("arguments")?.toString() ?: "{}"
                        if (name.isNotBlank()) {
                            val request = ToolExecutionRequest(
                                id = "plan_step_$stepNum",
                                name = name,
                                arguments = args
                            )
                            ToolCallCommandParser.parse(request, context)
                        } else {
                            AgentCommand.TextReply(message = "步骤解析失败：command 缺少 name 字段")
                        }
                    } else {
                        // 兼容旧格式：method + params（逐步迁移期间保留）
                        val methodName = stepObject.optString("method", "")
                        val paramsObject = stepObject.optJSONObject("params")
                        if (methodName.isNotBlank()) {
                            val args = paramsObject?.toString() ?: "{}"
                            val request = ToolExecutionRequest(
                                id = "plan_step_$stepNum",
                                name = methodName,
                                arguments = args
                            )
                            ToolCallCommandParser.parse(request, context)
                        } else {
                            AgentCommand.TextReply(message = "步骤解析失败：缺少 command 或 method 字段")
                        }
                    }

                    steps.add(
                        PlanStep(
                            step = stepNum,
                            action = action,
                            condition = condition,
                            waitCondition = waitCondition,
                            repeatCount = repeatCount,
                            description = stepDescription,
                            delayMs = delayMs
                        )
                    )
                }
            }

            ExecutionPlan(
                planId = planId,
                steps = steps,
                description = description
            )
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to parse execution plan: $cleaned", exception)
            ExecutionPlan(
                planId = "error_${System.currentTimeMillis()}",
                steps = emptyList(),
                description = "计划解析失败：${exception.message}"
            )
        }
    }

    /**
     * 解析等待条件 JSON
     */
    private fun parseWaitCondition(waitObject: org.json.JSONObject?): WaitCondition? {
        if (waitObject == null) return null

        val type = waitObject.optString("type", "")
        return when (type) {
            "smile_detected" -> WaitCondition.SmileDetected(
                timeoutMs = waitObject.optLong("timeout_ms", 15000)
            )
            "face_detected" -> WaitCondition.FaceDetected(
                timeoutMs = waitObject.optLong("timeout_ms", 10000)
            )
            "duration" -> WaitCondition.Duration(
                delayMs = waitObject.optLong("delay_ms", 1000)
            )
            "user_confirm" -> WaitCondition.UserConfirm(
                prompt = waitObject.optString("prompt", "")
            )
            else -> null
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────

    /**
     * 清理 JSON 内容，移除 think 标签和代码块
     */
    private fun cleanJsonContent(content: String): String {
        var cleaned = content.trim()

        // 移除 think 标签
        while (true) {
            val thinkStart = cleaned.indexOf("<think>")
            val thinkEnd = cleaned.indexOf("</think>")
            if (thinkStart >= 0 && thinkEnd > thinkStart) {
                cleaned = cleaned.removeRange(thinkStart, thinkEnd + "</think>".length).trim()
            } else {
                break
            }
        }

        val orphanThinkStart = cleaned.indexOf("<think>")
        if (orphanThinkStart >= 0) {
            cleaned = cleaned.substring(0, orphanThinkStart).trim()
        }

        // 移除代码块
        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        
        return cleaned
    }

    /**
     * 将 LLM 输出的 filter 名称解析为 [FilterType]（支持别名/模糊匹配）
     */
    private fun resolveFilterType(name: String): FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> FilterType.NONE
            "LEICA_CLASSIC", "徕卡经典", "徕卡经典滤镜" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID", "徕卡鲜艳", "徕卡鲜艳滤镜" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME", "徕卡黑白", "徕卡黑白滤镜" -> FilterType.LEICA_BW
            "FILM_GOLD", "胶片金", "胶片金滤镜" -> FilterType.FILM_GOLD
            "FILM_FUJI", "胶片富士", "富士", "胶片富士滤镜" -> FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD", "复古", "怀旧" -> FilterType.VINTAGE
            "COOL", "COLD", "冷色", "冷色调", "冷色滤镜", "冷调", "冷调滤镜", "冷滤镜" -> FilterType.COOL
            "WARM", "暖色", "暖色调", "暖色滤镜", "暖调", "暖调滤镜", "暖滤镜" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    /**
     * 将 LLM 输出的 style 名称解析为 [com.mamba.picme.beauty.api.StyleFilter]（支持别名/模糊匹配）
     */
    private fun resolveStyleFilter(name: String): StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> StyleFilter.NONE
            "TOON", "CARTOON", "COMIC" -> StyleFilter.TOON
            "SKETCH" -> StyleFilter.SKETCH
            "POSTERIZE", "POSTER" -> StyleFilter.POSTERIZE
            "EMBOSS" -> StyleFilter.EMBOSS
            "CROSSHATCH", "CROSS_HATCH" -> StyleFilter.CROSSHATCH
            else -> runCatching { StyleFilter.valueOf(normalized) }.getOrDefault(StyleFilter.NONE)
        }
    }
}

// ── 流式聊天结果类型 ────────────────────────────────────────────

/**
 * 流式聊天结果
 *
 * @property fullResponse LLM 返回的完整文本内容（可能包含 tool_calls JSON）
 * @property commands 从响应文本中提取的命令列表（空列表表示纯文本回复）
 * @property metrics 流式性能指标
 */
data class StreamChatResult(
    val fullResponse: String,
    val commands: List<AgentCommand> = emptyList(),
    val metrics: StreamMetrics? = null
)

/**
 * 流式聊天的性能指标
 */
data class StreamMetrics(
    val latencyMs: Long = 0L,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null
)

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
    private val store: DataStoreChatMemoryStore,
    private val maxMessages: Int = 10
) : ChatMemory {

    override fun id(): Any = memoryId

    override fun messages(): MutableList<LcChatMessage> {
        return store.getMessages(memoryId)
    }

    override fun add(message: LcChatMessage) {
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
