package com.picme.domain.agent.remote

import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.data.remote.kimi.KimiCodingApiClient
import com.picme.data.remote.kimi.KimiCodingMessage
import com.picme.data.remote.kimi.KimiCodingRequest
import com.picme.domain.agent.PromptBuilder
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.InferenceResult
import com.picme.domain.model.MediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 远程编排器
 *
 * 负责执行 L2 Batch FC、L3 Plan-and-Execute、L4 Chat 三种远程推理模式。
 * 统一使用 Kimi Coding API（Claude 格式），通过 [PromptBuilder] 构建分层 Prompt。
 *
 * @param codingClient Kimi Coding API 客户端
 * @param promptBuilder Prompt 构建器
 * @param model 模型 ID，默认 kimi-for-coding
 */
class RemoteOrchestrator(
    private val codingClient: KimiCodingApiClient,
    private val promptBuilder: PromptBuilder,
    private val model: String = "kimi-for-coding"
) {

    private val tag = "PicMe:RemoteOrchestrator"

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
            val request = KimiCodingRequest(
                model = model,
                messages = listOf(
                    KimiCodingMessage(role = "user", content = userInput)
                ),
                system = systemPrompt,
                maxTokens = 2048,
                temperature = 0.3,
                stream = false
            )

            Logger.d(tag, "[L2-BATCH] REQ: input=\"$userInput\"")

            val response = codingClient.service.messages(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Logger.e(tag, "[L2-BATCH] ERR: HTTP ${response.code()}, body=$errorBody")
                return InferenceResult.Batch(
                    commands = listOf(
                        AgentCommand.Error("Batch API error: ${response.code()}")
                    )
                )
            }

            val body = response.body()
                ?: return InferenceResult.Batch(
                    commands = listOf(AgentCommand.Error("Empty response body"))
                )

            val content = body.content.firstOrNull()?.text?.trim()
                ?: return InferenceResult.Batch(
                    commands = listOf(AgentCommand.Error("No content in response"))
                )

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L2-BATCH] RSP: latency=${latencyMs}ms, content=\"$content\"")

            val commands = parseCommandArray(content, context)
            Logger.d(tag, "[L2-BATCH] parsed ${commands.size} commands")
            return InferenceResult.Batch(commands = commands)
        } catch (exception: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L2-BATCH] ERR: latency=${latencyMs}ms, ${exception.message}", exception)
            return InferenceResult.Batch(
                commands = listOf(AgentCommand.Error("Batch parsing error: ${exception.message}"))
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
            val request = KimiCodingRequest(
                model = model,
                messages = listOf(
                    KimiCodingMessage(role = "user", content = userInput)
                ),
                system = systemPrompt,
                maxTokens = 2048,
                temperature = 0.3,
                stream = false
            )

            Logger.d(tag, "[L3-PLAN] REQ: input=\"$userInput\"")

            val response = codingClient.service.messages(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Logger.e(tag, "[L3-PLAN] ERR: HTTP ${response.code()}, body=$errorBody")
                return InferenceResult.Plan(
                    plan = ExecutionPlan(
                        planId = "error_${System.currentTimeMillis()}",
                        steps = emptyList(),
                        description = "Plan API error: ${response.code()}"
                    )
                )
            }

            val body = response.body()
                ?: return InferenceResult.Plan(
                    plan = ExecutionPlan(
                        planId = "error_${System.currentTimeMillis()}",
                        steps = emptyList(),
                        description = "Empty response body"
                    )
                )

            val content = body.content.firstOrNull()?.text?.trim()
                ?: return InferenceResult.Plan(
                    plan = ExecutionPlan(
                        planId = "error_${System.currentTimeMillis()}",
                        steps = emptyList(),
                        description = "No content in response"
                    )
                )

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
            val request = KimiCodingRequest(
                model = model,
                messages = listOf(
                    KimiCodingMessage(role = "user", content = userInput)
                ),
                system = systemPrompt,
                maxTokens = 2048,
                temperature = 0.3,
                stream = false
            )

            Logger.d(tag, "[L4-CHAT] REQ: input=\"$userInput\"")

            val response = codingClient.service.messages(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Logger.e(tag, "[L4-CHAT] ERR: HTTP ${response.code()}, body=$errorBody")
                return InferenceResult.Chat(
                    message = "抱歉，服务暂时不可用，请稍后再试。（错误码：${response.code()}）"
                )
            }

            val body = response.body()
                ?: return InferenceResult.Chat(
                    message = "抱歉，收到了空响应，请再试一次。"
                )

            val content = body.content.firstOrNull()?.text?.trim()
                ?: return InferenceResult.Chat(
                    message = "抱歉，没有收到有效回复，请再试一次。"
                )

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
     * 解析 L2 Batch 的命令数组
     */
    private fun parseCommandArray(
        content: String,
        context: AgentContext
    ): List<AgentCommand> {
        val cleaned = cleanJsonContent(content)

        return try {
            when {
                cleaned.startsWith("[") && cleaned.endsWith("]") -> {
                    val jsonArray = JSONArray(cleaned)
                    val commands = mutableListOf<AgentCommand>()
                    for (index in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(index)
                        val command = parseAgentCommand(jsonObject, context)
                        commands.add(command)
                    }
                    commands
                }
                cleaned.startsWith("{") && cleaned.endsWith("}") -> {
                    val jsonObject = JSONObject(cleaned)
                    listOf(parseAgentCommand(jsonObject, context))
                }
                else -> {
                    listOf(AgentCommand.TextReply(cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }))
                }
            }
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to parse command array: $cleaned", exception)
            listOf(AgentCommand.TextReply(cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }))
        }
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

                    val actionObject = stepObject.optJSONObject("action")
                    val action = if (actionObject != null) {
                        parseAgentCommand(actionObject, context)
                    } else {
                        AgentCommand.TextReply("步骤解析失败：缺少 action 字段")
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
        val action = jsonObject.optString("action", "")

        return when (action) {
            "adjust_beauty" -> {
                val smoothing = jsonObject.optDouble("smoothing", context.beautySettings.smoothing.toDouble()).toFloat()
                val whitening = jsonObject.optDouble("whitening", context.beautySettings.whitening.toDouble()).toFloat()
                val slimFace = jsonObject.optDouble("slim_face", context.beautySettings.slimFace.toDouble()).toFloat()
                val bigEyes = jsonObject.optDouble("big_eyes", context.beautySettings.bigEyes.toDouble()).toFloat()
                val lipColor = jsonObject.optDouble("lip_color", context.beautySettings.lipColor.toDouble()).toFloat()
                val blush = jsonObject.optDouble("blush", context.beautySettings.blush.toDouble()).toFloat()
                val eyebrow = jsonObject.optDouble("eyebrow", context.beautySettings.eyebrow.toDouble()).toFloat()
                AgentCommand.AdjustBeauty(
                    context.beautySettings.copy(
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
                val filterName = jsonObject.optString("filter", "NONE")
                AgentCommand.SwitchFilter(resolveFilterType(filterName))
            }
            "switch_style" -> {
                val styleName = jsonObject.optString("style", "NONE")
                AgentCommand.SwitchStyle(resolveStyleFilter(styleName))
            }
            "switch_scene" -> {
                val scene = jsonObject.optString("scene", "none")
                AgentCommand.SwitchScene(scene)
            }
            "switch_ratio" -> {
                val ratio = jsonObject.optString("ratio", "full")
                AgentCommand.SwitchRatio(ratio)
            }
            "adjust_exposure" -> {
                val exposure = jsonObject.optInt("exposure", 0)
                AgentCommand.AdjustExposure(exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = jsonObject.optDouble("zoom", 1.0).toFloat()
                AgentCommand.AdjustZoom(zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera
            "capture", "photo" -> AgentCommand.CapturePhoto
            "toggle_recording" -> AgentCommand.ToggleRecording
            "switch_mode" -> {
                val modeName = jsonObject.optString("mode", "PHOTO")
                val mode = runCatching { MediaType.valueOf(modeName) }
                    .getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(mode)
            }
            "text_reply" -> {
                val message = jsonObject.optString("message", "收到")
                AgentCommand.TextReply(message)
            }
            "navigate_to" -> {
                val destination = jsonObject.optString("destination", "")
                AgentCommand.NavigateTo(destination)
            }
            "go_back" -> AgentCommand.GoBack
            else -> AgentCommand.TextReply("收到，有什么其他需要帮忙的吗？")
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────

    /**
     * 清理 JSON 内容，移除 think 标签和 markdown 代码块
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

        // 移除 markdown 代码块
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
            "LEICA_CLASSIC" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME" -> FilterType.LEICA_BW
            "FILM_GOLD" -> FilterType.FILM_GOLD
            "FILM_FUJI" -> FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD" -> FilterType.VINTAGE
            "COOL", "COLD" -> FilterType.COOL
            "WARM" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    /**
     * 将 LLM 输出的 style 名称解析为 [StyleFilter]（支持别名/模糊匹配）
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
