package com.picme.domain.usecase

import android.content.Context
import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
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
 * @param localModelId 本地模型 ID，默认 qwen3_1_7b
 * @param codingApiKey Kimi Coding API Key
 * @param codingModel Kimi Coding 模型 ID，默认 kimi-for-coding
 * @param codingBaseUrl Kimi Coding API Base URL
 * @param forceRemote 是否强制使用远程模型（绕过本地模型检查）
 */
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.LOCAL,
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_1_7b",
    codingApiKey: String? = null,
    codingModel: String = "kimi-for-coding",
    codingBaseUrl: String? = null,
    forceRemote: Boolean = false,
    gatewayToken: String? = null
) {

    private val tag = "AiAgent"

    /**
     * Agent Runtime 编排器（单例）
     */
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /**
     * 用户自定义远程模型配置（高优先级：用户自己的 API Key）
     */
    private val userRemoteConfig: com.picme.domain.model.RemoteModelConfig? =
        codingApiKey?.takeIf { it.isNotBlank() }?.let { apiKey ->
            com.picme.domain.model.RemoteModelConfig(
                modelId = codingModel,
                apiKey = apiKey,
                baseUrl = codingBaseUrl ?: CODING_DEFAULT_BASE_URL
            )
        }

    /**
     * 兜底远程模型配置（腾讯云 SCF Gateway，无需用户配置）
     * 使用 BuildConfig 中内嵌的默认 Token
     */
    private val fallbackRemoteConfig: com.picme.domain.model.RemoteModelConfig =
        com.picme.domain.model.RemoteModelConfig.TENCENT_SCF_DEFAULT.copy(
            gatewayToken = gatewayToken?.takeIf { it.isNotBlank() }
                ?: com.picme.BuildConfig.TENCENT_SCF_APP_TOKEN
        )

    /**
     * 远程推理引擎（L2/L3/L4）
     * 优先使用用户自定义配置，未配置时使用兜底 SCF Gateway
     */
    private val remoteEngine: RemoteInferenceEngine = RemoteInferenceEngine(
        remoteConfig = userRemoteConfig ?: fallbackRemoteConfig
    )

    /**
     * 是否使用兜底 Gateway（用于限频检测）
     */
    private val isUsingFallbackGateway: Boolean = userRemoteConfig == null

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

                // 打印完整 prompt 用于调试
                val promptLength = systemPrompt.length
                val estimatedTokens = promptLength / 2  // 中文字符约 1-2 token，取保守估计
                Logger.d(tag, "===== CAMERA SYSTEM PROMPT ===== [len=$promptLength, estTokens~$estimatedTokens]")
                systemPrompt.lineSequence().forEach { line ->
                    Logger.d(tag, line)
                }
                Logger.d(tag, "===== END CAMERA PROMPT ===== [totalLen=$promptLength, totalEstTokens~$estimatedTokens, maxTokens=256]")

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
        // 1. 选择推理策略
        val strategy = strategySelector.selectStrategy(userInput, agentContext)
        Logger.d(REMOTE_TAG, "[STRATEGY] selected=${strategy::class.simpleName}, input=\"$userInput\"")

        val result = when (strategy) {
            is InferenceStrategy.L1_Cached -> {
                val command = mapAgentCommandToLegacy(strategy.command)
                Logger.d(REMOTE_TAG, "[L1] cache hit → ${strategy.command::class.simpleName}")
                Result.success(command)
            }

            is InferenceStrategy.L2_BatchFC -> {
                remoteEngine.processBatch(userInput, agentContext, currentState).map { commands ->
                    when {
                        commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                        commands.size == 1 -> mapAgentCommandToLegacy(commands.first())
                        else -> AiAgentCommand.BatchExecute(commands.map { mapAgentCommandToLegacy(it) })
                    }
                }
            }

            is InferenceStrategy.L3_PlanExecute -> {
                Logger.d(REMOTE_TAG, "[L3] plan mode → fallback to L2 batch")
                remoteEngine.processBatch(userInput, agentContext, currentState).map { commands ->
                    when {
                        commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                        commands.size == 1 -> mapAgentCommandToLegacy(commands.first())
                        else -> AiAgentCommand.BatchExecute(commands.map { mapAgentCommandToLegacy(it) })
                    }
                }
            }

            is InferenceStrategy.L4_ReAct -> {
                remoteEngine.react(userInput, currentState).map { message ->
                    AiAgentCommand.TextReply(message)
                }
            }
        }

        // 兜底 Gateway 限频检测：如果返回 429/433，提示用户配置自己的 API Key
        return result.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                val isRateLimited = error.message?.let { msg ->
                    msg.contains("429") || msg.contains("433")
                } == true
                if (isUsingFallbackGateway && isRateLimited) {
                    Logger.w(REMOTE_TAG, "Fallback gateway rate limited")
                    Result.success(
                        AiAgentCommand.TextReply(
                            "请求太频繁了，请稍后再试。免费额度每分钟有限制次数，如需无限次使用请在设置中配置自己的远程模型 API Key。"
                        )
                    )
                } else {
                    Result.failure(error)
                }
            }
        )
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
            is AgentCommand.NavigateTo -> AiAgentCommand.NavigateTo(command.destination)
            is AgentCommand.GoBack -> AiAgentCommand.GoBack
            is AgentCommand.ExecutePlan -> AiAgentCommand.TextReply("执行计划: ${command.plan.description}")
            // Gallery 命令
            is AgentCommand.ViewMedia -> AiAgentCommand.NavigateTo("gallery")
            is AgentCommand.DeleteMedia -> AiAgentCommand.TextReply("请在相册中删除照片")
            is AgentCommand.ShareMedia -> AiAgentCommand.TextReply("请在相册中分享照片")
            is AgentCommand.SelectMedia -> AiAgentCommand.TextReply("请在相册中选择照片")
            is AgentCommand.SearchMedia -> AiAgentCommand.TextReply("搜索照片: ${command.query}")
            is AgentCommand.SwitchViewMode -> AiAgentCommand.TextReply("切换相册视图")
            is AgentCommand.FavoriteMedia -> AiAgentCommand.TextReply("收藏照片")
            // 设置命令
            is AgentCommand.ChangeTheme -> AiAgentCommand.TextReply("切换主题: ${command.theme}")
            is AgentCommand.ChangeLanguage -> AiAgentCommand.TextReply("切换语言: ${command.language}")
            is AgentCommand.DownloadModel -> AiAgentCommand.TextReply("下载模型: ${command.modelId}")
            is AgentCommand.SwitchFaceEngine -> AiAgentCommand.TextReply("切换人脸引擎: ${command.engine}")
            is AgentCommand.ToggleSetting -> AiAgentCommand.TextReply("切换设置: ${command.settingKey}")
            // 错误/未知命令 —— 明确报告，不允许掩盖
            is AgentCommand.Error -> AiAgentCommand.TextReply("命令错误: ${command.reason}")
            is AgentCommand.Unknown -> AiAgentCommand.TextReply("未知命令: ${command.raw}")
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
                    is AgentCommand.NavigateTo -> AiAgentCommand.NavigateTo(cmd.destination)
                    is AgentCommand.GoBack -> AiAgentCommand.GoBack
                    is AgentCommand.TextReply -> AiAgentCommand.TextReply(cmd.message)
                    is AgentCommand.BatchExecute -> AiAgentCommand.BatchExecute(
                        cmd.commands.map { mapAgentCommandToLegacy(it) }
                    )
                    is AgentCommand.ExecutePlan -> AiAgentCommand.TextReply("执行计划: ${cmd.plan.description}")
                    // Gallery 命令
                    is AgentCommand.ViewMedia -> AiAgentCommand.NavigateTo("gallery")
                    is AgentCommand.DeleteMedia -> AiAgentCommand.TextReply("请在相册中删除照片")
                    is AgentCommand.ShareMedia -> AiAgentCommand.TextReply("请在相册中分享照片")
                    is AgentCommand.SelectMedia -> AiAgentCommand.TextReply("请在相册中选择照片")
                    is AgentCommand.SearchMedia -> AiAgentCommand.TextReply("搜索照片: ${cmd.query}")
                    is AgentCommand.SwitchViewMode -> AiAgentCommand.TextReply("切换相册视图")
                    is AgentCommand.FavoriteMedia -> AiAgentCommand.TextReply("收藏照片")
                    // 设置命令
                    is AgentCommand.ChangeTheme -> AiAgentCommand.TextReply("切换主题: ${cmd.theme}")
                    is AgentCommand.ChangeLanguage -> AiAgentCommand.TextReply("切换语言: ${cmd.language}")
                    is AgentCommand.DownloadModel -> AiAgentCommand.TextReply("下载模型: ${cmd.modelId}")
                    is AgentCommand.SwitchFaceEngine -> AiAgentCommand.TextReply("切换人脸引擎: ${cmd.engine}")
                    is AgentCommand.ToggleSetting -> AiAgentCommand.TextReply("切换设置: ${cmd.settingKey}")
                    // 错误/未知 —— 明确报告，不允许掩盖
                    is AgentCommand.Error -> AiAgentCommand.TextReply("命令错误: ${cmd.reason}")
                    is AgentCommand.Unknown -> AiAgentCommand.TextReply("未知命令: ${cmd.raw}")
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
            append("你是PicMe助手小觅。只输出一行JSON。")
            append("规则:1.控制{\"action\":\"命令\",参数...} 2.聊天{\"action\":\"text_reply\",\"message\":\"回复\"} 3.禁止think和markdown 4.导航用navigate_to/go_back。")
            append("状态:美颜${if (state.beautySettings.enabled) "开" else "关"}磨${state.beautySettings.smoothing.toInt()}白${state.beautySettings.whitening.toInt()}瘦${state.beautySettings.slimFace.toInt()}眼${state.beautySettings.bigEyes.toInt()}唇${state.beautySettings.lipColor.toInt()}腮${state.beautySettings.blush.toInt()}眉${state.beautySettings.eyebrow.toInt()}滤${state.filterType.name}风${state.styleFilter.name}焦${state.zoomRatio}x曝${state.exposureCompensation}模${state.captureMode.name}。")
            append("命令:adjust_beauty(smoothing/whitening/slim_face/big_eyes/lip_color/blush/eyebrow) ")
            append("switch_filter(NONE/CLASSIC/VIBRANT/BW/GOLD/FUJI/VINTAGE/COOL/WARM) ")
            append("switch_style(NONE/TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH) ")
            append("switch_scene(night/moon/none) switch_ratio(4:3/16:9/full) adjust_exposure(-2~2) adjust_zoom(0.5~10) ")
            append("flip_camera capture toggle_recording switch_mode(PHOTO/VIDEO/PORTRAIT/PRO/DOCUMENT) ")
            append("navigate_to(camera/gallery/settings/debug/model_center) go_back text_reply。")
            append("例:拍照片→{\"action\":\"capture\"} 磨皮80→{\"action\":\"adjust_beauty\",\"smoothing\":80} 徕卡黑白→{\"action\":\"switch_filter\",\"filter\":\"BW\"} 去相册→{\"action\":\"navigate_to\",\"destination\":\"gallery\"} 返回→{\"action\":\"go_back\"} 你好→{\"action\":\"text_reply\",\"message\":\"你好\"}。")
            append("规则:相对调整±15 未提及参数不变 message用中文 导航必须用navigate_to/go_back")
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
                "navigate_to" -> {
                    val destination = extractJsonField(json, "destination") ?: ""
                    AiAgentCommand.NavigateTo(destination)
                }
                "go_back" -> AiAgentCommand.GoBack
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
        private const val REMOTE_TAG = "RemoteInference"
        private const val CODING_DEFAULT_BASE_URL = "https://api.kimi.com/coding/v1/"
    }
}
