package com.mamba.picme.agent.core.platform.llm.local

import android.content.Context
import com.mamba.picme.agent.core.api.AiMessage
import com.mamba.picme.agent.core.api.ChatLanguageModel
import com.mamba.picme.agent.core.api.ChatMessage
import com.mamba.picme.agent.core.api.ChatRequest
import com.mamba.picme.agent.core.api.ChatResponse
import com.mamba.picme.agent.core.api.ChatResponseMetadata
import com.mamba.picme.agent.core.api.StreamingChatLanguageModel
import com.mamba.picme.agent.core.api.StreamingChatResponseHandler
import com.mamba.picme.agent.core.api.SystemMessage
import com.mamba.picme.agent.core.api.ToolExecutionResultMessage
import com.mamba.picme.agent.core.api.UserMessage
import com.mamba.picme.agent.core.platform.llm.local.MnnLlmClient.NativeReleaseTarget
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.mnn.MnnGlobalReleaseLock
import com.mamba.picme.agent.core.platform.mnn.MnnResourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM 模型未找到异常
 *
 * 用于区分"模型未下载"和"其他加载错误"，便于 UI 层引导用户下载。
 */
class LlmModelNotFoundException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 本地 LLM 推理引擎
 *
 * 封装 MNN-LLM 客户端，支持多模型管理和懒加载。
 *
 * **线程模型**：所有模型操作（load/unload/trimMemory/generate）统一在专用单线程上执行，
 * 避免 Compose 协程重组取消和多线程并发竞争导致的 MNN 全局状态冲突。
 *
 * @param context Application Context
 */
class LocalLlmEngine(private val context: Context) : ChatLanguageModel, StreamingChatLanguageModel {

    private val tag = "LocalLlmEngine"
    private val client = MnnLlmClient(context)
    private val engineMutex = Mutex()
    private val resourceManager = MnnResourceManager.getInstance(context)

