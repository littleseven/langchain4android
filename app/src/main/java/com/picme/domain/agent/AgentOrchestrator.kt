package com.picme.domain.agent

import android.content.Context
import com.picme.core.common.Logger
import com.picme.domain.agent.capability.Capability
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent 编排器（统一入口）
 *
 * 合并 V1 + V2 后的唯一编排器。负责：
 * 1. 本地模型生命周期管理
 * 2. 构建 system prompt（支持自定义覆盖）
 * 3. 调用 LocalLlmEngine 进行本地推理
 * 4. 解析 LLM 响应为 AgentCommand
 * 5. 通过 CapabilityRegistry 路由到对应 Capability 执行
 *
 * @param context Application Context
 */
class AgentOrchestrator private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AgentOrchestrator? = null

        fun getInstance(context: Context): AgentOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: AgentOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }

    private val tag = "PicMe:AgentOrchestrator"

    // 核心组件
    private val localLlmEngine = LocalLlmEngine(context)
    private val memoryManager = MemoryManager(context)
    private val privacyGuard = PrivacyGuard()
    private val sceneManager = SceneManager.getInstance()
    private val promptBuilder = PromptBuilder(sceneManager)
    private val capabilityRegistry = CapabilityRegistry.getInstance()

    // 配置状态
    private var agentMode: AiAgentMode = AiAgentMode.LOCAL
    private var currentModelId: String = "qwen3_0_6b"

    /**
     * 当前活跃场景（可观察）
     */
    val currentScene = sceneManager.currentScene

    /**
     * 注册 Capability
     */
    fun registerCapability(capability: Capability) {
        capabilityRegistry.register(capability)
    }

    /**
     * 场景切换
     *
     * @param scene 目标场景
     * @param saveToHistory 是否保存到历史
     */
    fun transitionToScene(scene: SceneManager.Scene, saveToHistory: Boolean = true) {
        sceneManager.transitionTo(scene, saveToHistory)
        Logger.i(tag, "Transitioned to scene: $scene")
    }

    /**
     * 返回上一场景
     */
    fun navigateBack(): Boolean {
        return sceneManager.navigateBack()
    }

    /**
     * 初始化配置
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: AiAgentPrivacyLevel
    ) {
        this.agentMode = mode
        this.currentModelId = modelId
        privacyGuard.updateConfig(privacyLevel, mode)
        Logger.i(tag, "Configured: mode=$mode, model=$modelId, privacy=$privacyLevel")
    }

    /**
     * 加载本地模型
     */
    suspend fun loadModel(modelId: String? = null): Result<Unit> {
        val targetModel = modelId ?: currentModelId

        if (!localLlmEngine.isModelAvailable(targetModel, context)) {
            Logger.w(tag, "Model not downloaded: $targetModel")
            return Result.failure(
                LlmModelNotFoundException(
                    "模型未下载，请前往设置 → AI 模型管理下载 $targetModel"
                )
            )
        }

        return localLlmEngine.loadModel(targetModel).onSuccess {
            currentModelId = targetModel
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        localLlmEngine.unload()
    }

    /**
     * 模型是否已加载
     */
    val isModelLoaded: Boolean
        get() = localLlmEngine.isLoaded

    /**
     * 处理用户输入
     *
     * 根据当前 agentMode 选择推理引擎：
     * - LOCAL: 本地 MNN-LLM（默认，符合隐私红线）
     * - REMOTE: 远程 Kimi/Moonshot API（兼容旧模式）
     *
     * @param input 用户自然语言输入
     * @param agentContext 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @param customSystemPrompt 自定义 system prompt（可选）。传入后将优先使用，
     *                           适用于需要高质量专用 prompt 的场景（如相机页面）。
     * @return Agent 执行结果
     */
    suspend fun processUserInput(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null,
        customSystemPrompt: String? = null
    ): Result<AgentAction> = withContext(Dispatchers.IO) {
        Logger.d(tag, "Processing input: '$input', scene=${sceneManager.currentScene.value}, mode=$agentMode")

        // 1. 获取当前场景的 Capability 列表
        val capabilities = capabilityRegistry.getCapabilitiesForCurrentScene()
        if (capabilities.isEmpty()) {
            Logger.w(tag, "No capabilities available for current scene")
            return@withContext Result.success(
                AgentAction.Error("当前页面暂不支持 AI 控制")
            )
        }

        // 2. 构建 system prompt（优先使用自定义，否则用 PromptBuilder 生成）
        val systemPrompt = customSystemPrompt
            ?: promptBuilder.buildSystemPrompt(capabilities, agentContext)

        val userPrompt = buildString {
            appendLine("用户输入: $input")
            appendLine()
            appendLine("请只输出一行JSON，不要其他内容:")
        }

        // 3. 根据模式选择推理引擎
        val responseResult = when (agentMode) {
            AiAgentMode.LOCAL -> {
                // 本地模式：使用 MNN-LLM
                if (!localLlmEngine.isLoaded) {
                    val loadResult = tryLoadModel()
                    if (loadResult.isFailure) {
                        return@withContext handleModelLoadError(loadResult)
                    }
                }
                Logger.d(tag, "Using local LLM (MNN-LLM)")
                localLlmEngine.generateWithSystem(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = 512
                )
            }
            AiAgentMode.REMOTE -> {
                // 远程模式：由上层 AiAgentUseCase 处理，此处不直接调用
                Logger.w(tag, "REMOTE mode should be handled by AiAgentUseCase")
                return@withContext Result.success(
                    AgentAction.Error("REMOTE 模式请通过 AiAgentUseCase 调用")
                )
            }
            AiAgentMode.OFF -> {
                Logger.w(tag, "Agent is OFF")
                return@withContext Result.success(
                    AgentAction.Error("AI Agent 已关闭")
                )
            }
        }

        val memorySessionId = agentContext.memorySessionId

        responseResult.fold(
            onSuccess = { rawResponse ->
                handleLlmResponse(rawResponse, input, agentContext, pageContext, memorySessionId)
            },
            onFailure = { error ->
                Logger.e(tag, "LLM inference failed (mode=$agentMode)", error)
                Result.success(
                    AgentAction.Error("推理失败：${error.message ?: "未知错误"}")
                )
            }
        )
    }

    /**
     * 尝试加载模型
     */
    private suspend fun tryLoadModel(): Result<Unit> {
        return if (currentModelId.isNotBlank()) {
            loadModel(currentModelId)
        } else {
            Result.failure(IllegalStateException("未配置模型 ID"))
        }
    }

    /**
     * 处理模型加载错误
     */
    private fun handleModelLoadError(loadResult: Result<Unit>): Result<AgentAction> {
        val error = loadResult.exceptionOrNull()
        val message = if (error is LlmModelNotFoundException) {
            error.message ?: "模型未下载"
        } else {
            "模型加载失败：${error?.message ?: "未知错误"}"
        }
        return Result.success(AgentAction.Error(message))
    }

    /**
     * 处理 LLM 响应
     */
    private suspend fun handleLlmResponse(
        rawResponse: String,
        userInput: String,
        agentContext: AgentContext,
        pageContext: PageContext?,
        memorySessionId: String
    ): Result<AgentAction> {
        // 过滤 Qwen3 的 <think> 标签
        val response = filterThinkTags(rawResponse)
        Logger.i(tag, "LLM raw response: $response")

        // 解析命令
        val command = AgentCommandParser.parseLlmResponse(response, agentContext)
        Logger.i(tag, "Parsed command: ${command::class.simpleName}")

        // 保存对话历史
        saveConversation(memorySessionId, userInput, command, response)

        // 分发到 Capability 执行
        return capabilityRegistry.dispatch(command, agentContext, pageContext)
    }

    /**
     * 保存对话
     */
    private suspend fun saveConversation(
        sessionId: String,
        userInput: String,
        command: AgentCommand,
        rawResponse: String
    ) {
        val assistantResponse = if (command is AgentCommand.TextReply) {
            command.message
        } else {
            rawResponse
        }
        memoryManager.appendConversation(sessionId, userInput, assistantResponse)
    }

    /**
     * 过滤 Qwen3 模型的 <think> 标签
     */
    private fun filterThinkTags(response: String): String {
        val thinkStart = response.indexOf("<think>")
        if (thinkStart == -1) return response.trim()

        val thinkEnd = response.indexOf("</think>", thinkStart)
        return if (thinkEnd != -1) {
            (response.substring(0, thinkStart) + response.substring(thinkEnd + 8)).trim()
        } else {
            val afterTag = response.substring(thinkStart + 7).trim()
            val beforeTag = response.substring(0, thinkStart).trim()
            if (afterTag.contains("{")) afterTag else beforeTag
        }
    }

    /**
     * 清空当前场景的对话历史
     */
    suspend fun clearMemory(sessionId: String) {
        memoryManager.clearHistory(sessionId)
    }

    /**
     * 解析 LLM 响应（暴露给测试使用）
     */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand {
        return AgentCommandParser.parseLlmResponse(response, context)
    }

    /**
     * 根据 action 字段解析为具体命令
     */
    fun parseCommandByAction(
        action: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        return AgentCommandParser.parseCommandByAction(action, json, context, fallbackText)
    }
}
