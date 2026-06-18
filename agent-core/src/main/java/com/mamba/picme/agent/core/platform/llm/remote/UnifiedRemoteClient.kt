package com.mamba.picme.agent.core.platform.llm.remote

import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.android.RemoteProtocol
import com.mamba.picme.agent.core.api.LlmChatLanguageModel
import com.mamba.picme.agent.core.api.LlmChatRequest
import com.mamba.picme.agent.core.api.LlmChatResponse
import com.mamba.picme.agent.core.api.StreamingLlmChatLanguageModel
import com.mamba.picme.agent.core.api.StreamingChatResponseHandler
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.llm.remote.claude.ClaudeCodingApiClient
import com.mamba.picme.agent.core.platform.llm.remote.claude.ClaudeCodingMessage
import com.mamba.picme.agent.core.platform.llm.remote.claude.ClaudeCodingRequest
import com.mamba.picme.agent.core.platform.llm.remote.claude.ClaudeCodingResponse
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import kotlinx.coroutines.runBlocking
import retrofit2.Response

/**
 * 统一远程 API 客户端
 *
 * 根据 [RemoteModelConfig.protocol] 自动选择推理引擎：
 * - CLAUDE → Claude API 格式（x-api-key + /messages），沿用 Retrofit 实现
 * - OPENAI → LangChain4j OpenAiChatModel（标准 OpenAI 协议）
 *
 * OPENAI 路径已迁移至 langchain4j 标准实现，支持 tool_calls、流式等 OpenAI 协议特性。
 */
class UnifiedRemoteClient(
    private val config: RemoteModelConfig
) : LlmChatLanguageModel, StreamingLlmChatLanguageModel {

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
     * Claude API 客户端（Claude 协议）
     */
    private val claudeClient: ClaudeCodingApiClient? by lazy {
        if (config.protocol == RemoteProtocol.CLAUDE) {
            ClaudeCodingApiClient(
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                enableLogging = true
            )
        } else null
    }

    // ── 非流式 ──────────────────────────────────────────────────

    override fun chat(request: LlmChatRequest): LlmChatResponse {
        return when {
            langChain4jClient != null -> {
                Logger.d(tag, "Using LangChain4jOpenAiClient for model=${config.modelId}")
                langChain4jClient!!.chat(request)
            }
            claudeClient != null -> {
                Logger.d(tag, "Using ClaudeCodingApiClient for model=${config.modelId}")
                chatClaude(request)
            }
            else -> throw IllegalStateException("No client available for model ${config.modelId}")
        }
    }

    // ── 流式 ────────────────────────────────────────────────────

    override fun chat(request: LlmChatRequest, handler: StreamingChatResponseHandler) {
        when {
            langChain4jClient != null -> {
                Logger.d(tag, "Streaming with LangChain4jOpenAiClient for model=${config.modelId}")
                langChain4jClient!!.chat(request, handler)
            }
            claudeClient != null -> {
                // Claude 不支持流式，回退为非流式一次返回
                Logger.d(tag, "Claude fallback to non-streaming for model=${config.modelId}")
                try {
                    val response = chatClaude(request)
                    handler.onCompleteResponse(response)
                } catch (e: Exception) {
                    handler.onError(e)
                }
            }
            else -> handler.onError(IllegalStateException("No client available for model ${config.modelId}"))
        }
    }

    /**
     * Claude API 聊天
     */
    private fun chatClaude(request: LlmChatRequest): LlmChatResponse {
        return runBlocking {
            val systemPrompt = request.messages.filterIsInstance<SystemMessage>().lastOrNull()?.text()
            val userInput = request.messages.filterIsInstance<UserMessage>().lastOrNull()?.singleText()
                ?: throw IllegalArgumentException("LlmChatRequest must contain a UserMessage")

            try {
                val client = claudeClient ?: throw IllegalStateException("Claude client not initialized")
                val claudeRequest = ClaudeCodingRequest(
                    model = config.modelId,
                    messages = listOf(ClaudeCodingMessage(role = "user", content = userInput)),
                    system = systemPrompt,
                    maxTokens = request.maxTokens ?: 2048,
                    temperature = request.temperature ?: 0.3,
                    stream = false
                )

                val response: Response<ClaudeCodingResponse> = client.service.messages(claudeRequest)
                val content = parseResponse(response) { body ->
                    body.content.firstOrNull()?.text?.trim()
                }.getOrThrow()

                LlmChatResponse(aiMessage = AiMessage(content))
            } catch (e: Exception) {
                Logger.e(tag, "Claude chat failed for model=${config.modelId}", e)
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