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
import com.mamba.picme.agent.core.api.ChatRequest
import com.mamba.picme.agent.core.api.UserMessage
import com.mamba.picme.agent.core.api.SystemMessage
import com.mamba.picme.agent.core.api.StreamingChatResponseHandler
import com.mamba.picme.agent.core.platform.llm.local.LlmGenerationMetrics
import com.mamba.picme.agent.core.platform.llm.local.LlmModelNotFoundException
import com.mamba.picme.agent.core.platform.llm.remote.StreamChatResult
import com.mamba.picme.agent.core.platform.llm.remote.StreamMetrics
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Agent 编排器（统一入口）
 *
 * **线程模型**：
 * 所有专有线程池由 [ThreadPoolManager] 集中管理，四线程池完全隔离：
 * - **编排线程**（PicMe-Orchestrator-Thread）：双线程，处理用户输入的整个生命周期
 * - **LLM 推理线程**（PicMe-LLM-Model-Thread）：单线程，模型加载和推理
 * - **DataStore 线程**（PicMe-DataStore-Thread）：单线程，对话历史持久化
 * - **网络线程**（PicMe-Network-Thread）：单线程，远程 HTTP API 调用
 *
 * 各线程池完全隔离，无直接依赖关系。数据持久化为 fire-and-forget 异步操作，
 * 不阻塞推理与编排流程。
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

    private val orchestratorDispatcher = ThreadPoolManager.getInstance().orchestratorDispatcher

    /**
     * 后台作用域：用于 fire-and-forget 异步操作（如对话历史保存）。
     * SupervisorJob 确保单个后台任务失败不影响其他任务。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob())

    // 便捷访问器
    private val localLlmEngine get() = configurator.localLlmEngine
    private val memoryManager get() = configurator.memoryManager
    private val sceneManager get() = configurator.sceneManager
    private val promptBuilder get() = configurator.localPromptBuilder
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
     * 压入模式临时覆盖。
     * 此后所有推理路由将强制使用 [mode]，直到 [popModeOverride] 被调用。
     *
     * 典型场景：飞书远程控制强制使用 REMOTE 模式，无论用户本地设置如何。
     * 支持嵌套：多次压入需要对应次数弹出。
     */
    fun pushModeOverride(mode: AiAgentMode) {
        configurator.pushModeOverride(mode)
    }

    /**
     * 弹出模式临时覆盖。
     * 恢复栈为空时返回持久化模式。
     */
    fun popModeOverride() {
        configurator.popModeOverride()
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
            else -> { /* 非相机场景：保持当前状态 */ }
        }
    }

    /**
     * 使用 LocalPipeline 处理输入（支持 L2 本地快速通道）
     *
     * 统一走 LocalPipeline 路由，LOCAL 模式下优先尝试 L2 本地快速通道。
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
    ): InferenceResult = withContext(orchestratorDispatcher) {
        Logger.d(tag, "Processing input via LocalPipeline: '$input'")

        // 场景驱动的模型管理
        applySceneDrivenModelPolicy()

        Logger.i(tag, "[RouterEntry] mode=${configurator.getAgentMode()}, input='$input', modelLoaded=${localLlmEngine.isLoaded}")

        // 确保本地模型已加载（LOCAL 模式）
        if (configurator.getAgentMode() == AiAgentMode.LOCAL || configurator.getAgentMode() == AiAgentMode.OFF) {
            if (!localLlmEngine.isLoaded) {
                Logger.i(tag, "[RouterEntry] Local model not loaded, attempting load")
                val loadResult = tryLoadModel()
                if (loadResult.isFailure) {
                    Logger.e(tag, "[RouterEntry] Local model load failed")
                } else {
                    Logger.i(tag, "[RouterEntry] Local model loaded successfully")
                }
            } else {
                Logger.i(tag, "[RouterEntry] Local model already loaded")
            }
        } else {
            Logger.i(tag, "[RouterEntry] Mode is ${configurator.getAgentMode()}, skip local model load check")
        }

        // 通过推理管道路由
        Logger.i(tag, "[RouterEntry] Calling pipeline processInput")
        val inferenceResult = try {
            when (configurator.getAgentMode()) {
                AiAgentMode.LOCAL -> configurator.getLocalPipeline().processInput(input, agentContext)
                AiAgentMode.REMOTE -> configurator.getRemotePipeline().processInput(input, agentContext)
                AiAgentMode.OFF -> InferenceResult.Chat(message = "AI Agent 已关闭")
            }
        } catch (exception: Exception) {
            Logger.e(tag, "Pipeline routing failed", exception)
            InferenceResult.Local(
                command = AgentCommand.Error(reason = "推理路由失败：${exception.message ?: "未知错误"}")
            )
        }

        Logger.i(tag, "[RouterEntry] Pipeline result: ${inferenceResult::class.simpleName}")

        // 学习：解析成功且非错误命令时写入 L1 缓存
        if (inferenceResult is InferenceResult.Local &&
            inferenceResult.command !is AgentCommand.Error &&
            inferenceResult.command !is AgentCommand.TextReply
        ) {
            intentCache.put(input, inferenceResult.command)
            Logger.d(tag, "L1 cache learned: '$input' -> ${inferenceResult.command::class.simpleName}")
        }

        // 保存对话到 MemoryManager（供后续历史上下文使用）
        saveInferenceResultToMemory(input, inferenceResult, agentContext.memorySessionId)

        inferenceResult
    }

    // ── 流式自由聊天 ─────────────────────────────────────────────

    /**
     * 流式自由聊天
     *
     * 跳过 L1/L2/L3/L4 指令路由，直接进行纯文本流式生成。
     * LOCAL 模式：使用 [LocalLlmEngine.chat] 流式接口
     * REMOTE 模式：使用 [RemoteOrchestrator.processChatStreaming]
     *
     * @param input 用户输入
     * @param agentContext Agent 上下文
     * @param onToken 每个 token 的回调
     * @return 流式结果
     */
    suspend fun streamChat(
        input: String,
        agentContext: AgentContext,
        onToken: (String) -> Unit
    ): Result<StreamChatResult> {
        Logger.d(tag, "streamChat: mode=${configurator.getAgentMode()}, input='$input'")

        return when (configurator.getAgentMode()) {
            AiAgentMode.LOCAL, AiAgentMode.OFF -> {
                streamChatLocal(input, agentContext, onToken)
            }
            AiAgentMode.REMOTE -> {
                streamChatRemote(input, agentContext, onToken)
            }
        }
    }

    private suspend fun streamChatLocal(
        input: String,
        agentContext: AgentContext,
        onToken: (String) -> Unit
    ): Result<StreamChatResult> {
        // 确保模型已加载
        if (!localLlmEngine.isLoaded) {
            val loadResult = tryLoadModel()
            if (loadResult.isFailure) {
                return Result.failure(
                    RuntimeException("本地模型未加载：${loadResult.exceptionOrNull()?.message ?: "未知错误"}")
                )
            }
        }

        // 使用 L2 命令 prompt，让模型能输出指令（如 navigate_to、switch_filter 等）
        val capabilities = _capabilityRegistry.getCapabilitiesForCurrentScene()
        val systemPrompt = promptBuilder.buildL2SystemPrompt(capabilities, agentContext)
        val messages = memoryManager.buildContextMessages(
            agentContext.memorySessionId, systemPrompt, input
        )

        val startTime = System.currentTimeMillis()

        return try {
            val result = suspendCoroutine<Result<StreamChatResult>> { continuation ->
                localLlmEngine.chat(
                    ChatRequest(messages = messages),
                    object : StreamingChatResponseHandler {
                        private val accumulatedText = StringBuilder()

                        override fun onPartialResponse(partialResponse: String) {
                            accumulatedText.append(partialResponse)
                            onToken(partialResponse)
                        }

                        override fun onCompleteResponse(completeResponse: com.mamba.picme.agent.core.api.ChatResponse) {
                            val latencyMs = System.currentTimeMillis() - startTime
                            val metrics = completeResponse.metadata
                            Logger.d(tag, "streamChatLocal OK latency=${latencyMs}ms")
                            continuation.resume(
                                Result.success(
                                    StreamChatResult(
                                        fullResponse = completeResponse.aiMessage.text,
                                        metrics = StreamMetrics(
                                            latencyMs = latencyMs,
                                            promptTokens = metrics?.promptTokens,
                                            completionTokens = metrics?.completionTokens
                                        )
                                    )
                                )
                            )
                        }

                        override fun onError(error: Throwable) {
                            val latencyMs = System.currentTimeMillis() - startTime
                            Logger.e(tag, "streamChatLocal ERR latency=${latencyMs}ms", error)
                            continuation.resume(Result.failure(error))
                        }
                    }
                )
            }
            // 流式完成后，从响应文本中解析命令
            result.map { streamResult ->
                val commands = LocalCommandParser.parseL2BatchResponse(
                    streamResult.fullResponse, agentContext
                )
                Logger.d(tag, "streamChatLocal: parsed ${commands.size} commands from response")
                streamResult.copy(commands = commands)
            }
        } catch (e: Exception) {
            Logger.e(tag, "streamChatLocal setup failed", e)
            Result.failure(e)
        }
    }

    private suspend fun streamChatRemote(
        input: String,
        agentContext: AgentContext,
        onToken: (String) -> Unit
    ): Result<StreamChatResult> {
        return try {
            configurator.getRemoteOrchestrator()
                .processChatStreaming(input, agentContext, onToken)
        } catch (e: Exception) {
            Logger.e(tag, "streamChatRemote failed, falling back to local", e)
            // 远程失败回退到本地
            streamChatLocal(input, agentContext, onToken)
        }
    }

    /**
     * 将 InferenceResult 保存到 MemoryManager
     */
    private suspend fun saveInferenceResultToMemory(
        userInput: String,
        result: InferenceResult,
        sessionId: String
    ) {
        when (result) {
            is InferenceResult.Local -> {
                val responseText = when (val cmd = result.command) {
                    is AgentCommand.TextReply -> cmd.message
                    else -> result.responseText.ifBlank { AgentCommand.getMethodName(cmd) }
                }
                saveConversation(sessionId, userInput, result.command, responseText)
            }
            is InferenceResult.Batch -> {
                val firstCommand = result.commands.firstOrNull()
                if (firstCommand != null) {
                    val finalCommand = if (result.commands.size > 1) {
                        AgentCommand.BatchExecute(commands = result.commands)
                    } else {
                        firstCommand
                    }
                    saveConversation(sessionId, userInput, finalCommand, "")
                }
            }
            is InferenceResult.Plan -> {
                val planCommand = AgentCommand.ExecutePlan(plan = result.plan)
                saveConversation(sessionId, userInput, planCommand, result.plan.description)
            }
            is InferenceResult.Chat -> {
                val textCommand = AgentCommand.TextReply(message = result.message)
                saveConversation(sessionId, userInput, textCommand, result.message)
            }
        }
    }

    /**
     * 处理用户输入（原始入口，保留兼容）
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
    ): Result<AgentAction> = withContext(orchestratorDispatcher) {
        Logger.d(tag, "Processing input: '$input', scene=${sceneManager.currentScene.value}, mode=${configurator.getAgentMode()}")

        // 场景驱动的模型管理
        applySceneDrivenModelPolicy()

        // 0. L1 缓存查询
        val cachedCommand = intentCache.match(input)
        if (cachedCommand != null) {
            Logger.i(tag, "L1 cache hit for input='$input' -> ${cachedCommand::class.simpleName}")
            saveConversation(agentContext.memorySessionId, input, cachedCommand, "")
            return@withContext _capabilityRegistry.dispatch(cachedCommand, agentContext, pageContext)
        }

        // 1. 获取当前场景的 Capability 列表
        val capabilities = _capabilityRegistry.getCapabilitiesForCurrentScene()

        // 仅 LOCAL 模式需要 Capability 列表；REMOTE 模式通过云端 LLM 自主编排，不需要本地 Capability
        if (configurator.getAgentMode() == AiAgentMode.LOCAL && capabilities.isEmpty()) {
            Logger.w(tag, "No capabilities available for current scene in LOCAL mode")
            return@withContext Result.success(
                AgentAction.Error(
                    commandId = AgentIdGenerator.nextId(),
                    errorCode = AgentErrorCode.SCENE_MISMATCH,
                    message = "当前页面暂不支持 AI 控制"
                )
            )
        }

        // 2. 构建 system prompt（仅 LOCAL 模式使用）
        val systemPrompt = customSystemPrompt
            ?: configurator.localPromptBuilder.buildSystemPrompt(capabilities, agentContext)

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
                // 构建带历史上下文的 messages
                val localMessages = memoryManager.buildContextMessages(
                    agentContext.memorySessionId, systemPrompt, input
                )
                val responseResult = try {
                    Result.success(
                        localLlmEngine.chat(
                            ChatRequest(
                                messages = localMessages
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
                // 远程模式：通过 RemotePipeline 进行编排
                Logger.d(tag, "Using RemotePipeline for REMOTE mode")
                try {
                    configurator.getRemotePipeline().processInput(input, agentContext)
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
                        // 构建带历史上下文的 messages
                        val fallbackMessages = memoryManager.buildContextMessages(
                            agentContext.memorySessionId, systemPrompt, input
                        )
                        Result.success(
                            localLlmEngine.chat(
                                ChatRequest(
                                    messages = fallbackMessages
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
        val responseForHistory = filterThinkTags(rawResponse)
        Logger.i(tag, "LLM raw response: ${rawResponse.replace("\n", "\\n")}")

        // 解析命令（使用 LocalCommandParser）
        val command = LocalCommandParser.parseLlmResponse(rawResponse, agentContext)
        Logger.i(tag, "Parsed command: ${command::class.simpleName}")

        // L1 缓存学习
        if (command !is AgentCommand.Error && command !is AgentCommand.TextReply) {
            intentCache.put(userInput, command)
            Logger.d(tag, "L1 cache learned: '$userInput' -> ${command::class.simpleName}")
        }

        saveConversation(memorySessionId, userInput, command, responseForHistory)
        return _capabilityRegistry.dispatch(command, agentContext, pageContext)
    }

    /**
     * 保存对话
     */
    /**
     * 异步保存对话历史（fire-and-forget）。
     *
     * 不阻塞调用方，对话历史在后台 DataStore 线程上异步持久化。
     * 即使保存失败也不影响当前推理响应。
     */
    private fun saveConversation(
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
        backgroundScope.launch {
            memoryManager.appendConversation(sessionId, userInput, assistantResponse)
        }
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
        return LocalCommandParser.parseLlmResponse(response, context)
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
        return LocalCommandParser.parseCommandByMethod(method, json, context, fallbackText)
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
