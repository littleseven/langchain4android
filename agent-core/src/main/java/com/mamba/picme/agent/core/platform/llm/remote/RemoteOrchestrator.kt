package com.mamba.picme.agent.core.platform.llm.remote

import android.content.Context
import com.mamba.picme.agent.core.api.execution.ExecutionPlan
import com.mamba.picme.agent.core.api.execution.PlanStep
import com.mamba.picme.agent.core.api.execution.WaitCondition
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.parsing.PromptBuilder
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.api.context.MediaType
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.ChatLanguageModel
import com.mamba.picme.agent.core.api.ChatRequest
import com.mamba.picme.agent.core.api.SystemMessage
import com.mamba.picme.agent.core.api.UserMessage
import com.mamba.picme.agent.core.api.ToolSpecification
import com.mamba.picme.agent.core.api.ToolParameters
import com.mamba.picme.agent.core.api.JsonSchemaProperty
import com.mamba.picme.agent.core.api.ToolExecutionRequest
import org.json.JSONArray
import org.json.JSONObject

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
 * @param context Application Context，用于加载本地配置
 * @param remoteConfig 远程模型配置
 * @param promptBuilder Prompt 构建器
 */
class RemoteOrchestrator(
    private val context: Context,
    private val remoteConfig: RemoteModelConfig,
    private val promptBuilder: PromptBuilder,
    val chatLanguageModel: ChatLanguageModel = UnifiedRemoteClient(remoteConfig)
) {

    private val tag = "RemoteOrchestrator"

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

            val chatRequest = ChatRequest(
                messages = listOf(
                    SystemMessage(systemPrompt),
                    UserMessage(userInput)
                ),
                toolSpecifications = buildL2ToolSpecifications(),
                temperature = remoteLlmConfig.l2Temperature,
                maxTokens = remoteLlmConfig.l2MaxTokens
            )

            val response = try {
                chatLanguageModel.chat(chatRequest)
            } catch (error: Exception) {
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L2-BATCH] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return InferenceResult.Batch(
                    commands = listOf(AgentCommand.Error(reason = "Batch API error: ${error.message}"))
                )
            }

            val latencyMs = System.currentTimeMillis() - startTime
            
            // 优先使用 tool_calls（标准 OpenAI 协议）
            val toolRequests = response.aiMessage.toolExecutionRequests
            if (toolRequests.isNotEmpty()) {
                val commands = parseToolCalls(toolRequests, context)
                Logger.d(
                    tag,
                    "[L2-BATCH] tool_calls OK latency=${latencyMs}ms, ${toolRequests.size} calls -> ${commands.size} commands"
                )
                return InferenceResult.Batch(commands = commands)
            }
            
            // 没有 tool_calls：尝试从文本内容中回退解析 tool_calls JSON
            val textContent = response.aiMessage.text
            if (textContent.isNotBlank()) {
                val fallbackCommands = parseFallbackToolCalls(textContent, context)
                if (fallbackCommands.isNotEmpty()) {
                    Logger.w(tag, "[L2-BATCH] tool_calls missing in API response, fallback parsed ${fallbackCommands.size} commands from content")
                    return InferenceResult.Batch(commands = fallbackCommands)
                }
                Logger.w(tag, "[L2-BATCH] RSP: latency=${latencyMs}ms, content=\"$textContent\", treating as text reply")
            } else {
                Logger.w(tag, "[L2-BATCH] RSP: latency=${latencyMs}ms, content is blank AND no tool_calls — possible proxy/cors stripping")
            }
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

            val chatRequest = ChatRequest(
                messages = listOf(
                    SystemMessage(systemPrompt),
                    UserMessage(userInput)
                ),
                temperature = remoteLlmConfig.l3Temperature,
                maxTokens = remoteLlmConfig.l3MaxTokens
            )

            val content = try {
                chatLanguageModel.chat(chatRequest).aiMessage.text
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

            val chatRequest = ChatRequest(
                messages = listOf(
                    SystemMessage(systemPrompt),
                    UserMessage(userInput)
                ),
                temperature = remoteLlmConfig.l4Temperature,
                maxTokens = remoteLlmConfig.l4MaxTokens
            )

            val content = try {
                chatLanguageModel.chat(chatRequest).aiMessage.text
            } catch (error: Exception) {
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L4-CHAT] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return InferenceResult.Chat(
                    message = "抱歉，服务暂时不可用，请稍后再试。（错误：${error.message}）"
                )
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L4-CHAT] RSP: latency=${latencyMs}ms, content=\"$content\"")

            return InferenceResult.Chat(message = content)
        } catch (exception: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L4-CHAT] ERR: latency=${latencyMs}ms, ${exception.message}", exception)
            return InferenceResult.Chat(
                message = "抱歉，处理出错了：${exception.message ?: "未知错误"}"
            )
        }
    }

    // ── 解析器 ─────────────────────────────────────────────────

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

        // 尝试 2: 解析为 method/params JSON 数组（兼容旧格式）
        try {
            val jsonArray = JSONArray(cleaned)
            if (jsonArray.length() > 0) {
                val commands = mutableListOf<AgentCommand>()
                for (i in 0 until jsonArray.length()) {
                    try {
                        val item = jsonArray.getJSONObject(i)
                        commands.add(parseAgentCommand(item, context))
                    } catch (e: Exception) {
                        Logger.e(tag, "[Fallback] Failed to parse array item #$i", e)
                    }
                }
                if (commands.isNotEmpty()) {
                    return commands
                }
            }
        } catch (_: Exception) {}

        // 尝试 3: 单个 JSON 对象
        try {
            val jsonObj = JSONObject(cleaned)
            val method = jsonObj.optString("method", jsonObj.optString("name", ""))
            if (method.isNotBlank()) {
                return listOf(parseAgentCommand(jsonObj, context))
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
     */
    private fun parseToolCalls(
        toolRequests: List<ToolExecutionRequest>,
        context: AgentContext
    ): List<AgentCommand> {
        val commands = mutableListOf<AgentCommand>()
        Logger.d(tag, "[parseToolCalls] Processing ${toolRequests.size} tool calls")
        for ((index, req) in toolRequests.withIndex()) {
            try {
                Logger.d(tag, "[parseToolCalls] #$index: name=${req.name}, arguments=${req.arguments}")
                // 构建 {method, params...} 格式供 parseAgentCommand 使用
                val json = JSONObject().apply {
                    put("method", req.name)
                    // 解析 arguments JSON 字符串为 params 对象
                    try {
                        val args = JSONObject(req.arguments)
                        put("params", args)
                        Logger.d(tag, "[parseToolCalls] #$index: parsed params keys=${args.keys().asSequence().toList()}")
                    } catch (_: Exception) {
                        Logger.w(tag, "[parseToolCalls] #$index: arguments not valid JSON: ${req.arguments}")
                    }
                }
                val command = parseAgentCommand(json, context)
                commands.add(command)
                Logger.d(tag, "[parseToolCalls] #$index: -> ${command::class.simpleName}")
            } catch (e: Exception) {
                Logger.e(tag, "[parseToolCalls] FAILED: name=${req.name}, args=${req.arguments}", e)
            }
        }
        return commands
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

                    // 解析命令：method 字符串 + params 对象合并为统一格式
                    val methodName = stepObject.optString("method", "")
                    val paramsObject = stepObject.optJSONObject("params")
                    val commandJson = if (paramsObject != null) {
                        // 将 params 合并到以 method 为顶层的对象中
                        val merged = JSONObject()
                        merged.put("method", methodName)
                        paramsObject.keys().forEach { key ->
                            merged.put(key, paramsObject.get(key))
                        }
                        merged
                    } else {
                        JSONObject().apply { put("method", methodName) }
                    }
                    val action = if (methodName.isNotBlank()) {
                        parseAgentCommand(commandJson, context)
                    } else {
                        AgentCommand.TextReply(message = "步骤解析失败：缺少 method 字段")
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

    /**
     * 从 JSON 对象解析单个 [AgentCommand]
     *
     * @param jsonObject JSON 对象
     * @param context 当前 Agent 上下文（用于获取默认值）
     * @return 解析后的 AgentCommand
     */
    private fun parseAgentCommand(
        jsonObject: JSONObject,
        context: AgentContext
    ): AgentCommand {
        val normalizedCommand = mergeParamsIntoRoot(jsonObject)
        val method = normalizedCommand.optString("method", "")

        return when (method) {
            "adjust_beauty" -> {
                val smoothing = normalizedCommand.optDouble("smoothing", context.beautySettings.smoothing.toDouble()).toFloat()
                val whitening = normalizedCommand.optDouble("whitening", context.beautySettings.whitening.toDouble()).toFloat()
                val slimFace = normalizedCommand.optDouble("slim_face", context.beautySettings.slimFace.toDouble()).toFloat()
                val bigEyes = normalizedCommand.optDouble("big_eyes", context.beautySettings.bigEyes.toDouble()).toFloat()
                val lipColor = normalizedCommand.optDouble("lip_color", context.beautySettings.lipColor.toDouble()).toFloat()
                val blush = normalizedCommand.optDouble("blush", context.beautySettings.blush.toDouble()).toFloat()
                val eyebrow = normalizedCommand.optDouble("eyebrow", context.beautySettings.eyebrow.toDouble()).toFloat()
                AgentCommand.AdjustBeauty(
                    settings = context.beautySettings.copy(
                        enabled = true,
                        smoothing = smoothing,
                        whitening = whitening,
                        slimFace = slimFace,
                        bigEyes = bigEyes,
                        lipColor = lipColor,
                        blush = blush,
                        eyebrow = eyebrow
                    )
                )
            }
            "switch_filter" -> {
                val filterName = normalizedCommand.optString("filter", "NONE")
                AgentCommand.SwitchFilter(filterType = resolveFilterType(filterName))
            }
            "switch_style" -> {
                val styleName = normalizedCommand.optString("style", "NONE")
                AgentCommand.SwitchStyle(styleFilter = resolveStyleFilter(styleName))
            }
            "switch_scene" -> {
                val scene = normalizedCommand.optString("scene", "none")
                AgentCommand.SwitchScene(sceneName = scene)
            }
            "switch_ratio" -> {
                val ratio = normalizedCommand.optString("ratio", "full")
                AgentCommand.SwitchRatio(ratio = ratio)
            }
            "adjust_exposure" -> {
                val exposure = normalizedCommand.optInt("exposure", 0)
                AgentCommand.AdjustExposure(exposure = exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = normalizedCommand.optDouble("zoom", 1.0).toFloat()
                AgentCommand.AdjustZoom(zoomRatio = zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera()
            "capture", "photo" -> AgentCommand.CapturePhoto()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "delay" -> {
                val delayMs = normalizedCommand.optLong("delay_ms", 3000)
                AgentCommand.Delay(delayMs = delayMs.coerceIn(1, 300000))
            }
            "switch_mode" -> {
                val modeName = normalizedCommand.optString("mode", "PHOTO")
                val mode = runCatching { MediaType.valueOf(modeName) }
                    .getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(mode = mode)
            }
            "text_reply" -> {
                val message = normalizedCommand.optString("message", "收到")
                AgentCommand.TextReply(message = message)
            }
            "navigate_to" -> {
                val destination = normalizedCommand.optString("destination", "")
                AgentCommand.NavigateTo(destination = destination)
            }
            "go_back" -> AgentCommand.GoBack()
            else -> AgentCommand.TextReply(message = "收到，有什么其他需要帮忙的吗？")
        }
    }

    private fun mergeParamsIntoRoot(jsonObject: JSONObject): JSONObject {
        val paramsObject = jsonObject.optJSONObject("params") ?: return jsonObject
        val merged = JSONObject(jsonObject.toString())
        paramsObject.keys().forEach { key ->
            if (!merged.has(key) || merged.isNull(key)) {
                merged.put(key, paramsObject.opt(key))
            }
        }
        return merged
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
