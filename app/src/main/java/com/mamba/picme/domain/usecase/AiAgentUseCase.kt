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
import com.mamba.picme.agent.core.remote.prompt.RemotePromptBuilder
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
 * @param localModelId 本地模型 ID，默认 qwen3_5_2b（下划线格式）
 * @param localUseOpencl 本地模型是否启用 OpenCL 后端
 * @param remoteConfig 用户自定义远程模型配置（完整配置，包含 modelId/apiKey/baseUrl/gatewayToken）
 * @param forceRemote 是否强制使用远程模型（绕过本地模型检查）
 * @param gatewayToken 腾讯云 SCF Gateway Token（兜底用）
 */
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.LOCAL,
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_5_2b", // 下划线格式，与 ModelManager 注册表一致
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
            context = context,
            remoteConfig = effectiveRemoteConfig,
            promptBuilder = RemotePromptBuilder(SceneManager.getInstance())
        )
    }

    /**
     * 是否使用兜底 Gateway（用于限频检测）
     */
    private val isUsingFallbackGateway: Boolean = userRemoteConfig == null

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
     * - REMOTE 模式：直接调用 processBatch
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

                // 本地推理：优先使用 processInputWithRouter 的 L2 本地快速通道（支持 JSON 数组）
                Logger.i(tag, "[UseCase] LOCAL mode, calling processInputWithRouter for input='$userInput'")
                val inferenceResult = orchestrator.processInputWithRouter(
                    input = userInput,
                    agentContext = agentContext
                )
                Logger.i(tag, "[UseCase] processInputWithRouter returned: ${inferenceResult::class.simpleName}")
                return@withContext handleInferenceResult(inferenceResult, userInput, agentContext, currentState)
            }
            AiAgentMode.REMOTE -> {
                // REMOTE 模式：使用分层自适应推理
                Logger.i(tag, "[UseCase] REMOTE mode, calling processRemote for input='$userInput'")
                return@withContext processRemote(userInput, agentContext, currentState)
            }
        }
    }

    /**
     * 处理 InferenceResult 并转换为 AiAgentCommand
     */
    private fun handleInferenceResult(
        inferenceResult: InferenceResult,
        userInput: String,
        agentContext: AgentContext,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> {
        return when (inferenceResult) {
            is InferenceResult.Local -> {
                val command = inferenceResult.command
                Logger.d(tag, "Local result: ${command::class.simpleName}")
                Result.success(mapAgentCommandToLegacy(command))
            }
            is InferenceResult.Batch -> {
                Logger.d(tag, "Batch result: ${inferenceResult.commands.size} commands")
                if (inferenceResult.commands.isEmpty()) {
                    Result.success(AiAgentCommand.TextReply("未识别到有效命令"))
                } else {
                    val commands = inferenceResult.commands.map { mapAgentCommandToLegacy(it) }
                    Result.success(
                        if (commands.size == 1) commands.first() else AiAgentCommand.BatchExecute(commands)
                    )
                }
            }
            is InferenceResult.Plan -> {
                Logger.d(tag, "Plan result: ${inferenceResult.plan.steps.size} steps")
                val commands = inferenceResult.plan.steps.mapNotNull { step ->
                    // PlanStep 的 action 已经是 AgentCommand，直接映射
                    mapAgentCommandToLegacy(step.action)
                }
                if (commands.isEmpty()) {
                    Result.success(AiAgentCommand.TextReply("未识别到有效命令"))
                } else {
                    Result.success(
                        if (commands.size == 1) commands.first() else AiAgentCommand.BatchExecute(commands)
                    )
                }
            }
            is InferenceResult.Chat -> {
                Logger.d(tag, "Chat result: ${inferenceResult.message}")
                Result.success(AiAgentCommand.TextReply(inferenceResult.message))
            }
        }
    }

    /**
     * 远程推理入口（简化版：直接调用 processBatch）
     */
    private suspend fun processRemote(
        userInput: String,
        agentContext: AgentContext,
        currentState: CameraStateSnapshot
    ): Result<AiAgentCommand> {
        Logger.d(REMOTE_TAG, "[REMOTE] processRemote for input=\"$userInput\"")
    
        return try {
            val batchResult = remoteOrchestrator.processBatch(
                userInput = userInput,
                context = agentContext
            )
            val commands = batchResult.commands
            Result.success(
                when {
                    commands.isEmpty() -> AiAgentCommand.TextReply("未识别到有效命令")
                    commands.size == 1 -> mapAgentCommandToLegacy(commands.first())
                    else -> AiAgentCommand.BatchExecute(commands.map { mapAgentCommandToLegacy(it) })
                }
            )
        } catch (error: Exception) {
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
            // 系统/外部 App 命令
            is AgentCommand.LaunchApp -> AiAgentCommand.TextReply("打开应用: ${command.appName ?: command.packageName}")
            is AgentCommand.OpenSystemSettings -> AiAgentCommand.TextReply("打开设置: ${command.setting}")
            // 无障碍动作
            is AgentCommand.PerformAccessibilityAction -> AiAgentCommand.TextReply("无障碍动作: ${command.action}")
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
                    // 系统/外部 App 命令
                    is AgentCommand.LaunchApp -> AiAgentCommand.TextReply("打开应用: ${cmd.appName ?: cmd.packageName}")
                    is AgentCommand.OpenSystemSettings -> AiAgentCommand.TextReply("打开设置: ${cmd.setting}")
                    // 无障碍动作
                    is AgentCommand.PerformAccessibilityAction -> AiAgentCommand.TextReply("无障碍动作: ${cmd.action}")
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

    companion object {
        private const val REMOTE_TAG = "RemoteInference"
        private const val CODING_DEFAULT_BASE_URL = "https://api.kimi.com/coding/v1/"
    }
}
