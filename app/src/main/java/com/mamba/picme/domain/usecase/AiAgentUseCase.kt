package com.mamba.picme.domain.usecase

import android.content.Context
import com.mamba.picme.BuildConfig
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.api.context.MediaType
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.platform.llm.remote.RemoteOrchestrator
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.inference.AdaptiveStrategySelector
import com.mamba.picme.agent.core.runtime.inference.InferenceStrategy
import com.mamba.picme.agent.core.runtime.parsing.PromptBuilder
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.features.camera.capability.CameraCapability
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
 * @param localModelId 本地模型 ID，默认 qwen3_1_7b（下划线格式）
 * @param localUseOpencl 本地模型是否启用 OpenCL 后端
 * @param remoteConfig 用户自定义远程模型配置（完整配置，包含 modelId/apiKey/baseUrl/gatewayToken）
 * @param forceRemote 是否强制使用远程模型（绕过本地模型检查）
 * @param gatewayToken 腾讯云 SCF Gateway Token（兜底用）
 */
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.LOCAL,
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_1_7b", // 下划线格式，与 ModelManager 注册表一致
    localUseOpencl: Boolean = false,
    remoteConfig: RemoteModelConfig? = null,
    forceRemote: Boolean = false,
    gatewayToken: String? = null
) {

    private val tag = "AiAgent"

    /**
     * Agent Runtime 编排器（单例）
     */
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /**
     * 用户自定义远程模型配置（高优先级）
     * 只要 remoteConfig 有 baseUrl + modelId 就使用，apiKey 由调用链路自行处理
     */
    private val userRemoteConfig: RemoteModelConfig? =
        remoteConfig?.takeIf { it.baseUrl.isNotBlank() && it.modelId.isNotBlank() }

    /**
     * 兜底远程模型配置（腾讯云 SCF Gateway，无需用户配置）
     * 使用 BuildConfig 中内嵌的默认 Token
     */
    private val fallbackRemoteConfig: RemoteModelConfig =
        RemoteModelConfig.TENCENT_SCF_DEFAULT.copy(
            gatewayToken = gatewayToken?.takeIf { it.isNotBlank() }
                ?: BuildConfig.TENCENT_SCF_APP_TOKEN
        )

    /**
     * 当前实际使用的远程模型配置
     */
    private val effectiveRemoteConfig: RemoteModelConfig
        get() = userRemoteConfig ?: fallbackRemoteConfig

    /**
     * 远程编排器（L2/L3/L4）
     * 使用 PromptBuilder 统一构建 prompt
     */
    private val remoteOrchestrator: RemoteOrchestrator by lazy {
        RemoteOrchestrator(
            remoteConfig = effectiveRemoteConfig,
            promptBuilder = PromptBuilder(SceneManager.getInstance())
        )
    }

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
    private var currentLocalUseOpencl: Boolean = localUseOpencl

    init {
        Logger.i(tag, "AiAgentUseCase init: remoteConfig=${remoteConfig?.modelId ?: "null"}, " +
            "baseUrl=${remoteConfig?.baseUrl?.take(40) ?: "null"}, " +
            "apiKey=${if (remoteConfig?.apiKey.isNullOrBlank()) "empty" else "set"}, " +
            "localUseOpencl=$localUseOpencl, " +
            "gatewayToken=${if (remoteConfig?.gatewayToken.isNullOrBlank()) "empty" else "set"}, " +
            "effectiveBaseUrl=${effectiveRemoteConfig.baseUrl.take(40)}, " +
            "isUsingFallbackGateway=$isUsingFallbackGateway")
        orchestrator.configure(
            mode = agentMode,
            modelId = localModelId,
            privacyLevel = privacyLevel,
            remoteConfig = effectiveRemoteConfig,
            localUseOpencl = localUseOpencl
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
     * 卸载本地模型，释放内存
     */
    fun unloadLocalModel() {
        orchestrator.unloadModel()
    }

    /**
     * 加载本地模型
     *
     * @param modelId 模型 ID，为空时使用当前配置的模型。如果模型 ID 与当前加载的不同，会先卸载旧模型。
     */
    suspend fun loadLocalModel(modelId: String? = null, useOpencl: Boolean? = null): Result<Unit> {
        val targetModel = modelId ?: currentLocalModelId
        val targetUseOpencl = useOpencl ?: currentLocalUseOpencl
        if (targetModel != currentLocalModelId || targetUseOpencl != currentLocalUseOpencl) {
            currentLocalModelId = targetModel
            currentLocalUseOpencl = targetUseOpencl
            orchestrator.configure(
                mode = currentMode,
                modelId = targetModel,
                privacyLevel = AiAgentPrivacyLevel.STRICT,
                localUseOpencl = targetUseOpencl
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
                val result = remoteOrchestrator.processBatch(
                    userInput = userInput,
                    context = agentContext
                )
                Result.success(
                    when (result) {
                        is InferenceResult.Batch -> {
                            when {
                                result.commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                                result.commands.size == 1 -> mapAgentCommandToLegacy(result.commands.first())
                                else -> AiAgentCommand.BatchExecute(result.commands.map { mapAgentCommandToLegacy(it) })
                            }
                        }
                        else -> AiAgentCommand.TextReply("推理结果类型不匹配")
                    }
                )
            }

            is InferenceStrategy.L3_PlanExecute -> {
                Logger.d(REMOTE_TAG, "[L3] plan mode → fallback to L2 batch")
                val result = remoteOrchestrator.processBatch(
                    userInput = userInput,
                    context = agentContext
                )
                Result.success(
                    when (result) {
                        is InferenceResult.Batch -> {
                            when {
                                result.commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                                result.commands.size == 1 -> mapAgentCommandToLegacy(result.commands.first())
                                else -> AiAgentCommand.BatchExecute(result.commands.map { mapAgentCommandToLegacy(it) })
                            }
                        }
                        else -> AiAgentCommand.TextReply("推理结果类型不匹配")
                    }
                )
            }

            is InferenceStrategy.L4_ReAct -> {
                val result = remoteOrchestrator.processChat(
                    userInput = userInput,
                    context = agentContext
                )
                Result.success(
                    when (result) {
                        is InferenceResult.Chat -> AiAgentCommand.TextReply(result.message)
                        else -> AiAgentCommand.TextReply("推理结果类型不匹配")
                    }
                )
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
            is AgentCommand.Delay -> AiAgentCommand.Delay(command.delayMs)
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
                    is AgentCommand.Delay -> AiAgentCommand.Delay(cmd.delayMs)
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
            is AgentAction.BatchResult -> {
                AiAgentCommand.BatchExecute(action.results.map { mapAgentActionToLegacyCommand(it) })
            }
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
            append("规则:1.控制{\"method\":\"命令\",\"params\":{参数...}} 2.聊天{\"method\":\"text_reply\",\"params\":{\"message\":\"回复\"}} 3.禁止think和markdown 4.导航用navigate_to/go_back。")
            append("状态:美颜${if (state.beautySettings.enabled) "开" else "关"}磨${state.beautySettings.smoothing.toInt()}白${state.beautySettings.whitening.toInt()}瘦${state.beautySettings.slimFace.toInt()}眼${state.beautySettings.bigEyes.toInt()}唇${state.beautySettings.lipColor.toInt()}腮${state.beautySettings.blush.toInt()}眉${state.beautySettings.eyebrow.toInt()}滤${state.filterType.name}风${state.styleFilter.name}焦${state.zoomRatio}x曝${state.exposureCompensation}模${state.captureMode.name}。")
            append("命令:adjust_beauty(smoothing/whitening/slim_face/big_eyes/lip_color/blush/eyebrow) ")
            append("switch_filter(NONE/CLASSIC/VIBRANT/BW/GOLD/FUJI/VINTAGE/COOL/WARM) ")
            append("switch_style(NONE/TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH) ")
            append("switch_scene(night/moon/none) switch_ratio(4:3/16:9/full) adjust_exposure(-2~2) adjust_zoom(0.5~10) ")
            append("flip_camera capture toggle_recording switch_mode(PHOTO/VIDEO/PRO/DOCUMENT) ")
            append("navigate_to(camera/gallery/settings/debug/model_center) go_back text_reply。")
            append("例:拍照片→{\"method\":\"capture\",\"params\":{}} 磨皮80→{\"method\":\"adjust_beauty\",\"params\":{\"smoothing\":80}} 暖色滤镜→{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}} 冷色滤镜→{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}} 复古滤镜→{\"method\":\"switch_filter\",\"params\":{\"filter\":\"VINTAGE\"}} 徕卡黑白→{\"method\":\"switch_filter\",\"params\":{\"filter\":\"BW\"}} 去相册→{\"method\":\"navigate_to\",\"params\":{\"destination\":\"gallery\"}} 返回→{\"method\":\"go_back\",\"params\":{}} 你好→{\"method\":\"text_reply\",\"params\":{\"message\":\"你好\"}}。")
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

        val hasJsonMethod = cleaned.contains("\"method\"")
        if (!hasJsonMethod) {
            Logger.d(tag, "No JSON method found, treating as free chat")
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
            val method = extractJsonField(json, "method") ?: "text_reply"
            val mergedJson = mergeParamsIntoJson(json)
            when (method) {
                "adjust_beauty" -> {
                    val smoothing = extractJsonFloat(mergedJson, "smoothing") ?: state.beautySettings.smoothing
                    val whitening = extractJsonFloat(mergedJson, "whitening") ?: state.beautySettings.whitening
                    val slimFace = extractJsonFloat(mergedJson, "slim_face") ?: state.beautySettings.slimFace
                    val bigEyes = extractJsonFloat(mergedJson, "big_eyes") ?: state.beautySettings.bigEyes
                    val lipColor = extractJsonFloat(mergedJson, "lip_color") ?: state.beautySettings.lipColor
                    val blush = extractJsonFloat(mergedJson, "blush") ?: state.beautySettings.blush
                    val eyebrow = extractJsonFloat(mergedJson, "eyebrow") ?: state.beautySettings.eyebrow
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
                    val filterName = extractJsonField(mergedJson, "filter") ?: "NONE"
                    val filterType = resolveFilterType(filterName)
                    AiAgentCommand.SwitchFilter(filterType)
                }
                "switch_style" -> {
                    val styleName = extractJsonField(mergedJson, "style") ?: "NONE"
                    val styleFilter = resolveStyleFilter(styleName)
                    AiAgentCommand.SwitchStyle(styleFilter)
                }
                "switch_scene" -> {
                    val scene = extractJsonField(mergedJson, "scene") ?: "none"
                    AiAgentCommand.SwitchScene(scene)
                }
                "switch_ratio" -> {
                    val ratio = extractJsonField(mergedJson, "ratio") ?: "full"
                    AiAgentCommand.SwitchRatio(ratio)
                }
                "adjust_exposure" -> {
                    val exposure = extractJsonInt(mergedJson, "exposure") ?: 0
                    AiAgentCommand.AdjustExposure(exposure.coerceIn(-2, 2))
                }
                "adjust_zoom" -> {
                    val zoom = extractJsonFloat(mergedJson, "zoom") ?: 1f
                    val minZoom = 0.5f
                    AiAgentCommand.AdjustZoom(zoom.coerceAtLeast(minZoom))
                }
                "flip_camera" -> AiAgentCommand.FlipCamera
                "capture", "photo" -> AiAgentCommand.CapturePhoto
                "toggle_recording" -> AiAgentCommand.ToggleRecording
                "switch_mode" -> {
                    val modeName = extractJsonField(mergedJson, "mode") ?: "PHOTO"
                    val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                    AiAgentCommand.SwitchMode(mode)
                }
                "navigate_to" -> {
                    val destination = extractJsonField(mergedJson, "destination") ?: ""
                    AiAgentCommand.NavigateTo(destination)
                }
                "go_back" -> AiAgentCommand.GoBack
                else -> {
                    val message = extractJsonField(mergedJson, "message")
                        ?: cleaned.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                    AiAgentCommand.TextReply(message)
                }
            }
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to parse LLM response, fallback to text: $json", exception)
            AiAgentCommand.TextReply(cleaned.ifBlank { "收到你的消息了，但没理解具体意图，请再描述一下~" })
        }
    }

    private fun mergeParamsIntoJson(json: String): String {
        val paramsObject = extractJsonObject(json, "params") ?: return json
        val paramsContent = paramsObject.removePrefix("{").removeSuffix("}").trim()
        if (paramsContent.isBlank()) return json

        val paramsPattern = """\"params\"\s*:\s*\{[^{}]*\}""".toRegex()
        val withoutParams = json.replace(paramsPattern, "").trim()
        val lastBrace = withoutParams.lastIndexOf('}')
        if (lastBrace <= 0) return json

        val prefix = withoutParams.substring(0, lastBrace).trimEnd().trimEnd(',')
        val separator = if (prefix.endsWith('{')) "" else ","
        return "$prefix$separator$paramsContent}"
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex == -1) return null

        val colonIndex = json.indexOf(':', keyIndex)
        if (colonIndex == -1) return null

        var braceStart = colonIndex + 1
        while (braceStart < json.length && json[braceStart].isWhitespace()) braceStart++
        if (braceStart >= json.length || json[braceStart] != '{') return null

        var depth = 1
        var pos = braceStart + 1
        while (pos < json.length && depth > 0) {
            when (json[pos]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    pos++
                    while (pos < json.length && json[pos] != '"') {
                        if (json[pos] == '\\' && pos + 1 < json.length) pos++
                        pos++
                    }
                }
            }
            pos++
        }

        return if (depth == 0) json.substring(braceStart, pos) else null
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
