package com.picme.domain.agent.remote

import com.picme.agent.core.remote.ExecutionPlan
import com.picme.agent.core.remote.PlanStep
import com.picme.agent.core.remote.RemoteOrchestrator
import com.picme.agent.core.remote.UnifiedRemoteClient
import com.picme.agent.core.PromptBuilder
import com.picme.core.common.Logger
import com.picme.agent.core.model.AgentCommand
import com.picme.agent.core.model.AgentContext
import com.picme.agent.core.model.InferenceResult
import com.picme.agent.core.model.RemoteModelConfig
import com.picme.domain.usecase.AiAgentUseCase
import kotlinx.coroutines.delay
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.agent.core.model.MediaType

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

    init {
        Logger.i(tag, "RemoteInferenceEngine init: model=${remoteConfig.modelId}, " +
            "baseUrl=${remoteConfig.baseUrl.take(40)}, " +
            "apiKey=${if (remoteConfig.apiKey.isBlank()) "empty" else "set"}, " +
            "gatewayToken=${if (remoteConfig.gatewayToken.isBlank()) "empty" else "set"}")
    }

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
    /**
     * L2 批量命令解析（已废弃，请使用 RemoteOrchestrator）
     *
     * 保留此方法用于向后兼容，内部委托给 RemoteOrchestrator。
     * Prompt 构建已统一迁移到 PromptBuilder。
     */
    @Deprecated("Use RemoteOrchestrator.processBatch instead", ReplaceWith("remoteOrchestrator.processBatch(userInput, context)"))
    suspend fun processBatch(
        userInput: String,
        context: AgentContext,
        stateSnapshot: AiAgentUseCase.CameraStateSnapshot
    ): Result<List<AgentCommand>> {
        // 委托给 RemoteOrchestrator，使用统一的 PromptBuilder
        val remoteOrchestrator = RemoteOrchestrator(
            remoteConfig = remoteConfig,
            promptBuilder = PromptBuilder(com.picme.agent.core.SceneManager.getInstance())
        )
        val result = remoteOrchestrator.processBatch(userInput, context)
        return Result.success(
            when (result) {
                is InferenceResult.Batch -> result.commands
                else -> emptyList()
            }
        )
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
    /**
     * L3 生成执行计划（已废弃，请使用 RemoteOrchestrator）
     */
    @Deprecated("Use RemoteOrchestrator.processPlan instead")
    suspend fun generatePlan(
        userInput: String,
        context: AgentContext,
        stateSnapshot: AiAgentUseCase.CameraStateSnapshot
    ): Result<ExecutionPlan> {
        val remoteOrchestrator = RemoteOrchestrator(
            remoteConfig = remoteConfig,
            promptBuilder = PromptBuilder(com.picme.agent.core.SceneManager.getInstance())
        )
        val result = remoteOrchestrator.processPlan(userInput, context)
        return when (result) {
            is InferenceResult.Plan -> Result.success(result.plan)
            else -> Result.success(ExecutionPlan(planId = "fallback", steps = emptyList(), description = "Fallback"))
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
    /**
     * L4 ReAct 兜底回复（已废弃，请使用 RemoteOrchestrator）
     */
    @Deprecated("Use RemoteOrchestrator.processChat instead")
    suspend fun react(
        userInput: String,
        stateSnapshot: AiAgentUseCase.CameraStateSnapshot
    ): Result<String> {
        val remoteOrchestrator = RemoteOrchestrator(
            remoteConfig = remoteConfig,
            promptBuilder = PromptBuilder(com.picme.agent.core.SceneManager.getInstance())
        )
        // 创建最小 AgentContext
        val agentContext = AgentContext(
            scene = com.picme.agent.core.model.AgentScene.CAMERA,
            beautySettings = stateSnapshot.beautySettings,
            filterType = stateSnapshot.filterType,
            styleFilter = stateSnapshot.styleFilter,
            zoomRatio = stateSnapshot.zoomRatio,
            exposureCompensation = stateSnapshot.exposureCompensation,
            captureMode = stateSnapshot.captureMode,
            isRecording = stateSnapshot.isRecording,
            memorySessionId = "camera"
        )
        val result = remoteOrchestrator.processChat(userInput, agentContext)
        return when (result) {
            is InferenceResult.Chat -> Result.success(result.message)
            else -> Result.success("抱歉，服务暂时不可用")
        }
    }

   // ── Prompt 构建（已废弃，全部迁移到 PromptBuilder）────────────────────────

    @Deprecated("Prompt building migrated to PromptBuilder")
    private fun buildBatchSystemPrompt(state: AiAgentUseCase.CameraStateSnapshot): String = ""

    @Deprecated("Prompt building migrated to PromptBuilder")
    private fun buildPlanSystemPrompt(state: AiAgentUseCase.CameraStateSnapshot): String = ""

    @Deprecated("Prompt building migrated to PromptBuilder")
    private fun buildReActSystemPrompt(state: AiAgentUseCase.CameraStateSnapshot): String = ""

    @Deprecated("Prompt building migrated to PromptBuilder")
    private fun buildStateSection(state: AiAgentUseCase.CameraStateSnapshot): String = ""

    @Deprecated("Prompt building migrated to PromptBuilder")
    private fun buildCapabilitiesSection(): String = ""

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
        return listOf(AgentCommand.TextReply(message = cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }))
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

                // 提取 method 和 params
                val methodMatch = Regex(""""method"\s*:\"([^\"]+)\"""").find(stepObj)
                val methodName = methodMatch?.groupValues?.get(1) ?: "text_reply"
                
                // 提取 params 对象内容
                val paramsMatch = Regex("\"params\"\\s*:\\s*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL).find(stepObj)
                val paramsStr = paramsMatch?.groupValues?.get(1)?.let { "{$it}" } ?: "{}"
                
                // 合并为统一格式解析
                val mergedJson = "{\"method\":\"$methodName\",$paramsStr}"
                val action = parseSingleJsonCommand(mergedJson, AiAgentUseCase.CameraStateSnapshot())
                    ?: AgentCommand.TextReply(message = "步骤解析失败")

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
        val method = extractJsonField(json, "method") ?: return null
        return when (method) {
            "adjust_beauty" -> {
                val smoothing = extractJsonFloat(json, "smoothing") ?: state.beautySettings.smoothing
                val whitening = extractJsonFloat(json, "whitening") ?: state.beautySettings.whitening
                val slimFace = extractJsonFloat(json, "slim_face") ?: state.beautySettings.slimFace
                val bigEyes = extractJsonFloat(json, "big_eyes") ?: state.beautySettings.bigEyes
                val lipColor = extractJsonFloat(json, "lip_color") ?: state.beautySettings.lipColor
                val blush = extractJsonFloat(json, "blush") ?: state.beautySettings.blush
                val eyebrow = extractJsonFloat(json, "eyebrow") ?: state.beautySettings.eyebrow
                AgentCommand.AdjustBeauty(
                    settings = state.beautySettings.copy(
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
                AgentCommand.SwitchFilter(filterType = resolveFilterType(filterName))
            }
            "switch_style" -> {
                val styleName = extractJsonField(json, "style") ?: "NONE"
                AgentCommand.SwitchStyle(styleFilter = resolveStyleFilter(styleName))
            }
            "switch_scene" -> {
                val scene = extractJsonField(json, "scene") ?: "none"
                AgentCommand.SwitchScene(sceneName = scene)
            }
            "switch_ratio" -> {
                val ratio = extractJsonField(json, "ratio") ?: "full"
                AgentCommand.SwitchRatio(ratio = ratio)
            }
            "adjust_exposure" -> {
                val exposure = extractJsonInt(json, "exposure") ?: 0
                AgentCommand.AdjustExposure(exposure = exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = extractJsonFloat(json, "zoom") ?: 1f
                AgentCommand.AdjustZoom(zoomRatio = zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera()
            "capture", "photo" -> AgentCommand.CapturePhoto()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "switch_mode" -> {
                val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                val mode = runCatching { MediaType.valueOf(modeName) }
                    .getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(mode = mode)
            }
            "text_reply" -> {
                val message = extractJsonField(json, "message") ?: "收到"
                AgentCommand.TextReply(message = message)
            }
            else -> AgentCommand.TextReply(message = "未知命令: $method")
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

    companion object {
        private const val REMOTE_TAG = "RemoteInference"
    }
}