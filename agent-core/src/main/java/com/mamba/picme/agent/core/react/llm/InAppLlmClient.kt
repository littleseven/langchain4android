package com.mamba.picme.agent.core.react.llm

import com.mamba.agent.agent.tool.ToolExecutionRequest
import com.mamba.agent.agent.tool.ToolSpecification
import com.mamba.agent.data.message.AiMessage
import com.mamba.agent.data.message.ChatMessage
import com.mamba.agent.data.message.SystemMessage
import com.mamba.agent.data.message.ToolExecutionResultMessage
import com.mamba.agent.data.message.UserMessage
import com.mamba.agent.model.chat.request.json.JsonEnumSchema
import com.mamba.picme.agent.core.platform.logging.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 轻量级 OpenAI 兼容 API 客户端。
 * 不使用 langchain4j-open-ai（需要完整 langchain4j 生态），
 * 直接通过 OkHttp 调用 OpenAI 兼容端点。
 */
class InAppLlmClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val modelName: String,
    private val temperature: Double = 0.1,
    private val gatewayToken: String? = null
) {
    data class LlmResponse(
        val text: String?,
        val toolExecutionRequests: List<ToolExecutionRequest>,
        val totalTokens: Int = 0
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
            if (gatewayToken != null) {
                req.header("X-App-Token", gatewayToken)
            }
            chain.proceed(req.build())
        }
        .build()

    /**
     * Blocking LLM 调用。
     * 返回 AiMessage（可能包含 ToolExecutionRequest）。
     */
    fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        Logger.d("InAppLlmClient", "chat called with ${toolSpecs.size} tools")
        toolSpecs.forEach { spec ->
            Logger.d("InAppLlmClient", "Tool: ${spec.name()} - ${spec.description()}")
        }
        
        val requestBody = buildJsonRequest(messages, toolSpecs)
        Logger.d("InAppLlmClient", "Request body: ${requestBody.take(3000)}")
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from LLM API")

        if (!response.isSuccessful) {
            throw RuntimeException("LLM API error ${response.code}: $responseBody")
        }

        Logger.d("InAppLlmClient", "Response body: ${responseBody.take(3000)}")
        return parseResponse(responseBody)
    }

    private fun buildJsonRequest(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): String {
        val json = JSONObject()
        json.put("model", modelName)
        json.put("temperature", temperature)

        val messagesArray = JSONArray()
        for (msg in messages) {
            messagesArray.put(convertMessage(msg))
        }
        json.put("messages", messagesArray)

        // 注意：InAppLlmClient 使用文本 ReAct 模式（Thought/Action），
        // 不通过 OpenAI function calling API（tools/tool_choice）传递工具定义。
        // 这种方式兼容所有模型，包括不支持 function calling 的本地小模型。

        // DeepSeek 适配：禁用 thinking 模式以确保输出格式稳定
        // 参考：https://api-docs.deepseek.com/zh-cn/guides/tool_calls
        if (modelName.contains("deepseek", ignoreCase = true)) {
            val thinking = JSONObject()
            thinking.put("type", "disabled")
            json.put("thinking", thinking)
        }

        return json.toString()
    }

    /**
     * 将 langchain4j ChatMessage 转换为 OpenAI API 请求中的 message JSON。
     *
     * 标准格式：
     * - system: {role: "system", content: "..."}
     * - user: {role: "user", content: "..."}
     * - assistant: {role: "assistant", content: null, tool_calls: [...]}  // 有 tool_calls 时
     * - assistant: {role: "assistant", content: "..."}  // 纯文本回复时
     * - tool: {role: "tool", tool_call_id: "...", content: "..."}
     *
     * 注意：tool_calls 是 assistant message 的独立字段，与 content 互斥。
     * 当存在 tool_calls 时，content 必须为 null（不设置或显式设为 null）。
     */
    private fun convertMessage(msg: ChatMessage): JSONObject {
        val json = JSONObject()
        when (msg) {
            is SystemMessage -> {
                json.put("role", "system")
                json.put("content", msg.text())
            }
            is UserMessage -> {
                json.put("role", "user")
                json.put("content", msg.singleText())
            }
            is AiMessage -> {
                val aiMsg = msg
                json.put("role", "assistant")
                val toolCalls = aiMsg.toolExecutionRequests()
                val hasToolCalls = !toolCalls.isNullOrEmpty()
                // 当存在 tool_calls 时，content 必须为 null（OpenAI / DeepSeek 标准）
                if (!hasToolCalls) {
                    json.put("content", aiMsg.text().ifBlank { "" })
                }
                if (hasToolCalls) {
                    val toolCallsArray = JSONArray()
                    for ((i, tc) in toolCalls.withIndex()) {
                        val tcJson = JSONObject()
                        tcJson.put("id", tc.id() ?: "call_$i")
                        tcJson.put("type", "function")
                        val func = JSONObject()
                        func.put("name", tc.name())
                        func.put("arguments", tc.arguments() ?: "{}")
                        tcJson.put("function", func)
                        toolCallsArray.put(tcJson)
                    }
                    json.put("tool_calls", toolCallsArray)
                }
            }
            is ToolExecutionResultMessage -> {
                json.put("role", "tool")
                json.put("content", msg.text())
                json.put("tool_call_id", msg.id())
            }
            else -> {
                json.put("role", "system")
                json.put("content", msg.toString())
            }
        }
        return json
    }

    private fun convertToolSpec(spec: ToolSpecification): JSONObject {
        val json = JSONObject()
        json.put("type", "function")
        val func = JSONObject()
        func.put("name", spec.name())
        func.put("description", spec.description() ?: "")

        val params = spec.parameters()
        if (params != null) {
            val schema = JSONObject()
            schema.put("type", "object")
            val properties = JSONObject()

            val props = params.properties()
            if (props != null) {
                for ((key, value) in props) {
                    val propSchema = JSONObject()
                    when (value) {
                        is com.mamba.agent.model.chat.request.json.JsonStringSchema -> {
                            propSchema.put("type", "string")
                        }
                        is com.mamba.agent.model.chat.request.json.JsonIntegerSchema -> {
                            propSchema.put("type", "integer")
                        }
                        is com.mamba.agent.model.chat.request.json.JsonNumberSchema -> {
                            propSchema.put("type", "number")
                        }
                        is com.mamba.agent.model.chat.request.json.JsonBooleanSchema -> {
                            propSchema.put("type", "boolean")
                        }
                        is JsonEnumSchema -> {
                            propSchema.put("type", "string")
                            val enumValues = value.enumValues()
                            if (enumValues != null) {
                                propSchema.put("enum", JSONArray(enumValues))
                            }
                        }
                    }
                    val desc = value.description()
                    if (desc != null) {
                        propSchema.put("description", desc)
                    }
                    properties.put(key, propSchema)
                }
            }
            schema.put("properties", properties)
            val required = params.required()
            if (required != null && required.isNotEmpty()) {
                schema.put("required", required)
            }
            func.put("parameters", schema)
        }
        json.put("function", func)
        return json
    }

    private fun parseResponse(responseBody: String): LlmResponse {
        val json = JSONObject(responseBody)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")

        val text = if (message.has("content") && !message.isNull("content")) {
            val content = message.optString("content", "")
            if (content.isNotBlank()) content else null
        } else {
            null
        }

        // 从文本内容中解析 Thought/Action 格式的工具调用
        val toolCalls = if (text != null) {
            extractActionsFromText(text)
        } else {
            emptyList()
        }

        val totalTokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0

        return LlmResponse(text, toolCalls, totalTokens)
    }

    /**
     * 从 ReAct 格式的文本中提取 Action 工具调用。
     * 格式：Action: tool_name({"param":"value"})
     */
    private fun extractActionsFromText(content: String): List<ToolExecutionRequest> {
        val result = mutableListOf<ToolExecutionRequest>()
        try {
            // 匹配 Action: tool_name({"param":"value"}) 格式
            // 支持无参工具如 Action: capture()
            val actionRegex = Regex("""Action:\s*(\w+)\((.*?)\)""", RegexOption.MULTILINE)
            val matches = actionRegex.findAll(content)

            for ((index, match) in matches.withIndex()) {
                val toolName = match.groupValues[1]
                val argsStr = match.groupValues[2].trim()

                // 解析参数：如果括号内有内容，应该是 JSON 格式
                val arguments = if (argsStr.isNotBlank()) {
                    // 尝试解析为 JSON，如果不是合法 JSON，包装为 JSON
                    try {
                        JSONObject(argsStr)
                        argsStr
                    } catch (_: Exception) {
                        // 不是合法 JSON，尝试包装
                        try {
                            // 可能是 "value" 格式（字符串参数）
                            if (argsStr.startsWith("\"") && argsStr.endsWith("\"")) {
                                "{\"text\":$argsStr}"
                            } else {
                                // 尝试作为简单值处理
                                "{\"value\":\"$argsStr\"}"
                            }
                        } catch (_: Exception) {
                            "{}"
                        }
                    }
                } else {
                    "{}"
                }

                Logger.d("InAppLlmClient", "Parsed Action: $toolName($arguments)")
                result.add(
                    ToolExecutionRequest.builder()
                        .id("react_call_$index")
                        .name(toolName)
                        .arguments(arguments)
                        .build()
                )
            }
        } catch (e: Exception) {
            Logger.w("InAppLlmClient", "Failed to parse actions from text: ${e.message}")
        }
        return result
    }
}
