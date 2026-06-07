package com.picme.agent.core.remote

import com.picme.agent.core.AgentLogger
import com.picme.agent.core.remote.kimi.KimiCodingApiClient
import com.picme.agent.core.remote.kimi.KimiCodingMessage
import com.picme.agent.core.remote.kimi.KimiCodingRequest
import com.picme.agent.core.remote.kimi.KimiCodingResponse
import com.picme.agent.core.remote.openai.OpenAiApiClient
import com.picme.agent.core.remote.openai.OpenAiChatRequest
import com.picme.agent.core.remote.openai.OpenAiChatResponse
import com.picme.agent.core.remote.openai.OpenAiMessage
import com.picme.agent.core.model.RemoteModelConfig
import com.picme.agent.core.model.RemoteProtocol
import retrofit2.Response

/**
 * 统一远程 API 客户端
 *
 * 根据 [RemoteModelConfig.protocol] 自动选择协议：
 * - CLAUDE → Kimi Coding 格式（x-api-key + /messages）
 * - OPENAI → OpenAI 兼容格式（Bearer + /chat/completions）
 *
 * 对外暴露统一接口，隐藏协议差异。
 */
class UnifiedRemoteClient(
    private val config: RemoteModelConfig
) {

    private val tag = "UnifiedRemote"

    private val kimiClient: KimiCodingApiClient? by lazy {
        if (config.protocol == RemoteProtocol.CLAUDE) {
            KimiCodingApiClient(
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                enableLogging = true
            )
        } else null
    }

    private val openAiClient: OpenAiApiClient? by lazy {
        if (config.protocol == RemoteProtocol.OPENAI) {
            OpenAiApiClient(
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                enableLogging = true,
                gatewayToken = config.gatewayToken
            )
        } else null
    }

    /**
     * 发送聊天请求，返回统一格式的文本内容
     *
     * @param systemPrompt 系统提示词
     * @param userInput 用户输入
     * @param maxTokens 最大 token 数
     * @param temperature 温度
     * @return 生成的文本内容，失败时返回 null
     */
    suspend fun chat(
        systemPrompt: String?,
        userInput: String,
        maxTokens: Int = 1024,
        temperature: Double = 0.3
    ): Result<String> {
        return try {
            when {
                kimiClient != null -> chatKimi(systemPrompt, userInput, maxTokens, temperature)
                openAiClient != null -> chatOpenAi(systemPrompt, userInput, maxTokens, temperature)
                else -> Result.failure(IllegalStateException("No client available for model ${config.modelId}"))
            }
        } catch (e: Exception) {
            AgentLogger.e(tag, "Chat failed for model=${config.modelId}", e)
            Result.failure(e)
        }
    }

    private suspend fun chatKimi(
        systemPrompt: String?,
        userInput: String,
        maxTokens: Int,
        temperature: Double
    ): Result<String> {
        val client = kimiClient ?: return Result.failure(IllegalStateException("Kimi client not initialized"))
        val request = KimiCodingRequest(
            model = config.modelId,
            messages = listOf(KimiCodingMessage(role = "user", content = userInput)),
            system = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            stream = false
        )

        val response: Response<KimiCodingResponse> = client.service.messages(request)
        return parseResponse(response) { body ->
            body.content.firstOrNull()?.text?.trim()
        }
    }

    private suspend fun chatOpenAi(
        systemPrompt: String?,
        userInput: String,
        maxTokens: Int,
        temperature: Double
    ): Result<String> {
        val client = openAiClient ?: return Result.failure(IllegalStateException("OpenAI client not initialized"))

        val messages = mutableListOf<OpenAiMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(OpenAiMessage(role = "system", content = systemPrompt))
        }
        messages.add(OpenAiMessage(role = "user", content = userInput))

        val request = OpenAiChatRequest(
            model = config.modelId,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            stream = false
        )

        val response: Response<OpenAiChatResponse> = client.service.chatCompletions(request)
        return parseResponse(response) { body ->
            body.choices.firstOrNull()?.message?.content?.trim()
        }
    }

    private fun <T> parseResponse(
        response: Response<T>,
        extractContent: (T) -> String?
    ): Result<String> {
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            AgentLogger.e(tag, "HTTP ${response.code()}, body=$errorBody")
            return Result.failure(RuntimeException("API error: ${response.code()}"))
        }

        val body = response.body()
            ?: return Result.failure(RuntimeException("Empty response body"))

        val content = extractContent(body)
            ?: return Result.failure(RuntimeException("No content in response"))

        return Result.success(content)
    }

    /**
     * 检查是否为限频/配额错误（429 或 433）
     *
     * 433 是 Cloudflare AI Gateway 或上游返回的非标准状态码，
     * 通常表示配额耗尽或请求频率超限。
     */
    fun isRateLimitError(error: Throwable): Boolean {
        return error.message?.let { msg ->
            msg.contains("429") || msg.contains("433")
        } == true
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
    }
}
