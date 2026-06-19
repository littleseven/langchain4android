package com.mamba.picme.agent.core.platform.llm.remote

import com.mamba.picme.agent.core.api.android.RemoteModelConfig
import com.mamba.picme.agent.core.api.ChatResponseMetadata
import com.mamba.picme.agent.core.api.LlmChatLanguageModel
import com.mamba.picme.agent.core.api.LlmChatRequest
import com.mamba.picme.agent.core.api.LlmChatResponse
import com.mamba.picme.agent.core.api.StreamingLlmChatLanguageModel
import com.mamba.picme.agent.core.api.StreamingChatResponseHandler
import dev.langchain4j.agent.tool.ToolExecutionRequest
import com.mamba.picme.agent.core.api.ToolSpecification
import com.mamba.picme.agent.core.platform.logging.Logger
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的 OpenAI 兼容 API 客户端
 *
 * 使用标准 OpenAI Chat Completions 协议进行远程推理。
 * 支持 Tool Calling、自定义请求头、Token 用量统计。
 *
 * @param config 远程模型配置
 */
class LangChain4jOpenAiClient(
    private val config: RemoteModelConfig
) : LlmChatLanguageModel, StreamingLlmChatLanguageModel {

    private val tag = "LangChain4jOpenAi"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = run {
        val effectiveBaseUrl = normalizeBaseUrl(config.baseUrl)
        val effectiveApiKey = config.apiKey.ifEmpty { "demo" }

        Logger.i(tag, "Initializing: baseUrl=$effectiveBaseUrl, model=${config.modelId}")

        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $effectiveApiKey")
                    .addHeader("Content-Type", "application/json")
                    .also {
                        // 自定义请求头（如 X-App-Token 用于 Tencent SCF 网关认证）
                        config.gatewayToken.takeIf { token -> token.isNotBlank() }?.let { token ->
                            it.addHeader("X-App-Token", token)
                        }
                    }
                    .build()
                chain.proceed(request)
            }

        builder.build()
    }

    private val baseUrl: String = normalizeBaseUrl(config.baseUrl)

    override fun chat(request: LlmChatRequest): LlmChatResponse {
        val startTime = System.currentTimeMillis()
        try {
            // 1. 构建请求 JSON
            val requestBody = buildOpenAiRequest(request)

            // 2. 执行 HTTP 请求
            val httpRequest = Request.Builder()
                .url("${baseUrl}chat/completions")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .build()

            val httpResponse = httpClient.newCall(httpRequest).execute()
            val responseBody = httpResponse.body?.string()
            val latencyMs = System.currentTimeMillis() - startTime

            if (!httpResponse.isSuccessful) {
                val errorMsg = "HTTP ${httpResponse.code}: ${responseBody ?: httpResponse.message}"
                Logger.e(tag, "Chat FAILED latency=${latencyMs}ms: $errorMsg")
                throw RuntimeException(errorMsg)
            }

            // 3. 解析响应 JSON
            val jsonResponse = JSONObject(responseBody!!)
            val result = parseOpenAiResponse(jsonResponse)
            val metadata = parseTokenUsage(jsonResponse)

            Logger.d(
                tag,
                "Chat OK latency=${latencyMs}ms, tokens=${metadata?.completionTokens ?: "?"}, tools=${result.second.size}"
            )

            return LlmChatResponse(
                aiMessage = AiMessage(
                    result.first ?: "",
                    result.second
                ),
                metadata = metadata
            )
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "Chat FAILED latency=${latencyMs}ms: ${e.message}", e)
            throw e
        }
    }

    // ── 流式聊天（SSE） ───────────────────────────────────────────

    override fun chat(request: LlmChatRequest, handler: StreamingChatResponseHandler) {
        val startTime = System.currentTimeMillis()
        try {
            val requestBody = buildOpenAiRequest(request).apply {
                put("stream", true)
                put("stream_options", JSONObject().apply { put("include_usage", true) })
            }

            val httpRequest = Request.Builder()
                .url("${baseUrl}chat/completions")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(httpRequest).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body ?: run {
                        handler.onError(RuntimeException("Empty response body"))
                        return
                    }
                    val fullContent = StringBuilder()
                    var promptTokens = 0L
                    var completionTokens = 0L

                    try {
                        val source = body.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            if (!line.startsWith("data: ")) continue

                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") continue

                            try {
                                val json = JSONObject(data)
                                // 解析 usage（最后一个 chunk 可能携带）
                                json.optJSONObject("usage")?.let { usage ->
                                    promptTokens = usage.optLong("prompt_tokens", 0)
                                    completionTokens = usage.optLong("completion_tokens", 0)
                                }

                                val choices = json.optJSONArray("choices")
                                if (choices == null || choices.length() == 0) continue

                                val delta = choices.optJSONObject(0)?.optJSONObject("delta")
                                val content = delta?.optString("content")
                                if (content != null && content != "null") {
                                    fullContent.append(content)
                                    handler.onPartialResponse(content)
                                }

                                val finishReason = choices.optJSONObject(0)?.optString("finish_reason")
                                if (finishReason != null && finishReason != "null") {
                                    val latencyMs = System.currentTimeMillis() - startTime
                                    Logger.d(tag, "Stream OK latency=${latencyMs}ms, tokens=${completionTokens}")
                                    handler.onCompleteResponse(
                                        LlmChatResponse(
                                            aiMessage = AiMessage(fullContent.toString()),
                                            metadata = ChatResponseMetadata(
                                                promptTokens = promptTokens,
                                                completionTokens = completionTokens
                                            )
                                        )
                                    )
                                    return
                                }
                            } catch (e: Exception) {
                                Logger.w(tag, "SSE parse error: ${e.message}, line=$data")
                            }
                        }
                        // 流结束但未收到 finish_reason（某些 API 行为）
                        if (fullContent.isNotEmpty()) {
                            val latencyMs = System.currentTimeMillis() - startTime
                            Logger.d(tag, "Stream EOF latency=${latencyMs}ms, tokens=${completionTokens}")
                            handler.onCompleteResponse(
                                LlmChatResponse(
                                    aiMessage = AiMessage(fullContent.toString()),
                                    metadata = ChatResponseMetadata(
                                        promptTokens = promptTokens,
                                        completionTokens = completionTokens
                                    )
                                )
                            )
                        } else {
                            handler.onError(RuntimeException("Stream ended with empty content"))
                        }
                    } catch (e: Exception) {
                        val latencyMs = System.currentTimeMillis() - startTime
                        Logger.e(tag, "Stream FAILED latency=${latencyMs}ms: ${e.message}", e)
                        handler.onError(e)
                    }
                }

                override fun onFailure(call: Call, e: java.io.IOException) {
                    val latencyMs = System.currentTimeMillis() - startTime
                    Logger.e(tag, "Stream HTTP FAILED latency=${latencyMs}ms: ${e.message}", e)
                    handler.onError(e)
                }
            })
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Logger.e(tag, "Stream setup FAILED latency=${latencyMs}ms: ${e.message}", e)
            handler.onError(e)
        }
    }

    // ── 构建请求 ────────────────────────────────────────────────

    private fun buildOpenAiRequest(request: LlmChatRequest): JSONObject {
        val json = JSONObject()
        json.put("model", config.modelId)

        // 消息列表
        val messages = JSONArray()
        for (msg in request.messages) {
            messages.put(messageToJson(msg))
        }
        json.put("messages", messages)

        // 工具规格（Tool Calling）
        if (request.toolSpecifications.isNotEmpty()) {
            val tools = JSONArray()
            for (spec in request.toolSpecifications) {
                tools.put(toolSpecToJson(spec))
            }
            json.put("tools", tools)
        }

        // 可选参数
        request.temperature?.let { json.put("temperature", it) }
        request.maxTokens?.let { json.put("max_tokens", it) }

        return json
    }

    private fun messageToJson(msg: ChatMessage): JSONObject {
        return when (msg) {
            is SystemMessage -> JSONObject().apply {
                put("role", "system")
                put("content", msg.text())
            }
            is UserMessage -> JSONObject().apply {
                put("role", "user")
                put("content", msg.singleText())
            }
            is AiMessage -> JSONObject().apply {
                put("role", "assistant")
                put("content", msg.text().ifBlank { null })
                if (msg.toolExecutionRequests().isNotEmpty()) {
                    val toolCalls = JSONArray()
                    for ((index, req) in msg.toolExecutionRequests().withIndex()) {
                        toolCalls.put(JSONObject().apply {
                            put("index", index)
                            put("id", req.id())
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", req.name())
                                put("arguments", req.arguments())
                            })
                        })
                    }
                    put("tool_calls", toolCalls)
                }
            }
            is ToolExecutionResultMessage -> JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", msg.id())
                put("content", msg.text())
            }
            else -> throw IllegalArgumentException("Unknown message type: ${msg::class.simpleName}")
        }
    }

    private fun toolSpecToJson(spec: ToolSpecification): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", spec.name)
                put("description", spec.description)
                // 串联 JSON Schema parameters
                val params = spec.parameters
                if (params.properties.isNotEmpty() || params.required.isNotEmpty()) {
                    put("parameters", JSONObject().apply {
                        put("type", params.type)
                        if (params.properties.isNotEmpty()) {
                            val props = JSONObject()
                            for ((key, prop) in params.properties) {
                                props.put(key, JSONObject().apply {
                                    put("type", prop.type)
                                    prop.description?.let { put("description", it) }
                                    prop.enum?.let {
                                        put("enum", JSONArray(it))
                                    }
                                })
                            }
                            put("properties", props)
                        }
                        if (params.required.isNotEmpty()) {
                            put("required", JSONArray(params.required))
                        }
                    })
                }
            })
        }
    }

    // ── 解析响应 ────────────────────────────────────────────────

    /**
     * 解析 OpenAI 响应，返回 (content, toolExecutionRequests)
     */
    private fun parseOpenAiResponse(json: JSONObject): Pair<String?, List<ToolExecutionRequest>> {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return null to emptyList()
        }

        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: return null to emptyList()

        val content = message.optString("content")
        val toolCalls = message.optJSONArray("tool_calls")

        if (toolCalls == null || toolCalls.length() == 0) {
            return content to emptyList()
        }

        val requests = mutableListOf<ToolExecutionRequest>()
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.getJSONObject(i)
            val func = tc.optJSONObject("function") ?: continue
            // arguments 可能是 JSON 字符串如 "{"delay_ms":5000}" 或 JSON 对象 {"delay_ms":5000}
            // 使用 opt() 获取原始值，然后统一转为字符串
            val arguments = when (val raw = func.opt("arguments")) {
                is JSONObject -> raw.toString()
                is String -> raw
                else -> "{}"
            }
            Logger.d(tag, "Tool call #$i: name=${func.optString("name", "")}, arguments=$arguments")
            requests.add(
                ToolExecutionRequest.builder()
                    .id(tc.optString("id", ""))
                    .name(func.optString("name", ""))
                    .arguments(arguments)
                    .build()
            )
        }

        return content to requests
    }

    private fun parseTokenUsage(json: JSONObject): ChatResponseMetadata? {
        val usage = json.optJSONObject("usage") ?: return null
        return ChatResponseMetadata(
            promptTokens = usage.optLong("prompt_tokens", 0),
            completionTokens = usage.optLong("completion_tokens", 0)
        )
    }

    // ── 工具方法 ────────────────────────────────────────────────

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.endsWith("/v1")) {
            "$trimmed/"
        } else if (trimmed.contains("/v1/")) {
            trimmed.substringBefore("/v1/") + "/v1/"
        } else {
            "$trimmed/v1/"
        }
    }

    companion object {
        fun isRateLimitError(error: Throwable): Boolean {
            return error.message?.let { msg ->
                msg.contains("429") || msg.contains("433")
            } == true
        }
    }
}
