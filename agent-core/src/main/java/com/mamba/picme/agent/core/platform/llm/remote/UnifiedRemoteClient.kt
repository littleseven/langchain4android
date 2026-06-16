package com.mamba.picme.agent.core.platform.llm.remote

import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.android.RemoteProtocol
import com.mamba.picme.agent.core.api.AiMessage
import com.mamba.picme.agent.core.api.ChatLanguageModel
import com.mamba.picme.agent.core.api.ChatRequest
import com.mamba.picme.agent.core.api.ChatResponse
import com.mamba.picme.agent.core.api.SystemMessage
import com.mamba.picme.agent.core.api.UserMessage
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingApiClient
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingMessage
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingRequest
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingResponse
import kotlinx.coroutines.runBlocking
import retrofit2.Response

/**
 * 统一远程 API 客户端
 *
 * 根据 [RemoteModelConfig.protocol] 自动选择推理引擎：
 * - CLAUDE → Kimi Coding 格式（x-api-key + /messages），沿用 Retrofit 实现
 * - OPENAI → LangChain4j OpenAiChatModel（标准 OpenAI 协议）
 *
 * OPENAI 路径已迁移至 langchain4j 标准实现，支持 tool_calls、流式等 OpenAI 协议特性。
 */
class UnifiedRemoteClient(
    private val config: RemoteModelConfig
) : ChatLanguageModel {

    private val tag = "UnifiedRemote"

    /**
     * LangChain4j 实现的 OpenAI 客户端
     */
    private val langChain4jClient: LangChain4jOpenAiClient? by lazy {
        if (config.protocol == RemoteProtocol.OPENAI) {
            LangChain4jOpenAiClient(config)
        } else null
    }

    /**
     * Kimi Coding API 客户端（Claude 协议）
     */
    private val kimiClient: KimiCodingApiClient? by lazy {
        if (config.protocol == RemoteProtocol.CLAUDE) {
            KimiCodingApiClient(
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                enableLogging = true
            )
        } else null
    }

    /**
     * 发送聊天请求，返回 LangChain4j 风格的 [ChatResponse]。
     *
     * - OpenAI 协议：委托给 [LangChain4jOpenAiClient]
     * - Claude/Kimi 协议：沿用 Retrofit 实现
     */
    override fun chat(request: ChatRequest): ChatResponse {
        return when {
            langChain4jClient != null -> {
                // OpenAI 路径：使用 langchain4j 标准协议
                Logger.d(tag, "Using LangChain4jOpenAiClient for model=${config.modelId}")
                langChain4jClient!!.chat(request)
            }
            kimiClient != null -> {
                // Kimi/Claude 路径：沿用现有实现
                Logger.d(tag, "Using KimiCodingApiClient for model=${config.modelId}")
                chatKimi(request)
            }
            else -> throw IllegalStateException("No client available for model ${config.modelId}")
        }
    }

    /**
     * Kimi Coding API 聊天
     */
    private fun chatKimi(request: ChatRequest): ChatResponse {
        return runBlocking {
            val systemPrompt = request.messages.filterIsInstance<SystemMessage>().lastOrNull()?.text
            val userInput = request.messages.filterIsInstance<UserMessage>().lastOrNull()?.text
                ?: throw IllegalArgumentException("ChatRequest must contain a UserMessage")

            try {
                val client = kimiClient ?: throw IllegalStateException("Kimi client not initialized")
                val kimiRequest = KimiCodingRequest(
                    model = config.modelId,
                    messages = listOf(KimiCodingMessage(role = "user", content = userInput)),
                    system = systemPrompt,
                    maxTokens = request.maxTokens ?: 2048,
                    temperature = request.temperature ?: 0.3,
                    stream = false
                )

                val response: Response<KimiCodingResponse> = client.service.messages(kimiRequest)
                val content = parseResponse(response) { body ->
                    body.content.firstOrNull()?.text?.trim()
                }.getOrThrow()

                ChatResponse(aiMessage = AiMessage(text = content))
            } catch (e: Exception) {
                Logger.e(tag, "Kimi chat failed for model=${config.modelId}", e)
                throw e
            }
        }
    }

    private fun <T, R> parseResponse(
        response: Response<T>,
        extractContent: (T) -> R?
    ): Result<R> {
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            Logger.e(tag, "HTTP ${response.code()}, body=$errorBody")
            return Result.failure(RuntimeException("API error: ${response.code()}"))
        }

        val body = response.body()
            ?: return Result.failure(RuntimeException("Empty response body"))

        val content = extractContent(body)
            ?: return Result.failure(RuntimeException("No content in response"))

        return Result.success(content)
    }

    companion object {
        /**
         * 根据模型 ID 推断默认协议类型（向后兼容）
         */
        fun protocolFor(modelId: String): RemoteProtocol {
            return when (modelId) {
                "kimi-for-coding" -> RemoteProtocol.CLAUDE
                else -> RemoteProtocol.OPENAI
            }
        }

        /**
         * 检测是否为限频/配额错误
         */
        fun isRateLimitError(error: Throwable): Boolean {
            return LangChain4jOpenAiClient.isRateLimitError(error)
        }
    }
}
