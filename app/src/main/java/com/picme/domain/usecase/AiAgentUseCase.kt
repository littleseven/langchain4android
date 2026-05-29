package com.picme.domain.usecase

import android.content.Context
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.data.remote.kimi.KimiCodingApiClient
import com.picme.domain.agent.AgentOrchestrator
import com.picme.domain.agent.capability.CameraCapability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.agent.remote.AdaptiveStrategySelector
import com.picme.domain.agent.remote.InferenceStrategy
import com.picme.domain.agent.remote.RemoteInferenceEngine
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import com.picme.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Agent 核心用例（Facade）
 *
 * 向后兼容的入口类。内部委托给 [AgentOrchestrator]，保留原有接口不变。
 * 核心变更：删除自动远程 API 回退逻辑，默认 100% 本地执行。
 *
 * @param context Application Context
 * @param agentMode Agent 运行模式，默认 LOCAL
 * @param privacyLevel 隐私级别，默认 STRICT
 * @param localModelId 本地模型 ID，默认 qwen3_0_6b
 * @param codingApiKey Kimi Coding API Key
 * @param codingModel Kimi Coding 模型 ID，默认 kimi-for-coding
 * @param codingBaseUrl Kimi Coding API Base URL
 * @param forceRemote 是否强制使用远程模型（绕过本地模型检查）
 */
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.LOCAL,
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_0_6b",
    codingApiKey: String? = null,
    codingModel: String = "kimi-for-coding",
    codingBaseUrl: String? = null,
    forceRemote: Boolean = false
) {

    private val tag = "PicMe:AiAgent"

    /**
     * Agent Runtime 编排器（单例）
     */
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /**
     * Kimi Coding API 客户端（Claude 格式，唯一远程推理客户端）
     */
    private val codingClient: KimiCodingApiClient? = codingApiKey?.takeIf { it.isNotBlank() }?.let {
        KimiCodingApiClient(
            apiKey = it,
            baseUrl = codingBaseUrl ?: CODING_DEFAULT_BASE_URL,
            enableLogging = true
        )
    }

    /**
     * 远程推理引擎（L2/L3/L4）
     */
    private val remoteEngine: RemoteInferenceEngine? = codingClient?.let {
        RemoteInferenceEngine(codingClient = it, model = codingModel)
    }

    /**
     * 自适应策略选择器（L1 缓存 + 输入特征分析）
     */
    private val strategySelector = AdaptiveStrategySelector()

    /**
     * 当前 Agent 模式
     */
    val currentMode: AiAgentMode = agentMode

    /**
     * 是否强制使用远程模型（绕过本地模型检查）
     */
    private val forceRemoteMode: Boolean = forceRemote

    /**
     * 当前配置的本地模型 ID
     */
    private var currentLocalModelId: String = localModelId

    init {
        orchestrator.configure(
            mode = agentMode,
            modelId = localModelId,
            privacyLevel = privacyLevel
        )
    }

    /**
     * 注册相机 Capability
     */
    fun registerCameraCapability(capability: CameraCapability) {
        orchestrator.registerCapability(capability)
    }

    /**
     * 本地模型是否已加载
     */
    val isLocalModelLoaded: Boolean
        get() = orchestrator.isModelLoaded

    /**
     * 加载本地模型
     *
     * @param modelId 模型 ID，为空时使用当前配置的模型。如果模型 ID 与当前加载的不同，会先卸载旧模型。
     */
    suspend fun loadLocalModel(modelId: String? = null): Result<Unit> {
        val targetModel = modelId ?: currentLocalModelId
        if (targetModel != currentLocalModelId && modelId != null) {
            currentLocalModelId = targetModel
            orchestrator.configure(
                mode = currentMode,
                modelId = targetModel,
                privacyLevel = AiAgentPrivacyLevel.STRICT
            )
        }
        return orchestrator.loadModel(targetModel)
    }

    /**
     * 发送用户指令到 LLM，返回解析后的命令
     *
     * 分层自适应推理策略：
     * - LOCAL/OFF 模式：默认本地推理，forceRemote 时走远程
     * - REMOTE 模式：使用 AdaptiveStrategySelector 选择 L1/L2/L3/L4
     *
     * @param userInput 用户自然语言输入
     * @param currentState 当前相机状态快照，用于上下文感知
     */
    suspend fun processInput(
        userInput: String,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> = withContext(Dispatchers.IO) {
        // 构建 AgentContext
        val agentContext = AgentContext(
            scene = AgentScene.CAMERA,
            beautySettings = currentState.beautySettings,
            filterType = currentState.filterType,
            styleFilter = currentState.styleFilter,
            zoomRatio = currentState.zoomRatio,
            exposureCompensation = currentState.exposureCompensation,
            captureMode = currentState.captureMode,
            isRecording = currentState.isRecording,
            memorySessionId = "camera"
        )

        // 根据模式选择推理路径
        when (currentMode) {
            AiAgentMode.LOCAL, AiAgentMode.OFF -> {
                // 强制远程模式：直接走远程推理
                if (forceRemoteMode) {
                    Logger.d(tag, "[REMOTE] Force remote enabled, bypassing local model")
                    return@withContext processRemote(userInput, agentContext, currentState)
                }
                // 本地推理（传入高质量自定义 system prompt）
                val systemPrompt = buildSystemPrompt(currentState)
                val result = orchestrator.processUserInput(
                    input = userInput,
                    agentContext = agentContext,
                    customSystemPrompt = systemPrompt
                )
                return@withContext result.map { action ->
                    mapAgentActionToLegacyCommand(action)
                }
            }
            AiAgentMode.REMOTE -> {
                // REMOTE 模式：使用分层自适应推理
                return@withContext processRemote(userInput, agentContext, currentState)
            }
        }
    }

    /**
     * 远程推理入口（分层自适应）
     */
    private suspend fun processRemote(
        userInput: String,
        agentContext: AgentContext,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> {
        val engine = remoteEngine
        if (engine == null) {
            Logger.w(REMOTE_TAG, "Remote engine not available (no API key)")
            return Result.success(
                AiAgentCommand.TextReply("请在设置中配置 Kimi Coding API Key 以启用远程推理。")
            )
        }

        // 1. 选择推理策略
        val strategy = strategySelector.selectStrategy(userInput, agentContext)
        Logger.d(REMOTE_TAG, "[STRATEGY] selected=${strategy::class.simpleName}, input=\"$userInput\"")

        return when (strategy) {
            is InferenceStrategy.L1_Cached -> {
                // L1: 缓存命中，零延迟返回
                val command = mapAgentCommandToLegacy(strategy.command)
                Logger.d(REMOTE_TAG, "[L1] cache hit → ${strategy.command::class.simpleName}")
                Result.success(command)
            }

            is InferenceStrategy.L2_BatchFC -> {
                // L2: Batch Function Calling（默认模式）
                val result = engine.processBatch(userInput, agentContext, currentState)
                result.map { commands ->
                    when {
                        commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                        commands.size == 1 -> mapAgentCommandToLegacy(commands.first())
                        else -> AiAgentCommand.BatchExecute(commands.map { mapAgentCommandToLegacy(it) })
                    }
                }
            }

            is InferenceStrategy.L3_PlanExecute -> {
                // L3: Plan-and-Execute
                // 降级到 L2（计划执行需要 UI 层配合，当前版本先走 Batch 格式）
                Logger.d(REMOTE_TAG, "[L3] plan mode → fallback to L2 batch")
                val result = engine.processBatch(userInput, agentContext, currentState)
                result.map { commands ->
                    when {
                        commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                        commands.size == 1 -> mapAgentCommandToLegacy(commands.first())
                        else -> AiAgentCommand.BatchExecute(commands.map { mapAgentCommandToLegacy(it) })
                    }
                }
            }

            is InferenceStrategy.L4_ReAct -> {
                // L4: ReAct 兜底
                val result = engine.react(userInput, currentState)
                result.map { message ->
                    AiAgentCommand.TextReply(message)
                }
            }
        }
    }

    /**
     * 将 AgentCommand 映射为 AiAgentCommand（向后兼容）
     */
    private fun mapAgentCommandToLegacy(command: AgentCommand): AiAgentCommand {
        return when (command) {
            is AgentCommand.AdjustBeauty -> AiAgentCommand.AdjustBeauty(command.settings)
            is AgentCommand.SwitchFilter -> AiAgentCommand.SwitchFilter(command.filterType)
            is AgentCommand.SwitchStyle -> AiAgentCommand.SwitchStyle(command.styleFilter)
            is AgentCommand.SwitchScene -> AiAgentCommand.SwitchScene(command.sceneName)
            is AgentCommand.SwitchRatio -> AiAgentCommand.SwitchRatio(command.ratio)
            is AgentCommand.AdjustExposure -> AiAgentCommand.AdjustExposure(command.exposure)
            is AgentCommand.AdjustZoom -> AiAgentCommand.AdjustZoom(command.zoomRatio)
            is AgentCommand.FlipCamera -> AiAgentCommand.FlipCamera
            is AgentCommand.CapturePhoto -> AiAgentCommand.CapturePhoto
            is AgentCommand.ToggleRecording -> AiAgentCommand.ToggleRecording
            is AgentCommand.SwitchMode -> AiAgentCommand.SwitchMode(command.mode)
            is AgentCommand.TextReply -> AiAgentCommand.TextReply(command.message)
            is AgentCommand.BatchExecute -> AiAgentCommand.BatchExecute(
                command.commands.map { mapAgentCommandToLegacy(it) }
            )
            is AgentCommand.ExecutePlan -> AiAgentCommand.TextReply("执行计划: ${command.plan.description}")
            else -> AiAgentCommand.TextReply("操作已执行")
        }
    }

    /**
     * 清空对话记忆
     */
    suspend fun clearMemory() {
        orchestrator.clearMemory("camera")
    }

    /**
     * 将新的 AgentAction 映射为旧的 AiAgentCommand（向后兼容）
     */
    private fun mapAgentActionToLegacyCommand(action: AgentAction): AiAgentCommand {
        return when (action) {
            is AgentAction.Success -> {
                when (val cmd = action.command) {
                    is AgentCommand.AdjustBeauty -> AiAgentCommand.AdjustBeauty(cmd.settings)
                    is AgentCommand.SwitchFilter -> AiAgentCommand.SwitchFilter(cmd.filterType)
                    is AgentCommand.SwitchStyle -> AiAgentCommand.SwitchStyle(cmd.styleFilter)
                    is AgentCommand.SwitchScene -> AiAgentCommand.SwitchScene(cmd.sceneName)
                    is AgentCommand.SwitchRatio -> AiAgentCommand.SwitchRatio(cmd.ratio)
                    is AgentCommand.AdjustExposure -> AiAgentCommand.AdjustExposure(cmd.exposure)
                    is AgentCommand.AdjustZoom -> AiAgentCommand.AdjustZoom(cmd.zoomRatio)
                    is AgentCommand.FlipCamera -> AiAgentCommand.FlipCamera
                    is AgentCommand.CapturePhoto -> AiAgentCommand.CapturePhoto
                    is AgentCommand.ToggleRecording -> AiAgentCommand.ToggleRecording
                    is AgentCommand.SwitchMode -> AiAgentCommand.SwitchMode(cmd.mode)
                    is AgentCommand.TextReply -> AiAgentCommand.TextReply(cmd.message)
                    else -> AiAgentCommand.TextReply("操作已执行")
                }
            }
            is AgentAction.TextReply -> AiAgentCommand.TextReply(action.message)
            is AgentAction.Error -> AiAgentCommand.TextReply("处理出错了：${action.message}")
        }
    }

    /**
     * 当前相机状态快照
     */
    data class CameraStateSnapshot(
        val beautySettings: BeautySettings = BeautySettings(),
        val filterType: FilterType = FilterType.NONE,
        val styleFilter: StyleFilter = StyleFilter.NONE,
        val zoomRatio: Float = 1f,
        val exposureCompensation: Int = 0,
        val captureMode: MediaType = MediaType.PHOTO,
        val isRecording: Boolean = false
    )

    private fun buildSystemPrompt(state: CameraStateSnapshot): String {
        return buildString {
            appendLine("你是PicMe相机的AI助手小觅。用户通过语音或文字与你交互。")
            appendLine()
            appendLine("【绝对规则 - 必须遵守】")
            appendLine("1. 无论用户要求什么，你的回复永远只输出一行JSON，不要任何其他文字、解释、标点或换行")
            appendLine("2. 控制相机格式: {\"action\":\"命令名\", 参数...}")
            appendLine("3. 聊天回复格式: {\"action\":\"text_reply\",\"message\":\"用中文友好回复\"}")
            appendLine("4. 绝对不要输出<think>标签或思考过程")
            appendLine("5. 绝对不要输出markdown代码块```")
            appendLine()
            appendLine("【当前相机状态】")
            appendLine("美颜=${if (state.beautySettings.enabled) "开" else "关"}, 磨皮=${state.beautySettings.smoothing.toInt()}, 美白=${state.beautySettings.whitening.toInt()}, 瘦脸=${state.beautySettings.slimFace.toInt()}, 大眼=${state.beautySettings.bigEyes.toInt()}, 唇色=${state.beautySettings.lipColor.toInt()}, 腮红=${state.beautySettings.blush.toInt()}, 眉毛=${state.beautySettings.eyebrow.toInt()}")
            appendLine("滤镜=${state.filterType.name}, 风格=${state.styleFilter.name}, 变焦=${state.zoomRatio}x, 曝光=${state.exposureCompensation}, 模式=${state.captureMode.name}")
            appendLine()
            appendLine("【可用命令】")
            appendLine("adjust_beauty: smoothing=0~100(磨皮), whitening=0~100(美白), slim_face=-50~50(瘦脸), big_eyes=0~100(大眼), lip_color=0~100(唇色), blush=0~100(腮红), eyebrow=0~100(眉毛)")
            appendLine("switch_filter: filter=NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("switch_style:  style=NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("switch_scene:  scene=night|moon|none")
            appendLine("switch_ratio:  ratio=4:3|16:9|full")
            appendLine("adjust_exposure: exposure=-2~2")
            appendLine("adjust_zoom:   zoom=0.5~10.0")
            appendLine("flip_camera:   翻转前后摄像头")
            appendLine("capture:       拍照")
            appendLine("toggle_recording: 开始/停止录像")
            appendLine("switch_mode:   mode=PHOTO|VIDEO|PORTRAIT|PRO|DOCUMENT")
            appendLine("text_reply:    普通聊天回复")
            appendLine()
            appendLine("【中文名称映射 - 用户说左边，你必须输出右边】")
            appendLine("滤镜: 无→NONE, 徕卡经典→LEICA_CLASSIC, 徕卡鲜艳/徕卡生动→LEICA_VIBRANT, 徕卡黑白/黑白→LEICA_BW")
            appendLine("滤镜: 胶片金→FILM_GOLD, 胶片富士→FILM_FUJI, 复古/怀旧→VINTAGE, 冷调→COOL, 暖调→WARM")
            appendLine("风格: 无→NONE, 卡通/漫画→TOON, 素描→SKETCH, 色调分离/海报→POSTERIZE, 浮雕→EMBOSS, 交叉线→CROSSHATCH")
            appendLine("模式: 拍照→PHOTO, 录像→VIDEO, 人像→PORTRAIT, 专业→PRO, 文档→DOCUMENT")
            appendLine("场景: 夜景→night, 月亮→moon, 关闭→none")
            appendLine("比例: 4比3→4:3, 16比9→16:9, 全屏→full")
            appendLine()
            appendLine("【示例 - 严格模仿】")
            appendLine("用户: 拍张照片 → {\"action\":\"capture\"}")
            appendLine("用户: 磨皮调到80 → {\"action\":\"adjust_beauty\",\"smoothing\":80}")
            appendLine("用户: 切换徕卡黑白 → {\"action\":\"switch_filter\",\"filter\":\"LEICA_BW\"}")
            appendLine("用户: 你好 → {\"action\":\"text_reply\",\"message\":\"你好呀，我是小觅！\"}")
            appendLine("用户: 瘦脸开到20 → {\"action\":\"adjust_beauty\",\"slim_face\":20}")
            appendLine()
            appendLine("【额外规则】")
            appendLine("- 相对调整: '高一点'='加一点' → 当前值+15左右; '低一点'='减一点' → 当前值-15左右")
            appendLine("- 未提及的参数保持当前值不变")
            appendLine("- 所有message字段必须使用中文")
        }
    }

    private fun parseLlmResponse(content: String, state: CameraStateSnapshot): AiAgentCommand {
        var cleaned = content.trim()

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

        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        Logger.d(tag, "Cleaned response: '$cleaned'")

        val hasJsonAction = cleaned.contains("\"action\"")
        if (!hasJsonAction) {
            Logger.d(tag, "No JSON action found, treating as free chat")
            return AiAgentCommand.TextReply(cleaned.ifBlank { "你好，我是小觅，有什么可以帮你的吗？" })
        }

        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        val json = if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            cleaned
        }

        return try {
            val action = extractJsonField(json, "action") ?: "text_reply"
            when (action) {
                "adjust_beauty" -> {
                    val smoothing = extractJsonFloat(json, "smoothing") ?: state.beautySettings.smoothing
                    val whitening = extractJsonFloat(json, "whitening") ?: state.beautySettings.whitening
                    val slimFace = extractJsonFloat(json, "slim_face") ?: state.beautySettings.slimFace
                    val bigEyes = extractJsonFloat(json, "big_eyes") ?: state.beautySettings.bigEyes
                    val lipColor = extractJsonFloat(json, "lip_color") ?: state.beautySettings.lipColor
                    val blush = extractJsonFloat(json, "blush") ?: state.beautySettings.blush
                    val eyebrow = extractJsonFloat(json, "eyebrow") ?: state.beautySettings.eyebrow
                    AiAgentCommand.AdjustBeauty(
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
                    val filterType = resolveFilterType(filterName)
                    AiAgentCommand.SwitchFilter(filterType)
                }
                "switch_style" -> {
                    val styleName = extractJsonField(json, "style") ?: "NONE"
                    val styleFilter = resolveStyleFilter(styleName)
                    AiAgentCommand.SwitchStyle(styleFilter)
                }
                "switch_scene" -> {
                    val scene = extractJsonField(json, "scene") ?: "none"
                    AiAgentCommand.SwitchScene(scene)
                }
                "switch_ratio" -> {
                    val ratio = extractJsonField(json, "ratio") ?: "full"
                    AiAgentCommand.SwitchRatio(ratio)
                }
                "adjust_exposure" -> {
                    val exposure = extractJsonInt(json, "exposure") ?: 0
                    AiAgentCommand.AdjustExposure(exposure.coerceIn(-2, 2))
                }
                "adjust_zoom" -> {
                    val zoom = extractJsonFloat(json, "zoom") ?: 1f
                    val minZoom = 0.5f
                    AiAgentCommand.AdjustZoom(zoom.coerceAtLeast(minZoom))
                }
                "flip_camera" -> AiAgentCommand.FlipCamera
                "capture", "photo" -> AiAgentCommand.CapturePhoto
                "toggle_recording" -> AiAgentCommand.ToggleRecording
                "switch_mode" -> {
                    val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                    val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                    AiAgentCommand.SwitchMode(mode)
                }
                else -> {
                    val message = extractJsonField(json, "message")
                        ?: cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                    AiAgentCommand.TextReply(message)
                }
            }
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to parse LLM response, fallback to text: $json", exception)
            AiAgentCommand.TextReply(cleaned.ifBlank { "收到你的消息了，但没理解具体意图，请再描述一下~" })
        }
    }

    private fun extractJsonField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val regex = """"$key"\s*:\s*(-?\d+\.?\d*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 将 LLM 输出的 filter 名称解析为 FilterType（支持别名/模糊匹配）
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
     * 将 LLM 输出的 style 名称解析为 StyleFilter（支持别名/模糊匹配）
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

    companion object {
        private const val REMOTE_TAG = "PicMe:RemoteInference"
        private const val CODING_DEFAULT_BASE_URL = "https://api.kimi.com/coding/v1/"
    }
}
