package com.picme.domain.agent

import android.content.Context
import com.picme.core.common.Logger
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
 * Agent 编排器 V2
 *
 * 核心升级：
 * 1. 支持多场景（SceneManager 集成）
 * 2. 分层 Prompt 构建（PromptBuilder）
 * 3. 页面上下文感知（PageContext）
 * 4. Capability V2 支持（场景绑定）
 *
 * @param context Application Context
 */
class AgentOrchestratorV2 private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AgentOrchestratorV2? = null

        fun getInstance(context: Context): AgentOrchestratorV2 {
            return instance ?: synchronized(this) {
                instance ?: AgentOrchestratorV2(context.applicationContext).also { instance = it }
            }
        }
    }

    private val tag = "PicMe:AgentOrchestratorV2"

    // 核心组件
    private val localLlmEngine = LocalLlmEngine(context)
    private val memoryManager = com.picme.domain.agent.MemoryManager(context)
    private val privacyGuard = PrivacyGuard()
    private val sceneManager = SceneManager.getInstance()
    private val promptBuilder = PromptBuilder(sceneManager)
    private val capabilityRegistry = CapabilityRegistryV2.getInstance()

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
    fun registerCapability(capability: com.picme.domain.agent.capability.CapabilityV2) {
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
     * 处理用户输入（V2 版本，支持页面上下文）
     *
     * @param input 用户自然语言输入
     * @param agentContext 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @return Agent 执行结果
     */
    suspend fun processUserInput(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction> = withContext(Dispatchers.IO) {
        Logger.d(tag, "Processing input: '$input', scene=${sceneManager.currentScene.value}")

        // 1. 检查本地模型状态
        if (!localLlmEngine.isLoaded) {
            val loadResult = tryLoadModel()
            if (loadResult.isFailure) {
                return@withContext handleModelLoadError(loadResult)
            }
        }

        // 2. 获取当前场景的 Capability 列表
        val capabilities = capabilityRegistry.getCapabilitiesForCurrentScene()
        if (capabilities.isEmpty()) {
            Logger.w(tag, "No capabilities available for current scene")
            return@withContext Result.success(
                AgentAction.Error("当前页面暂不支持 AI 控制")
            )
        }

        // 3. 构建分层 system prompt
        val systemPrompt = promptBuilder.buildSystemPrompt(capabilities, agentContext)

        // 4. 本地推理（简化版，不带历史）
        val prompt = "$systemPrompt\n\n用户：$input\n助手："
        val responseResult = localLlmEngine.generate(prompt, maxTokens = 128)
        val memorySessionId = agentContext.memorySessionId

        responseResult.fold(
            onSuccess = { rawResponse ->
                handleLlmResponse(rawResponse, input, agentContext, pageContext, memorySessionId)
            },
            onFailure = { error ->
                Logger.e(tag, "Local LLM inference failed", error)
                Result.success(
                    AgentAction.Error("本地模型推理失败：${error.message ?: "未知错误"}")
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
        val message = when (error) {
            is LlmModelNotFoundException -> error.message ?: "模型未下载"
            else -> "模型加载失败：${error?.message ?: "未知错误"}"
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
        val assistantResponse = when (command) {
            is AgentCommand.TextReply -> command.message
            else -> rawResponse
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
}