    /**
     * 专用单线程调度器，用于串行化所有模型操作。
     *
     * 关键设计：
     * 1. 单一明确线程：所有 load/unload/generate/trimMemory 都在此线程上串行执行
     * 2. 不外溢：不切换到 Dispatchers.IO 或其他线程池
     * 3. 不受 Compose 重组影响：该调度器独立于任何 Compose CoroutineScope
     * 4. 生命周期跟随 LocalLlmEngine 实例（通常由 AgentOrchestrator 单例持有）
     */
    private val modelExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PicMe-LLM-Model-Thread").apply {
            isDaemon = true
        }
    }
    private val modelDispatcher: CoroutineDispatcher = modelExecutor.asCoroutineDispatcher()

    /**
     * 当前加载的模型 ID
     *
     * **注意**：仅在 [modelDispatcher] 线程上读写，通过 [engineMutex] 保护。
     */
    private var currentModelId: String? = null

    /**
     * 模型是否已加载
     */
    val isLoaded: Boolean
        get() = client.isLoaded

    /**
     * 是否已向 ResourceManager 注册引用
     */
    private val isRegistered = AtomicBoolean(false)

    init {
        resourceManager.registerSoftTrimListener(::onSoftTrim)
        resourceManager.registerSafeUnloadListener(::onSafeUnload)

        // [P0-4] 注册分级释放回调，供 releaseAtLevel("llm", level) 使用
        resourceManager.registerLlmReleaseCallback(MnnResourceManager.ReleaseLevel.SOFT) {
            enqueueTrimMemory()
        }
        resourceManager.registerLlmReleaseCallback(MnnResourceManager.ReleaseLevel.SESSION) {
            if (client.isLoaded) {
                client.releaseNative(NativeReleaseTarget.KV_CACHE)
            }
        }
        resourceManager.registerLlmReleaseCallback(MnnResourceManager.ReleaseLevel.FULL) {
            enqueueUnload()
        }
    }

    /**
     * 加载指定模型
     *
     * 所有操作在专用单线程 [modelDispatcher] 上串行执行，避免：
     * - Compose 协程重组取消（LeftCompositionCancellationException）
     * - 多线程并发竞争（两个线程同时进入 loadModel）
     * - IO 线程池分散执行导致的状态不一致
     *
     * @param modelId 模型注册表中的 key，如 "qwen3_1_7b" 或 "qwen3_0_6b"
     * @return 加载结果，失败时返回具体错误原因
     */
    suspend fun loadModel(modelId: String, useOpencl: Boolean = false): Result<Unit> = withContext(modelDispatcher) {
        engineMutex.withLock {
            // 双重检查：已加载且是同一模型，直接返回
            if (client.isLoaded && currentModelId == modelId) {
                ensureRegistered()
                Logger.d(tag, "Model $modelId already loaded")
                return@withLock Result.success(Unit)
            }

            // 如果已加载的是其他模型，先卸载
            if (client.isLoaded) {
                Logger.d(tag, "Unloading previous model: $currentModelId")
                client.unload()
                currentModelId = null
                isRegistered.set(false)
            }

            try {
                Logger.i(tag, "Loading LLM model: $modelId, useOpencl=$useOpencl")
                val success = client.load(modelId, useOpencl)
                if (success) {
                    // 原子性设置：nativeHandle 和 currentModelId 在同一把锁内完成
                    currentModelId = modelId
                    ensureRegistered()
                    Logger.i(tag, "Model $modelId loaded successfully")
                    Result.success(Unit)
                } else {
                    Logger.e(tag, "Failed to load model $modelId (native load returned false)")
                    Result.failure(
                        LlmModelNotFoundException(
                            "模型加载失败，请确认模型已下载。设置 → AI 模型管理 → 下载 $modelId"
                        )
                    )
                }
            } catch (exception: CancellationException) {
                // 取消异常必须重新抛出，不吞没
                Logger.w(tag, "Model loading cancelled: $modelId")
                throw exception
            } catch (exception: IllegalStateException) {
                // LlmModelManager 抛出的模型未找到异常
                Logger.e(tag, "Model not found: $modelId", exception)
                Result.failure(
                    LlmModelNotFoundException(
                        "模型未下载，请前往设置 → AI 模型管理下载模型",
                        exception
                    )
                )
            } catch (exception: Exception) {
                Logger.e(tag, "Exception loading model $modelId", exception)
                Result.failure(exception)
            }
        }
    }

    /**
     * 检查指定模型是否已下载可用
     */
    fun isModelAvailable(modelId: String, context: Context): Boolean {
        return LlmModelManager(context).isModelCached(modelId)
    }

    /**
     * 最近一次本地生成的性能指标（仅同步/流式生成完成后有效）。
     */
    @Volatile
    var lastGenerationMetrics: LlmGenerationMetrics? = null
        private set

    /**
     * 使用 LangChain4j 风格 API 进行同步对话。
     *
     * 内部根据消息组成选择最优的底层调用：
     * - 包含 SystemMessage + UserMessage → MNN generateWithSystem（可获取完整性能指标）
     * - 仅 UserMessage → 直接 generate
     * - 多轮历史 → 拼接为纯文本 prompt 后 generate
     */
    override fun chat(request: ChatRequest): ChatResponse {
        return runBlocking(modelDispatcher) {
            engineMutex.withLock {
                if (!client.isLoaded) {
                    throw IllegalStateException("LLM model not loaded")
                }

                val messages = request.messages
                val systemMessage = messages.filterIsInstance<SystemMessage>().lastOrNull()?.text
                val userMessage = messages.filterIsInstance<UserMessage>().lastOrNull()?.text
                    ?: messages.lastOrNull()?.let { extractText(it) }
                    ?: throw IllegalArgumentException("ChatRequest must contain at least one message")

                try {
                    val response = if (systemMessage != null) {
                        val maxTokens = if (request.toolSpecifications.isNotEmpty()) 256 else 128
                        val result = client.generateWithSystem(
                            systemPrompt = systemMessage,
                            userPrompt = userMessage,
                            maxNewTokens = maxTokens
                        )
                        if (result.error != null) {
                            throw RuntimeException(result.error)
                        }
                        lastGenerationMetrics = LlmGenerationMetrics(
                            promptLen = result.promptLen,
                            decodeLen = result.decodeLen,
                            prefillTime = result.prefillTime,
                            decodeTime = result.decodeTime,
                            prefillSpeed = result.prefillSpeed,
                            decodeSpeed = result.decodeSpeed
                        )
                        result.response
                    } else {
                        client.generate(
                            prompt = buildPromptFromMessages(messages),
                            maxNewTokens = 128
                        )
                    }

                    if (response.isBlank()) {
                        throw RuntimeException("Empty LLM response")
                    }

                    ChatResponse(
                        aiMessage = AiMessage(response),
                        metadata = lastGenerationMetrics?.let {
                            ChatResponseMetadata(
                                promptTokens = it.promptLen,
                                completionTokens = it.decodeLen,
                                prefillTimeMs = it.prefillTime,
                                decodeTimeMs = it.decodeTime,
                                prefillSpeed = it.prefillSpeed,
                                decodeSpeed = it.decodeSpeed
                            )
                        }
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Logger.e(tag, "Chat failed", exception)
                    throw exception
                }
            }
        }
    }

    /**
     * 使用 LangChain4j 风格 API 进行流式对话。
     */
    override fun chat(request: ChatRequest, handler: StreamingChatResponseHandler) {
        CoroutineScope(modelDispatcher).launch {
            engineMutex.withLock {
                if (!client.isLoaded) {
                    handler.onError(IllegalStateException("LLM model not loaded"))
                    return@launch
                }

                val prompt = buildPromptFromMessages(request.messages)
                val accumulatedText = StringBuilder()

                val listener = object : StreamGenerateListener {
                    override fun onToken(token: String?, isEop: Boolean): Boolean {
                        if (token != null) {
                            accumulatedText.append(token)
                            handler.onPartialResponse(token)
                        }
                        return false
                    }
                }

                try {
                    val streamResult = client.generateStream(prompt, 128, listener)
                    if (streamResult.isSuccess) {
                        handler.onCompleteResponse(
                            ChatResponse(
                                aiMessage = AiMessage(streamResult.response),
                                metadata = ChatResponseMetadata(
                                    promptTokens = streamResult.promptLen,
                                    completionTokens = streamResult.decodeLen,
                                    prefillTimeMs = streamResult.prefillTime,
                                    decodeTimeMs = streamResult.decodeTime,
                                    prefillSpeed = streamResult.prefillSpeed,
                                    decodeSpeed = streamResult.decodeSpeed
                                )
                            )
                        )
                    } else {
                        handler.onError(RuntimeException(streamResult.error ?: "Unknown error"))
                    }
                } catch (exception: Exception) {
                    Logger.e(tag, "Streaming chat failed", exception)
                    handler.onError(exception)
                }
            }
        }
    }

    private fun extractText(message: ChatMessage): String = when (message) {
        is UserMessage -> message.text
        is SystemMessage -> message.text
        is AiMessage -> message.text
        is ToolExecutionResultMessage -> message.text
    }

    /**
     * 卸载当前模型，释放内存
     *
     * 通过 ResourceManager 协调释放，避免与 ASR 的 MNN 全局状态冲突。
     */
    fun unload() {
        resourceManager.releaseLlm(
            owner = "LocalLlmEngine",
            onSafeUnload = { enqueueUnload() },
            onSoftRelease = { enqueueTrimMemory() }
        )
        isRegistered.set(false)
    }

    /**
     * 重置模型状态，清理历史记录和 KV Cache（不卸载模型）
     *
     * 用于相机场景降低内存占用，避免与 Sherpa-MNN ASR 的 MNN 全局状态冲突。
     * 比 unload() 更安全，不会破坏 MNN 全局内存分配器。
     */
    fun trimMemory() {
        enqueueTrimMemory()
    }

    /**
     * 显式释放 LLM native 权重/interpreter/tensor。
     */
    fun releaseWeightsInterpreterTensors() {
        enqueueUnload()
    }

    /**
     * [P0-2] 反注册 ResourceManager 监听器，释放所有资源。
     *
     * 应在不再使用此引擎时调用，防止监听器累积和 native 泄漏。
     */
    fun release() {
        resourceManager.unregisterSoftTrimListener(::onSoftTrim)
        resourceManager.unregisterSafeUnloadListener(::onSafeUnload)
        resourceManager.unregisterReleaseCallbacks("llm")
        unload()
    }

    /**
     * 将 trimMemory 任务投递到专用线程执行
     */
    private fun enqueueTrimMemory() {
        modelExecutor.execute {
            if (!engineMutex.tryLock()) {
                Logger.w(tag, "Skip trimMemory: engine is busy")
                return@execute
            }
            try {
                if (client.isLoaded) {
                    client.releaseNative(NativeReleaseTarget.KV_CACHE)
                    Logger.i(tag, "LLM memory trimmed (history cleared, model still loaded)")
                }
            } finally {
                engineMutex.unlock()
            }
        }
    }

    /**
     * 将 unload 任务投递到专用线程执行
     *
     * 避免递归 runBlocking 导致的死锁。
     * 使用 MnnGlobalReleaseLock 串行化 MNN native 释放。
     */
    private fun enqueueUnload() {
        modelExecutor.execute {
            if (!engineMutex.tryLock()) {
                Logger.w(tag, "Skip unload: engine is busy, will retry on next operation")
                return@execute
            }
            try {
                if (client.isLoaded) {
                    // 使用 MNN 全局锁串行化 native 释放
                    MnnGlobalReleaseLock.withLock {
                        client.releaseNative(NativeReleaseTarget.WEIGHTS_INTERPRETER_TENSORS)
                    }
                    currentModelId = null
                    Logger.i(tag, "LLM fully unloaded")
                }
            } finally {
                engineMutex.unlock()
            }
        }
    }

    private fun ensureRegistered() {
        if (isRegistered.compareAndSet(false, true)) {
            resourceManager.acquireLlm("LocalLlmEngine")
        }
    }

    private fun onSoftTrim() {
        if (client.isLoaded) {
            enqueueTrimMemory()
        }
    }

    /**
     * 安全卸载回调（由 ResourceManager 触发）
     *
     * 注意：此回调已在 MnnGlobalReleaseLock 保护下执行，直接同步卸载即可。
     * 不要投递到 modelExecutor，否则真正的释放会脱离锁保护。
     */
    private fun onSafeUnload() {
        if (client.isLoaded) {
            try {
                // 同步卸载，确保在 MnnGlobalReleaseLock 保护下完成
                client.releaseNative(NativeReleaseTarget.WEIGHTS_INTERPRETER_TENSORS)
                currentModelId = null
                Logger.i(tag, "LLM fully unloaded (sync)")
            } catch (e: Exception) {
                Logger.e(tag, "LLM sync unload failed", e)
            }
        }
        isRegistered.set(false)
    }

    /**
     * 将 ChatMessages 拼接为单个 prompt 字符串
     *
     * **注意**：MNN-LLM 在 config.json 中 use_template=true 时会自动应用 chat template，
     * 外部不需要手动添加 <|im_start|> 等标记。使用纯文本格式即可。
     */
    private fun buildPromptFromMessages(
        messages: List<ChatMessage>
    ): String {
        return buildString {
            messages.forEach { message ->
                when (message) {
                    is SystemMessage -> {
                        appendLine("system:")
                        appendLine(message.text)
                        appendLine()
                    }
                    is UserMessage -> {
                        appendLine("user:")
                        appendLine(message.text)
                        appendLine()
                    }
                    is AiMessage -> {
                        appendLine("assistant:")
                        appendLine(message.text)
                        appendLine()
                    }
                    is ToolExecutionResultMessage -> {
                        appendLine("tool:")
                        appendLine(message.text)
                        appendLine()
                    }
                }
            }
            append("assistant:")
        }
    }
}
