package com.mamba.picme.agent.core.platform.llm.remote

import com.mamba.picme.agent.core.langchain4j.AiMessage
import com.mamba.picme.agent.core.langchain4j.ChatLanguageModel
import com.mamba.picme.agent.core.langchain4j.ChatRequest
import com.mamba.picme.agent.core.langchain4j.ChatResponse
import com.mamba.picme.agent.core.langchain4j.SystemMessage
import com.mamba.picme.agent.core.langchain4j.ToolExecutionResultMessage
import com.mamba.picme.agent.core.langchain4j.ToolSpecification
import com.mamba.picme.agent.core.langchain4j.UserMessage
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingApiClient
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingMessage
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingRequest
import com.mamba.picme.agent.core.platform.llm.remote.kimi.KimiCodingResponse
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiApiClient
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiChatRequest
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiChatResponse
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiFunction
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiFunctionParameters
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiJsonSchemaProperty
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiMessage
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiTool
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiToolCall
import com.mamba.picme.agent.core.platform.llm.remote.openai.OpenAiToolCallFunction
import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.android.RemoteProtocol
import kotlinx.coroutines.runBlocking
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
) : ChatLanguageModel {

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
     * 发送聊天请求，返回 LangChain4j 风格的 [ChatResponse]。
     */
    override fun chat(request: ChatRequest): ChatResponse {
        return runBlocking {
            val systemPrompt = request.messages.filterIsInstance<SystemMessage>().lastOrNull()?.text
            val userInput = request.messages.filterIsInstance<UserMessage>().lastOrNull()?.text
                ?: throw IllegalArgumentException("ChatRequest must contain a UserMessage")
            val maxTokens = 2048
            val temperature = 0.3

            try {
                when {
                    kimiClient != null -> {
                        val content = chatKimi(systemPrompt, userInput, maxTokens, temperature)
                        ChatResponse(aiMessage = AiMessage(content))
                    }
                    openAiClient != null -> chatOpenAi(request, maxTokens, temperature)
                    else -> throw IllegalStateException("No client available for model ${config.modelId}")
                }
            } catch (e: Exception) {
                Logger.e(tag, "Chat failed for model=${config.modelId}", e)
                throw e
            }
        }
    }

    private suspend fun chatKimi(
        systemPrompt: String?,
        userInput: String,
        maxTokens: Int,
        temperature: Double
    ): String {
        val client = kimiClient ?: throw IllegalStateException("Kimi client not initialized")
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
        }.getOrThrow()
    }

    private suspend fun chatOpenAi(
        request: ChatRequest,
        maxTokens: Int,
        temperature: Double
    ): ChatResponse {
        val client = openAiClient ?: throw IllegalStateException("OpenAI client not initialized")

        val messages = request.messages.mapNotNull { message ->
            when (message) {
                is SystemMessage -> OpenAiMessage(role = "system", content = message.text)
                is UserMessage -> OpenAiMessage(role = "user", content = message.text)
                is AiMessage -> OpenAiMessage(
                    role = "assistant",
                    content = message.text.takeIf { it.isNotBlank() },
                    toolCalls = message.toolExecutionRequests.takeIf { it.isNotEmpty() }?.map { req ->
                        OpenAiToolCall(
                            id = req.id,
                            function = OpenAiToolCallFunction(
                                name = req.name,
                                arguments = req.arguments
                            )
                        )
                    }
                )
                is ToolExecutionResultMessage -> OpenAiMessage(
                    role = "tool",
                    content = message.text,
                    toolCallId = message.toolExecutionRequest.id
                )
                else -> null
            }
        }

        val openAiRequest = OpenAiChatRequest(
            model = config.modelId,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            stream = false,
            tools = request.toolSpecifications.toOpenAiTools().takeIf { it.isNotEmpty() },
            toolChoice = if (request.toolSpecifications.isNotEmpty()) "auto" else null
        )

        val response: Response<OpenAiChatResponse> = client.service.chatCompletions(openAiRequest)
        val message = parseResponse(response) { body ->
            body.choices.firstOrNull()?.message
        }.getOrThrow()

        val content = message.content?.trim() ?: ""
        val toolExecutionRequests = message.toolCalls?.map { call ->
            com.mamba.picme.agent.core.langchain4j.ToolExecutionRequest(
                id = call.id,
                name = call.function.name,
                arguments = call.function.arguments
            )
        } ?: emptyList()

        return ChatResponse(
            aiMessage = AiMessage(
                text = content,
                toolExecutionRequests = toolExecutionRequests
            )
        )
    }

    private fun List<ToolSpecification>.toOpenAiTools(): List<OpenAiTool> = map { spec ->
        OpenAiTool(
            function = OpenAiFunction(
                name = spec.name,
                description = spec.description,
                parameters = OpenAiFunctionParameters(
                    type = spec.parameters.type,
                    properties = spec.parameters.properties.mapValues { (_, prop) ->
                        OpenAiJsonSchemaProperty(
                            type = prop.type,
                            description = prop.description,
                            enum = prop.enum
                        )
                    },
                    required = spec.parameters.required
                )
            )
        )
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
