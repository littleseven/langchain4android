package com.mamba.picme.agent.core.facade

import android.content.Context
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.capability.Capability
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentAction
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentErrorCode
import com.mamba.picme.agent.core.api.context.AgentIdGenerator
import com.mamba.picme.agent.core.api.context.PageContext
import com.mamba.picme.agent.core.api.policy.AiAgentMode
import com.mamba.picme.agent.core.api.policy.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.langchain4j.ChatRequest
import com.mamba.picme.agent.core.langchain4j.SystemMessage
import com.mamba.picme.agent.core.langchain4j.UserMessage
import com.mamba.picme.agent.core.platform.llm.local.LlmModelNotFoundException
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.parsing.AgentCommandParser
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent 编排器（统一入口）
 *
 * 合并 V1 + V2 后的唯一编排器。负责：
 * 1. 处理用户输入的完整生命周期
 * 2. 推理引擎选择与调用（本地/远程）
 * 3. LLM 响应解析与命令分发
 * 4. 对话历史管理
 * 5. 场景驱动的模型策略
 *
 * 重构后职责拆分：
 * - AgentOrchestrator：纯编排逻辑（输入处理 → 推理 → 解析 → 分发）
 * - AgentConfigurator：平台特定组件创建与配置管理
 * - CapabilityRegistry：命令路由与执行
 */
