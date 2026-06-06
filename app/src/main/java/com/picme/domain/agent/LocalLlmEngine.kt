package com.picme.domain.agent

import android.content.Context
import com.picme.beauty.api.llm.MnnLlmClient
import com.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.picme.beauty.internal.model.ModelManager
import com.picme.domain.agent.model.ChatMessage
import com.picme.domain.agent.model.ChatRole

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
 * @param context Application Context
 */
class LocalLlmEngine(private val context: Context) {

    private val tag = "LocalLlmEngine"
    private val client = MnnLlmClient(context)
    private val engineMutex = Mutex()

    /**
     * 当前加载的模型 ID
     */
    private var currentModelId: String? = null

    /**
     * 模型是否已加载
     */
    val isLoaded: Boolean
        get() = client.isLoaded

    /**
     * 加载指定模型
     *
     * @param modelId 模型注册表中的 key，如 "qwen3_1_7b" 或 "qwen3_0_6b"
     * @return 加载结果，失败时返回具体错误原因
     */
    suspend fun loadModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            if (client.isLoaded && currentModelId == modelId) {
                Logger.d(tag, "Model $modelId already loaded")
                return@withLock Result.success(Unit)
            }

            if (client.isLoaded) {
                Logger.d(tag, "Unloading previous model: $currentModelId")
                client.unload()
            }

            try {
                Logger.i(tag, "Loading LLM model: $modelId")
                val success = client.load(modelId)
                if (success) {
                    currentModelId = modelId
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
            } catch (exception: IllegalStateException) {
                // ModelManager 抛出的模型未找到异常
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
        return ModelManager.isLlmModelCached(modelId, context)
    }

    /**
     * 使用纯文本 prompt 生成回复
     *
     * @param prompt 完整 prompt 字符串（已包含 system/user/assistant 标记）
     * @param maxTokens 最大生成 token 数
     * @return 生成的文本
     */
    suspend fun generate(prompt: String, maxTokens: Int = 128): Result<String> = withContext(Dispatchers.IO) {
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
    ): Result<String> = withContext(Dispatchers.IO) {
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
    ): Result<String> = withContext(Dispatchers.IO) {
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
            } catch (exception: Exception) {
                Logger.e(tag, "Generation with history failed", exception)
                Result.failure(exception)
            }
        }
    }

    /**
     * 卸载当前模型，释放内存
     */
    fun unload() {
        if (!engineMutex.tryLock()) {
            Logger.w(tag, "Skip unload: engine is busy")
            return
        }

        try {
            if (client.isLoaded) {
                client.unload()
                currentModelId = null
                Logger.d(tag, "LLM unloaded")
            }
        } finally {
            engineMutex.unlock()
        }
    }

    /**
     * 重置模型状态，清理历史记录和 KV Cache（不卸载模型）
     *
     * 用于相机场景降低内存占用，避免与 Sherpa-MNN ASR 的 MNN 全局状态冲突。
     * 比 unload() 更安全，不会破坏 MNN 全局内存分配器。
     */
    fun trimMemory() {
        if (!engineMutex.tryLock()) {
            Logger.w(tag, "Skip trimMemory: engine is busy")
            return
        }

        try {
            if (client.isLoaded) {
                client.reset()
                Logger.i(tag, "LLM memory trimmed (history cleared, model still loaded)")
            }
        } finally {
            engineMutex.unlock()
        }
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
