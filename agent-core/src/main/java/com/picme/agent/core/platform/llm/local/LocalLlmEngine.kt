package com.picme.agent.core.platform.llm.local

import android.content.Context
import com.picme.agent.core.platform.logging.Logger
import com.picme.agent.core.platform.llm.local.LlmModelManager
import com.picme.agent.core.platform.llm.local.MnnLlmClient
import com.picme.agent.core.platform.llm.local.MnnLlmClient.NativeReleaseTarget
import com.picme.agent.core.platform.mnn.MnnGlobalReleaseLock
import com.picme.agent.core.platform.mnn.MnnResourceManager
import com.picme.agent.core.api.context.ChatMessage
import com.picme.agent.core.api.context.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
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
class LocalLlmEngine(private val context: Context) {

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
     * 使用纯文本 prompt 生成回复
     *
     * 在专用单线程 [modelDispatcher] 上执行，确保与 load/unload 互斥。
     *
     * @param prompt 完整 prompt 字符串（已包含 system/user/assistant 标记）
     * @param maxTokens 最大生成 token 数
     * @return 生成的文本
     */
    suspend fun generate(prompt: String, maxTokens: Int = 128): Result<String> = withContext(modelDispatcher) {
        engineMutex.withLock {
            if (!client.isLoaded) {
                Logger.w(tag, "LLM not loaded, cannot generate")
                return@withLock Result.failure(IllegalStateException("LLM model not loaded"))
            }

            try {
                Logger.d(tag, "Generating response with maxTokens=$maxTokens, promptLength=${prompt.length}")
                val response = client.generate(
                    prompt = prompt,
                    maxNewTokens = maxTokens
                )
                if (response.isNotBlank()) {
                    Result.success(response)
                } else {
                    Result.failure(RuntimeException("Empty LLM response"))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.e(tag, "Generation failed", exception)
                Result.failure(exception)
            }
        }
    }

    /**
     * 使用 system prompt + user prompt 生成回复（ChatMessages 格式）
     *
     * 注意：某些 MNN-LLM 模型版本可能不支持 ChatMessages API，
     * 如遇空响应请改用单 prompt 的 [generate] 方法。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @param maxTokens 最大生成 token 数
     * @return 生成的文本
     */
    suspend fun generateWithSystem(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 128
    ): Result<String> = withContext(modelDispatcher) {
        engineMutex.withLock {
            if (!client.isLoaded) {
                Logger.w(tag, "LLM not loaded, cannot generate")
                return@withLock Result.failure(IllegalStateException("LLM model not loaded"))
            }

            try {
                Logger.d(tag, "Generating response with maxTokens=$maxTokens")
                val response = client.generateWithSystem(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxNewTokens = maxTokens
                )
                if (response.isNotBlank()) {
                    Result.success(response)
                } else {
                    Result.failure(RuntimeException("Empty LLM response"))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.e(tag, "Generation with system prompt failed", exception)
                Result.failure(exception)
            }
        }
    }

    /**
     * 使用 ChatMessages 格式生成回复（支持多轮对话历史）
     *
     * @param messages 消息列表（system + history + user）
     * @param maxTokens 最大生成 token 数
     * @return 生成的文本
     */
    suspend fun generateWithHistory(
        messages: List<ChatMessage>,
        maxTokens: Int = 128
    ): Result<String> = withContext(modelDispatcher) {
        engineMutex.withLock {
            if (!client.isLoaded) {
                Logger.w(tag, "LLM not loaded, cannot generate")
                return@withLock Result.failure(IllegalStateException("LLM model not loaded"))
            }

            try {
                val prompt = buildPromptFromMessages(messages)
                Logger.d(tag, "Generating with history, messages=${messages.size}")
                val response = client.generate(
                    prompt = prompt,
                    maxNewTokens = maxTokens
                )
                if (response.isNotBlank()) {
                    Result.success(response)
                } else {
                    Result.failure(RuntimeException("Empty LLM response"))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.e(tag, "Generation with history failed", exception)
                Result.failure(exception)
            }
        }
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
     * MNN-LLM 当前版本支持 ChatMessages 格式，但为兼容性
     * 先拼接为文本格式。后续可升级使用原生 ChatMessages API。
     */
    private fun buildPromptFromMessages(
        messages: List<ChatMessage>
    ): String {
        return buildString {
            messages.forEach { message ->
                when (message.role) {
                    ChatRole.SYSTEM -> {
                        appendLine("<|im_start|>system")
                        appendLine(message.content)
                        appendLine("<|im_end|>")
                    }
                    ChatRole.USER -> {
                        appendLine("<|im_start|>user")
                        appendLine(message.content)
                        appendLine("<|im_end|>")
                    }
                    ChatRole.ASSISTANT -> {
                        appendLine("<|im_start|>assistant")
                        appendLine(message.content)
                        appendLine("<|im_end|>")
                    }
                }
            }
            appendLine("<|im_start|>assistant")
        }
    }
}
