package com.mamba.picme.agent.core.platform.llm.remote

import com.mamba.android.MambaAgentFactory
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.request.DefaultChatRequestParameters
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.LlmChatLanguageModel
import com.mamba.picme.agent.core.api.LlmChatRequest
import com.mamba.picme.agent.core.api.LlmChatResponse
import com.mamba.picme.agent.core.api.StreamingLlmChatLanguageModel
import com.mamba.picme.agent.core.api.StreamingChatResponseHandler
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.data.message.AiMessage
import java.time.Duration

/**
 * 统一远程 API 客户端
 *
 * 使用 MambaAgentFactory 创建标准 OpenAI 协议 ChatModel。
 * 所有远程模型统一走 OpenAI 兼容协议。
 */
class UnifiedRemoteClient(
    private val config: RemoteModelConfig
) : LlmChatLanguageModel, StreamingLlmChatLanguageModel {

    private val tag = "UnifiedRemote"

    /**
     * OPENAI 协议客户端（通过 MambaAgentFactory 创建）
     */
    private val openAiClient: com.mamba.model.chat.ChatModel? by lazy {
        try {
            MambaAgentFactory.builder()
                .apiKey(config.apiKey)
                .baseUrl(config.baseUrl)
                .model(config.modelId)
                .temperature(0.7)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(2)
                .build()
        } catch (e: Exception) {
            Logger.e(tag, "Failed to create OpenAI client", e)
            null
        }
    }

    // ── 非流式 ──────────────────────────────────────────────────

    override fun chat(request: LlmChatRequest): LlmChatResponse {
        val client = openAiClient ?: throw IllegalStateException("OpenAI client not initialized")

        val chatRequest = ChatRequest.builder()
            .messages(request.messages)
            .parameters(
                DefaultChatRequestParameters.builder()
                    .temperature(request.temperature ?: 0.7)
                    .maxOutputTokens(request.maxTokens ?: 2048)
                    .build()
            )
            .build()

        val response = client.chat(chatRequest)
        val text = response.aiMessage()?.text() ?: ""
        return LlmChatResponse(aiMessage = AiMessage(text))
    }

    // ── 流式 ────────────────────────────────────────────────────

    override fun chat(request: LlmChatRequest, handler: StreamingChatResponseHandler) {
        // mamba-agent 的 ChatModel 接口暂不支持流式，回退为非流式
        Logger.w(tag, "OpenAI streaming not yet supported by mamba-agent ChatModel, falling back to non-streaming")
        try {
            val response = chat(request)
            handler.onCompleteResponse(response)
        } catch (e: Exception) {
            handler.onError(e)
        }
    }

    companion object {
        /**
         * 检测是否为限频/配额错误
         */
        fun isRateLimitError(error: Throwable): Boolean {
            val msg = error.message ?: return false
            return msg.contains("429") || msg.contains("rate limit") || msg.contains("quota")
        }
    }
}