class AgentOrchestrator private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AgentOrchestrator? = null

        fun getInstance(context: Context): AgentOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: AgentOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }

    private val tag = "AgentOrchestrator"
    private val configurator = AgentConfigurator(context)

    // 便捷访问器
    private val localLlmEngine get() = configurator.localLlmEngine
    private val memoryManager get() = configurator.memoryManager
    private val sceneManager get() = configurator.sceneManager
    private val promptBuilder get() = configurator.promptBuilder
    private val _capabilityRegistry get() = configurator.capabilityRegistry
    private val intentCache get() = configurator.intentCache
    private val privacyGuard get() = configurator.privacyGuard

    /**
     * 当前活跃场景（可观察）
     */
    val currentScene = sceneManager.currentScene

    /**
     * 注册 Capability（应用级，通常由 PicMeApplication 调用）
     */
    fun registerCapability(capability: Capability) {
        _capabilityRegistry.register(capability)
    }

    /**
     * 获取 CapabilityRegistry
     */
    fun getCapabilityRegistry(): CapabilityRegistry {
        return _capabilityRegistry
    }

    /**
     * 获取最近一次本地 LLM 生成的性能指标。
     *
     * 仅在本地模型成功生成后有效，供 Chat UI 展示性能数据。
     */
    fun getLastLocalGenerationMetrics(): com.mamba.picme.agent.core.platform.llm.local.LlmGenerationMetrics? {
        return localLlmEngine.lastGenerationMetrics
    }

    /**
     * 场景切换
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
        privacyLevel: AiAgentPrivacyLevel,
        remoteConfig: RemoteModelConfig? = null,
        localUseOpencl: Boolean = false
    ) {
        configurator.configure(mode, modelId, privacyLevel, remoteConfig, localUseOpencl)
    }

    /**
     * 加载本地模型
     */
    suspend fun loadModel(modelId: String? = null): Result<Unit> {
        val targetModel = modelId ?: configurator.getCurrentModelId()

        if (!localLlmEngine.isModelAvailable(targetModel, configurator.getContext())) {
            Logger.w(tag, "Model not downloaded: $targetModel")
            return Result.failure(
                LlmModelNotFoundException(
                    "模型未下载，请前往设置 → AI 模型管理下载 $targetModel"
                )
            )
        }

        return localLlmEngine.loadModel(targetModel, configurator.getLocalUseOpencl())
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
        get() = configurator.isModelLoaded

    /**
     * 场景驱动的模型加载策略
     *
     * - CAMERA 场景：清理 KV Cache 和历史记录，降低内存占用
     * - 其他场景：按需加载模型
     */
    private fun applySceneDrivenModelPolicy() {
        val currentScene = sceneManager.currentScene.value
        when (currentScene) {
            SceneManager.Scene.CAMERA -> {
                if (localLlmEngine.isLoaded) {
                    Logger.i(tag, "CAMERA scene: trimming LLM memory (clear history, keep model)")
                    localLlmEngine.trimMemory()
                }
            }
            else -> {
                // 非相机场景：如果本地模式且模型未加载，保持当前状态
                // 实际加载在 processUserInput 中按需触发
            }
        }
    }

    /**
     * 使用 InferenceRouter 处理输入（支持 L2 本地快速通道）
     *
     * 统一走 InferenceRouter 路由，LOCAL 模式下优先尝试 L2 本地快速通道，
     * 超时或失败时自动降级到远程推理。
     *
     * @param input 用户自然语言输入
     * @param agentContext 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @return 推理结果
     */
    suspend fun processInputWithRouter(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null
    ): InferenceResult = withContext(Dispatchers.IO) {
        Logger.d(tag, "Processing input via InferenceRouter: '$input'")

        // 场景驱动的模型管理
        applySceneDrivenModelPolicy()

        Logger.i(tag, "[RouterEntry] mode=${configurator.getAgentMode()}, input='$input', modelLoaded=${localLlmEngine.isLoaded}")

        // 0. L1 缓存查询（本地高频指令快速响应，零 LLM 开销）
        val cachedCommand = intentCache.match(input)
        if (cachedCommand != null) {
            Logger.i(tag, "L1 cache hit for input='$input' -> ${cachedCommand::class.simpleName}")
            return@withContext InferenceResult.Local(command = cachedCommand)
        }

        // 确保本地模型已加载（LOCAL 模式）
        if (configurator.getAgentMode() == AiAgentMode.LOCAL || configurator.getAgentMode() == AiAgentMode.OFF) {
            if (!localLlmEngine.isLoaded) {
                Logger.i(tag, "[RouterEntry] Local model not loaded, attempting load")
                val loadResult = tryLoadModel()
                if (loadResult.isFailure) {
                    Logger.e(tag, "[RouterEntry] Local model load failed, falling back to remote via router")
                } else {
                    Logger.i(tag, "[RouterEntry] Local model loaded successfully")
                }
            } else {
                Logger.i(tag, "[RouterEntry] Local model already loaded")
            }
        } else {
            Logger.i(tag, "[RouterEntry] Mode is ${configurator.getAgentMode()}, skip local model load check")
        }

        // 通过 InferenceRouter 路由
        Logger.i(tag, "[RouterEntry] Calling InferenceRouter.processInput")
        val inferenceResult = try {
            configurator.getInferenceRouter().processInput(input, agentContext, configurator.toolProvider, configurator.toolCallingConfig)
        } catch (exception: Exception) {
            Logger.e(tag, "InferenceRouter failed", exception)
            InferenceResult.Local(
                command = AgentCommand.Error(reason = "推理路由失败：${exception.message ?: "未知错误"}")
            )
        }

        Logger.i(tag, "[RouterEntry] InferenceRouter result: ${inferenceResult::class.simpleName}")

        // 学习：解析成功且非错误命令时写入 L1 缓存
        if (inferenceResult is InferenceResult.Local &&
            inferenceResult.command !is AgentCommand.Error &&
            inferenceResult.command !is AgentCommand.TextReply
        ) {
            intentCache.put(input, inferenceResult.command)
            Logger.d(tag, "L1 cache learned: '$input' -> ${inferenceResult.command::class.simpleName}")
        }

        inferenceResult
    }

    /**
     * 处理用户输入（原始入口，保留兼容）
     *
     * 根据当前 agentMode 选择推理引擎：
     * - LOCAL: 本地 MNN-LLM（默认，符合隐私红线）
     * - REMOTE: 远程 Kimi/Moonshot API（兼容旧模式）
     *
     * @param input 用户自然语言输入
     * @param agentContext 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @param customSystemPrompt 自定义 system prompt（可选）
     * @return Agent 执行结果
     */
    suspend fun processUserInput(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null,
        customSystemPrompt: String? = null
    ): Result<AgentAction> = withContext(Dispatchers.IO) {
        Logger.d(tag, "Processing input: '$input', scene=${sceneManager.currentScene.value}, mode=${configurator.getAgentMode()}")

        // 场景驱动的模型管理
        applySceneDrivenModelPolicy()

        // 0. L1 缓存查询（本地高频指令快速响应，零 LLM 开销）
        val cachedCommand = intentCache.match(input)
        if (cachedCommand != null) {
            Logger.i(tag, "L1 cache hit for input='$input' -> ${cachedCommand::class.simpleName}")
            saveConversation(agentContext.memorySessionId, input, cachedCommand, "")
            return@withContext _capabilityRegistry.dispatch(cachedCommand, agentContext, pageContext)
        }

        // 1. 获取当前场景的 Capability 列表
        val capabilities = _capabilityRegistry.getCapabilitiesForCurrentScene()
        if (capabilities.isEmpty()) {
            Logger.w(tag, "No capabilities available for current scene")
            return@withContext Result.success(
                AgentAction.Error(
                    commandId = AgentIdGenerator.nextId(),
                    errorCode = AgentErrorCode.SCENE_MISMATCH,
                    message = "当前页面暂不支持 AI 控制"
                )
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

        // 打印完整 prompt 用于调试
        val totalPromptLength = systemPrompt.length + userPrompt.length
        val estimatedTokens = totalPromptLength / 2
        Logger.d(tag, "===== SYSTEM PROMPT ===== [len=${systemPrompt.length}, estTokens~${systemPrompt.length / 2}]")
        systemPrompt.lineSequence().forEach { line ->
            Logger.d(tag, line)
        }
        Logger.d(tag, "===== USER PROMPT ===== [len=${userPrompt.length}, estTokens~${userPrompt.length / 2}]")
        Logger.d(tag, userPrompt)
        Logger.d(tag, "===== END PROMPT ===== [totalLen=$totalPromptLength, totalEstTokens~$estimatedTokens, maxTokens=128]")

        // 3. 根据模式选择推理引擎
        val inferenceResult = when (configurator.getAgentMode()) {
            AiAgentMode.LOCAL -> {
                // 本地模式：使用 MNN-LLM
                if (!localLlmEngine.isLoaded) {
                    val loadResult = tryLoadModel()
                    if (loadResult.isFailure) {
                        return@withContext handleModelLoadError(loadResult)
                    }
                }
                Logger.d(tag, "Using local LLM (MNN-LLM)")
                val responseResult = try {
                    Result.success(
                        localLlmEngine.chat(
                            ChatRequest(
                                messages = listOf(
                                    SystemMessage(systemPrompt),
                                    UserMessage(userPrompt)
                                )
                            )
                        ).aiMessage.text
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
                return@withContext responseResult.fold(
                    onSuccess = { rawResponse ->
                        handleLlmResponse(rawResponse, input, agentContext, pageContext, agentContext.memorySessionId)
                    },
                    onFailure = { error ->
                        Logger.e(tag, "LLM inference failed (mode=${configurator.getAgentMode()})", error)
                        Result.success(
                            AgentAction.Error(
                                commandId = AgentIdGenerator.nextId(),
                                errorCode = AgentErrorCode.INTERNAL_ERROR,
                                message = "推理失败：${error.message ?: "未知错误"}"
                            )
                        )
                    }
                )
            }
            AiAgentMode.REMOTE -> {
                // 远程模式：通过 InferenceRouter 进行混合编排
                Logger.d(tag, "Using InferenceRouter for REMOTE mode")
                try {
                    configurator.getInferenceRouter().processInput(input, agentContext, configurator.toolProvider, configurator.toolCallingConfig)
                } catch (exception: Exception) {
                    Logger.e(tag, "Remote inference failed, falling back to local", exception)
                    // 远程失败时回退到本地
                    if (!localLlmEngine.isLoaded) {
                        val loadResult = tryLoadModel()
                        if (loadResult.isFailure) {
                            return@withContext handleModelLoadError(loadResult)
                        }
                    }
                    val fallbackResult = try {
                        Result.success(
                            localLlmEngine.chat(
                                ChatRequest(
                                    messages = listOf(
                                        SystemMessage(systemPrompt),
                                        UserMessage(userPrompt)
                                    )
                                )
                            ).aiMessage.text
                        )
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    return@withContext fallbackResult.fold(
                        onSuccess = { rawResponse ->
                            handleLlmResponse(rawResponse, input, agentContext, pageContext, agentContext.memorySessionId)
                        },
                        onFailure = { error ->
                            Logger.e(tag, "Fallback local inference also failed", error)
                            Result.success(
                                AgentAction.Error(
                                    commandId = AgentIdGenerator.nextId(),
                                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                                    message = "推理失败：${error.message ?: "未知错误"}"
                                )
                            )
                        }
                    )
                }
            }
            AiAgentMode.OFF -> {
                Logger.w(tag, "Agent is OFF")
                return@withContext Result.success(
                    AgentAction.Error(
                        commandId = AgentIdGenerator.nextId(),
                        errorCode = AgentErrorCode.INVALID_REQUEST,
                        message = "AI Agent 已关闭"
                    )
                )
            }
        }

        // 4. 处理 InferenceResult（REMOTE 模式）
        return@withContext handleInferenceResult(inferenceResult, input, agentContext, pageContext)
    }

    /**
     * 尝试加载模型
     */
    private suspend fun tryLoadModel(): Result<Unit> {
        return if (configurator.getCurrentModelId().isNotBlank()) {
            loadModel(configurator.getCurrentModelId())
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
        return Result.success(
            AgentAction.Error(
                commandId = AgentIdGenerator.nextId(),
                errorCode = AgentErrorCode.INTERNAL_ERROR,
                message = message
            )
        )
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
        // 保留原始输出给解析器，避免提前清理 think 标签导致 JSON/关键词信息丢失。
        val responseForHistory = filterThinkTags(rawResponse)
        Logger.i(tag, "LLM raw response: ${rawResponse.replace("\n", "\\n")}")

        // 解析命令
        val command = AgentCommandParser.parseLlmResponse(rawResponse, agentContext)
        Logger.i(tag, "Parsed command: ${command::class.simpleName}")

        // L1 缓存学习：解析成功且非错误命令时写入缓存
        if (command !is AgentCommand.Error && command !is AgentCommand.TextReply) {
            intentCache.put(userInput, command)
            Logger.d(tag, "L1 cache learned: '$userInput' -> ${command::class.simpleName}")
        }

        // 保存对话历史
        saveConversation(memorySessionId, userInput, command, responseForHistory)

        // 分发到 Capability 执行
        return _capabilityRegistry.dispatch(command, agentContext, pageContext)
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
     * 根据 method 字段解析为具体命令
     */
    fun parseCommandByMethod(
        method: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        return AgentCommandParser.parseCommandByMethod(method, json, context, fallbackText)
    }

    /**
     * 相机场景回退到远程推理
     */
    private suspend fun fallbackToRemote(
        input: String,
        systemPrompt: String,
        userPrompt: String,
        agentContext: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Falling back to remote inference in camera scene")
        return try {
            val result = configurator.getInferenceRouter().processInput(input, agentContext, configurator.toolProvider, configurator.toolCallingConfig)
            handleInferenceResult(result, input, agentContext, pageContext)
        } catch (exception: Exception) {
            Logger.e(tag, "Remote fallback failed", exception)
            Result.success(
                AgentAction.Error(
                    commandId = AgentIdGenerator.nextId(),
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "相机场景下本地模型已卸载，远程推理失败：${exception.message ?: "未知错误"}"
                )
            )
        }
    }

    /**
     * 处理 InferenceResult 并转换为 AgentAction
     */
    private suspend fun handleInferenceResult(
        inferenceResult: InferenceResult,
        userInput: String,
        agentContext: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val memorySessionId = agentContext.memorySessionId

        return when (inferenceResult) {
            is InferenceResult.Local -> {
                Logger.d(tag, "Handling Local result: ${inferenceResult.command::class.simpleName}")
                saveConversation(memorySessionId, userInput, inferenceResult.command, "")
                _capabilityRegistry.dispatch(inferenceResult.command, agentContext, pageContext)
            }
            is InferenceResult.Batch -> {
                Logger.d(tag, "Handling Batch result: ${inferenceResult.commands.size} commands")
                if (inferenceResult.commands.isEmpty()) {
                    Result.success(
                        AgentAction.Error(
                            commandId = AgentIdGenerator.nextId(),
                            errorCode = AgentErrorCode.INVALID_REQUEST,
                            message = "未解析到任何命令"
                        )
                    )
                } else {
                    // 批量执行：将第一个命令作为主结果，其余通过 BatchExecute 包装
                    val firstCommand = inferenceResult.commands.first()
                    val remainingCommands = inferenceResult.commands.drop(1)
                    val finalCommand = if (remainingCommands.isNotEmpty()) {
                        AgentCommand.BatchExecute(
                            commands = listOf(firstCommand) + remainingCommands
                        )
                    } else {
                        firstCommand
                    }
                    saveConversation(memorySessionId, userInput, finalCommand, "")
                    _capabilityRegistry.dispatch(finalCommand, agentContext, pageContext)
                }
            }
            is InferenceResult.Plan -> {
                Logger.d(tag, "Handling Plan result: ${inferenceResult.plan.steps.size} steps")
                val planCommand = AgentCommand.ExecutePlan(plan = inferenceResult.plan)
                saveConversation(memorySessionId, userInput, planCommand, inferenceResult.plan.description)
                _capabilityRegistry.dispatch(planCommand, agentContext, pageContext)
            }
            is InferenceResult.Chat -> {
                Logger.d(tag, "Handling Chat result: ${inferenceResult.message}")
                val textCommand = AgentCommand.TextReply(message = inferenceResult.message)
                saveConversation(memorySessionId, userInput, textCommand, inferenceResult.message)
                Result.success(
                    AgentAction.TextReply(
                        commandId = AgentIdGenerator.nextId(),
                        message = inferenceResult.message
                    )
                )
            }
        }
    }
}


