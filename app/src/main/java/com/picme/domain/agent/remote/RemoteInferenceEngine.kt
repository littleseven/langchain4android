package com.picme.domain.agent.remote

import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.model.RemoteModelConfig
import com.picme.domain.usecase.AiAgentUseCase
import kotlinx.coroutines.delay

/**
 * 远程推理引擎
 *
 * 负责执行 L2 Batch FC、L3 Plan-and-Execute、L4 ReAct 三种远程推理模式。
 * 通过 [UnifiedRemoteClient] 自动适配 Claude/OpenAI 协议。
 *
 * @param remoteConfig 远程模型配置
 */
class RemoteInferenceEngine(
    private val remoteConfig: RemoteModelConfig
) {

    private val unifiedClient = UnifiedRemoteClient(remoteConfig)

    private val tag = REMOTE_TAG

    // ── L2: Batch Function Calling ─────────────────────────────

    /**
     * L2 批量命令解析
     *
     * 将用户输入解析为命令数组，支持单指令和多指令。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @param stateSnapshot 相机状态快照（用于构建 prompt）
     * @return 解析后的命令列表
     */
    suspend fun processBatch(
        userInput: String,
        context: AgentContext,
        stateSnapshot: AiAgentUseCase.CameraStateSnapshot
    ): Result<List<AgentCommand>> {
        val startTime = System.currentTimeMillis()
        try {
            val systemPrompt = buildBatchSystemPrompt(stateSnapshot)

            Logger.d(tag, "[L2-BATCH] REQ: input=\"$userInput\", model=${remoteConfig.modelId}")

            val result = unifiedClient.chat(
                systemPrompt = systemPrompt,
                userInput = userInput,
                maxTokens = 1024,
                temperature = 0.3
            )

            val content = result.getOrElse { error ->
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L2-BATCH] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return Result.failure(error)
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L2-BATCH] RSP: latency=${latencyMs}ms, content=\"$content\"")

            // 解析命令数组
            val commands = parseCommandArray(content, stateSnapshot)
            Logger.d(tag, "[L2-BATCH] parsed ${commands.size} commands")
            return Result.success(commands)
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L2-BATCH] ERR: latency=${latencyMs}ms, ${e.message}", e)
            return Result.failure(e)
        }
    }

    // ── L3: Plan-and-Execute ───────────────────────────────────

    /**
     * L3 生成执行计划
     *
     * 将用户输入解析为结构化执行计划，支持条件和多步骤。
     *
     * @param userInput 用户输入
     * @param context 当前 Agent 上下文
     * @param stateSnapshot 相机状态快照
     * @return 执行计划
     */
    suspend fun generatePlan(
        userInput: String,
        context: AgentContext,
        stateSnapshot: AiAgentUseCase.CameraStateSnapshot
    ): Result<ExecutionPlan> {
        val startTime = System.currentTimeMillis()
        try {
            val systemPrompt = buildPlanSystemPrompt(stateSnapshot)

            Logger.d(tag, "[L3-PLAN] REQ: input=\"$userInput\", model=${remoteConfig.modelId}")

            val result = unifiedClient.chat(
                systemPrompt = systemPrompt,
                userInput = userInput,
                maxTokens = 2048,
                temperature = 0.3
            )

            val content = result.getOrElse { error ->
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L3-PLAN] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return Result.failure(error)
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L3-PLAN] RSP: latency=${latencyMs}ms, content=\"$content\"")

            val plan = parseExecutionPlan(content)
            Logger.d(tag, "[L3-PLAN] parsed plan with ${plan.steps.size} steps")
            return Result.success(plan)
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L3-PLAN] ERR: latency=${latencyMs}ms, ${e.message}", e)
            return Result.failure(e)
        }
    }

    // ── L4: ReAct ──────────────────────────────────────────────

    /**
     * L4 ReAct 兜底回复
     *
     * 当 L2/L3 失败或用户输入为开放式问题时，使用 ReAct 模式生成友好回复。
     *
     * @param userInput 用户输入
     * @param stateSnapshot 相机状态快照
     * @return 文本回复
     */
    suspend fun react(
        userInput: String,
        stateSnapshot: AiAgentUseCase.CameraStateSnapshot
    ): Result<String> {
        val startTime = System.currentTimeMillis()
        try {
            val systemPrompt = buildReActSystemPrompt(stateSnapshot)

            Logger.d(tag, "[L4-REACT] REQ: input=\"$userInput\", model=${remoteConfig.modelId}")

            val result = unifiedClient.chat(
                systemPrompt = systemPrompt,
                userInput = userInput,
                maxTokens = 1024,
                temperature = 0.5
            )

            val content = result.getOrElse { error ->
                val latencyMs = System.currentTimeMillis() - startTime
                Logger.e(tag, "[L4-REACT] ERR: latency=${latencyMs}ms, ${error.message}", error)
                return Result.failure(error)
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Logger.d(tag, "[L4-REACT] RSP: latency=${latencyMs}ms, content=\"$content\"")

            return Result.success(content)
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "[L4-REACT] ERR: latency=${latencyMs}ms, ${e.message}", e)
            return Result.failure(e)
        }
    }

   // ── Prompt 构建 ────────────────────────────────────────────

    private fun buildBatchSystemPrompt(state: AiAgentUseCase.CameraStateSnapshot): String {
        return buildString {
            appendLine("你是PicMe相机的AI助手小觅。用户通过语音或文字与你交互。")
            appendLine()
            appendLine("【绝对规则 - 必须遵守】")
            appendLine("1. 无论用户要求什么，你的回复永远只输出一个JSON数组，不要任何其他文字、解释、标点或换行")
            appendLine("2. 数组中每个元素是一个命令对象: {\"action\":\"命令名\", 参数...}")
            appendLine("3. 命令按数组顺序依次执行")
            appendLine("4. 如果用户只是聊天，输出: [{\"action\":\"text_reply\",\"message\":\"用中文友好回复\"}]")
            appendLine("5. 绝对不要输出<think>标签或思考过程")
            appendLine("6. 绝对不要输出markdown代码块```")
            appendLine()
            appendLine(buildStateSection(state))
            appendLine()
            appendLine(buildCapabilitiesSection())
            appendLine()
            appendLine("【示例 - 严格模仿】")
            appendLine("用户: 磨皮开到60，美白30，然后拍一张")
            appendLine("→ [{\"action\":\"adjust_beauty\",\"smoothing\":60},{\"action\":\"adjust_beauty\",\"whitening\":30},{\"action\":\"capture\"}]")
            appendLine("用户: 你好")
            appendLine("→ [{\"action\":\"text_reply\",\"message\":\"你好呀，我是小觅！\"}]")
            appendLine("用户: 切徕卡黑白再拍照")
            appendLine("→ [{\"action\":\"switch_filter\",\"filter\":\"LEICA_BW\"},{\"action\":\"capture\"}]")
        }
    }

    private fun buildPlanSystemPrompt(state: AiAgentUseCase.CameraStateSnapshot): String {
        return buildString {
            appendLine("你是PicMe相机的AI助手小觅。用户可能提出包含条件或复杂步骤的请求。")
            appendLine()
            appendLine("【输出格式 - 严格JSON】")
            appendLine("{")
            appendLine("  \"plan_id\": \"plan_1\",")
            appendLine("  \"description\": \"计划描述\",")
            appendLine("  \"steps\": [")
            appendLine("    {\"step\":1, \"condition\":\"条件表达式或null\", \"action\":{\"action\":\"命令名\",...}, \"description\":\"步骤描述\", \"delayMs\":500},")
            appendLine("    {\"step\":2, \"condition\":null, \"action\":{\"action\":\"命令名\",...}, \"description\":\"步骤描述\", \"delayMs\":0}")
            appendLine("  ]")
            appendLine("}")
            appendLine()
            appendLine("【规则】")
            appendLine("1. condition 字段：需要条件判断时填写描述性条件，无条件时填 null")
            appendLine("2. delayMs 字段：给UI反应时间的延迟（毫秒），拍照建议 500ms，其他 0ms")
            appendLine("3. 绝对不要输出任何其他文字")
            appendLine()
            appendLine(buildStateSection(state))
            appendLine()
            appendLine(buildCapabilitiesSection())
            appendLine()
            appendLine("【示例】")
            appendLine("用户: 如果是后置摄像头就切前置，然后设置磨皮80美白60，最后拍一张")
            appendLine("→ {\"plan_id\":\"plan_1\",\"description\":\"切换前置并拍摄人像\",\"steps\":[{\"step\":1,\"condition\":\"当前是后置摄像头\",\"action\":{\"action\":\"flip_camera\"},\"description\":\"切换到前置摄像头\",\"delayMs\":300},{\"step\":2,\"condition\":null,\"action\":{\"action\":\"adjust_beauty\",\"smoothing\":80,\"whitening\":60},\"description\":\"设置人像美颜参数\",\"delayMs\":0},{\"step\":3,\"condition\":null,\"action\":{\"action\":\"capture\"},\"description\":\"拍照\",\"delayMs\":500}]}")
        }
    }

    private fun buildReActSystemPrompt(state: AiAgentUseCase.CameraStateSnapshot): String {
        return buildString {
            appendLine("你是PicMe相机的AI助手小觅。用户的问题可能无法直接映射到相机命令，请友好回复。")
            appendLine()
            appendLine("【规则】")
            appendLine("1. 用中文友好、简洁地回复")
            appendLine("2. 如果用户问你能做什么，列出你可以控制的相机功能")
            appendLine("3. 如果用户的问题与相机无关，礼貌地引导回相机功能")
            appendLine("4. 绝对不要输出JSON格式")
            appendLine()
            appendLine(buildStateSection(state))
        }
    }

    private fun buildStateSection(state: AiAgentUseCase.CameraStateSnapshot): String {
        return buildString {
            appendLine("【当前相机状态】")
            appendLine("美颜=${if (state.beautySettings.enabled) "开" else "关"}, 磨皮=${state.beautySettings.smoothing.toInt()}, 美白=${state.beautySettings.whitening.toInt()}, 瘦脸=${state.beautySettings.slimFace.toInt()}, 大眼=${state.beautySettings.bigEyes.toInt()}, 唇色=${state.beautySettings.lipColor.toInt()}, 腮红=${state.beautySettings.blush.toInt()}, 眉毛=${state.beautySettings.eyebrow.toInt()}")
            appendLine("滤镜=${state.filterType.name}, 风格=${state.styleFilter.name}, 变焦=${state.zoomRatio}x, 曝光=${state.exposureCompensation}, 模式=${state.captureMode.name}")
        }
    }

    private fun buildCapabilitiesSection(): String {
        return buildString {
            appendLine("【可用命令】")
            appendLine("adjust_beauty: smoothing=0~100(磨皮), whitening=0~100(美白), slim_face=-50~50(瘦脸), big_eyes=0~100(大眼), lip_color=0~100(唇色), blush=0~100(腮红), eyebrow=0~100(眉毛)")
            appendLine("switch_filter: filter=NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("switch_style: style=NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("switch_scene: scene=night|moon|none")
            appendLine("switch_ratio: ratio=4:3|16:9|full")
            appendLine("adjust_exposure: exposure=-2~2")
            appendLine("adjust_zoom: zoom=0.5~10.0")
            appendLine("flip_camera: 翻转前后摄像头")
            appendLine("capture: 拍照")
            appendLine("toggle_recording: 开始/停止录像")
            appendLine("switch_mode: mode=PHOTO|VIDEO|PORTRAIT|PRO|DOCUMENT")
            appendLine("text_reply: 普通聊天回复")
            appendLine()
            appendLine("【中文名称映射】")
            appendLine("滤镜: 无→NONE, 徕卡经典→LEICA_CLASSIC, 徕卡鲜艳→LEICA_VIBRANT, 徕卡黑白→LEICA_BW")
            appendLine("滤镜: 胶片金→FILM_GOLD, 胶片富士→FILM_FUJI, 复古→VINTAGE, 冷调→COOL, 暖调→WARM")
            appendLine("风格: 无→NONE, 卡通→TOON, 素描→SKETCH, 色调分离→POSTERIZE, 浮雕→EMBOSS, 交叉线→CROSSHATCH")
            appendLine("模式: 拍照→PHOTO, 录像→VIDEO, 人像→PORTRAIT, 专业→PRO, 文档→DOCUMENT")
            appendLine("场景: 夜景→night, 月亮→moon, 关闭→none")
            appendLine("比例: 4比3→4:3, 16比9→16:9, 全屏→full")
        }
    }

    // ── 解析器 ─────────────────────────────────────────────────

    /**
     * 解析 L2 Batch 的命令数组
     */
    private fun parseCommandArray(
        content: String,
        state: AiAgentUseCase.CameraStateSnapshot
    ): List<AgentCommand> {
        val cleaned = cleanJsonContent(content)

        // 尝试解析 JSON 数组
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            return parseJsonArray(cleaned, state)
        }

        // 如果不是数组，尝试作为单个对象解析
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            val command = parseSingleJsonCommand(cleaned, state)
            return listOfNotNull(command)
        }

        // 兜底：作为文本回复
        return listOf(AgentCommand.TextReply(cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }))
    }

    /**
     * 解析 L3 Plan
     */
    private fun parseExecutionPlan(content: String): ExecutionPlan {
        val cleaned = cleanJsonContent(content)

        // 简化实现：提取 plan_id、description 和 steps
        val planId = extractJsonField(cleaned, "plan_id") ?: "plan_${System.currentTimeMillis()}"
        val description = extractJsonField(cleaned, "description") ?: ""

        // 提取 steps 数组
        val steps = mutableListOf<PlanStep>()
        val stepsMatch = Regex("\"steps\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
            .find(cleaned)

        if (stepsMatch != null) {
            val stepsArray = stepsMatch.groupValues[1]
            // 简化：按 step 序号提取每个步骤
            val stepRegex = Regex("\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
            stepRegex.findAll(stepsArray).forEachIndexed { index, match ->
                val stepObj = match.groupValues[1]
                val stepNum = extractJsonInt(stepObj, "step") ?: (index + 1)
                val condition = extractJsonField(stepObj, "condition")
                val desc = extractJsonField(stepObj, "description") ?: ""
                val delayMs = extractJsonLong(stepObj, "delayMs") ?: 0L

                // 提取 action 对象
                val actionMatch = Regex("\"action\"\\s*:\\s*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
                    .find(stepObj)
                val actionStr = actionMatch?.groupValues?.get(1)?.let { "{$it}" } ?: "{\"action\":\"text_reply\",\"message\":\"步骤解析失败\"}"

                val action = parseSingleJsonCommand(actionStr, AiAgentUseCase.CameraStateSnapshot())
                    ?: AgentCommand.TextReply("步骤解析失败")

                steps.add(PlanStep(
                    step = stepNum,
                    action = action,
                    condition = condition,
                    description = desc,
                    delayMs = delayMs
                ))
            }
        }

        return ExecutionPlan(planId = planId, steps = steps, description = description)
    }

    private fun parseJsonArray(json: String, state: AiAgentUseCase.CameraStateSnapshot): List<AgentCommand> {
        val commands = mutableListOf<AgentCommand>()
        val objRegex = Regex("\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
        objRegex.findAll(json).forEach { match ->
            val obj = "{${match.groupValues[1]}}"
            parseSingleJsonCommand(obj, state)?.let { commands.add(it) }
        }
        return commands
    }

    private fun parseSingleJsonCommand(json: String, state: AiAgentUseCase.CameraStateSnapshot): AgentCommand? {
        val action = extractJsonField(json, "action") ?: return null
        return when (action) {
            "adjust_beauty" -> {
                val smoothing = extractJsonFloat(json, "smoothing") ?: state.beautySettings.smoothing
                val whitening = extractJsonFloat(json, "whitening") ?: state.beautySettings.whitening
                val slimFace = extractJsonFloat(json, "slim_face") ?: state.beautySettings.slimFace
                val bigEyes = extractJsonFloat(json, "big_eyes") ?: state.beautySettings.bigEyes
                val lipColor = extractJsonFloat(json, "lip_color") ?: state.beautySettings.lipColor
                val blush = extractJsonFloat(json, "blush") ?: state.beautySettings.blush
                val eyebrow = extractJsonFloat(json, "eyebrow") ?: state.beautySettings.eyebrow
                AgentCommand.AdjustBeauty(
                    state.beautySettings.copy(
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
                val filterName = extractJsonField(json, "filter") ?: "NONE"
                AgentCommand.SwitchFilter(resolveFilterType(filterName))
            }
            "switch_style" -> {
                val styleName = extractJsonField(json, "style") ?: "NONE"
                AgentCommand.SwitchStyle(resolveStyleFilter(styleName))
            }
            "switch_scene" -> {
                val scene = extractJsonField(json, "scene") ?: "none"
                AgentCommand.SwitchScene(scene)
            }
            "switch_ratio" -> {
                val ratio = extractJsonField(json, "ratio") ?: "full"
                AgentCommand.SwitchRatio(ratio)
            }
            "adjust_exposure" -> {
                val exposure = extractJsonInt(json, "exposure") ?: 0
                AgentCommand.AdjustExposure(exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = extractJsonFloat(json, "zoom") ?: 1f
                AgentCommand.AdjustZoom(zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera
            "capture", "photo" -> AgentCommand.CapturePhoto
            "toggle_recording" -> AgentCommand.ToggleRecording
            "switch_mode" -> {
                val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                val mode = runCatching { com.picme.domain.model.MediaType.valueOf(modeName) }
                    .getOrDefault(com.picme.domain.model.MediaType.PHOTO)
                AgentCommand.SwitchMode(mode)
            }
            "text_reply" -> {
                val message = extractJsonField(json, "message") ?: "收到"
                AgentCommand.TextReply(message)
            }
            else -> AgentCommand.TextReply("未知命令: $action")
        }
    }

    private fun cleanJsonContent(content: String): String {
        var cleaned = content.trim()
        // 移除 think 标签
        while (true) {
            val thinkStart = cleaned.indexOf("<think>")
            val thinkEnd = cleaned.indexOf("</think>")
            if (thinkStart >= 0 && thinkEnd > thinkStart) {
                cleaned = cleaned.removeRange(thinkStart, thinkEnd + "</think>".length).trim()
            } else break
        }
        val orphanThinkStart = cleaned.indexOf("<think>")
        if (orphanThinkStart >= 0) {
            cleaned = cleaned.substring(0, orphanThinkStart).trim()
        }
        // 移除 markdown 代码块
        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return cleaned
    }

    private fun extractJsonField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val regex = """"$key"\s*:\s*(-?\d+\.?\d*)
""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*(-?\d+)
""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val regex = """"$key"\s*:\s*(-?\d+)
""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun resolveFilterType(name: String): com.picme.beauty.api.FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> com.picme.beauty.api.FilterType.NONE
            "LEICA_CLASSIC" -> com.picme.beauty.api.FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID" -> com.picme.beauty.api.FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME" -> com.picme.beauty.api.FilterType.LEICA_BW
            "FILM_GOLD" -> com.picme.beauty.api.FilterType.FILM_GOLD
            "FILM_FUJI" -> com.picme.beauty.api.FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD" -> com.picme.beauty.api.FilterType.VINTAGE
            "COOL", "COLD" -> com.picme.beauty.api.FilterType.COOL
            "WARM" -> com.picme.beauty.api.FilterType.WARM
            else -> runCatching { com.picme.beauty.api.FilterType.valueOf(normalized) }.getOrDefault(com.picme.beauty.api.FilterType.NONE)
        }
    }

    private fun resolveStyleFilter(name: String): com.picme.beauty.api.StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> com.picme.beauty.api.StyleFilter.NONE
            "TOON", "CARTOON", "COMIC" -> com.picme.beauty.api.StyleFilter.TOON
            "SKETCH" -> com.picme.beauty.api.StyleFilter.SKETCH
            "POSTERIZE", "POSTER" -> com.picme.beauty.api.StyleFilter.POSTERIZE
            "EMBOSS" -> com.picme.beauty.api.StyleFilter.EMBOSS
            "CROSSHATCH", "CROSS_HATCH" -> com.picme.beauty.api.StyleFilter.CROSSHATCH
            else -> runCatching { com.picme.beauty.api.StyleFilter.valueOf(normalized) }.getOrDefault(com.picme.beauty.api.StyleFilter.NONE)
        }
    }

    companion object {
        private const val REMOTE_TAG = "PicMe:RemoteInference"
    }
}