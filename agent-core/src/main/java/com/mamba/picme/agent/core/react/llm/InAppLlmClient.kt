package com.mamba.picme.agent.core.react.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
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
        val requestBody = buildJsonRequest(messages, toolSpecs)
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

        if (toolSpecs.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (spec in toolSpecs) {
                toolsArray.put(convertToolSpec(spec))
            }
            json.put("tools", toolsArray)
            // 强制模型使用工具调用（如果支持）
            json.put("tool_choice", "auto")
        }

        return json.toString()
    }

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
                val textContent = aiMsg.text()
                if (!textContent.isNullOrBlank()) {
                    json.put("content", textContent)
                }
                val toolCalls = aiMsg.toolExecutionRequests()
                if (!toolCalls.isNullOrEmpty()) {
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
                        is dev.langchain4j.model.chat.request.json.JsonStringSchema -> {
                            propSchema.put("type", "string")
                        }
                        is dev.langchain4j.model.chat.request.json.JsonIntegerSchema -> {
                            propSchema.put("type", "integer")
                        }
                        is dev.langchain4j.model.chat.request.json.JsonNumberSchema -> {
                            propSchema.put("type", "number")
                        }
                        is dev.langchain4j.model.chat.request.json.JsonBooleanSchema -> {
                            propSchema.put("type", "boolean")
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

        val toolCalls = mutableListOf<ToolExecutionRequest>()
        if (message.has("tool_calls")) {
            val toolCallsArray = message.getJSONArray("tool_calls")
            for (i in 0 until toolCallsArray.length()) {
                val tc = toolCallsArray.getJSONObject(i)
                val func = tc.getJSONObject("function")
                val request = ToolExecutionRequest.builder()
                    .id(tc.optString("id", "call_$i"))
                    .name(func.getString("name"))
                    .arguments(func.optString("arguments", "{}"))
                    .build()
                toolCalls.add(request)
            }
        }

        // 回退机制：如果 API 没有返回 tool_calls 但 content 中包含 tool_calls JSON，尝试解析
        if (toolCalls.isEmpty() && text != null) {
            val extracted = extractToolCallsFromContent(text)
            if (extracted.isNotEmpty()) {
                toolCalls.addAll(extracted)
            }
        }

        val totalTokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0

        return LlmResponse(text, toolCalls, totalTokens)
    }

    /**
     * 从 content 文本中提取嵌入的 tool_calls JSON。
     * 某些模型会把 tool_calls 输出到 content 中而不是使用原生 function calling。
     */
    private fun extractToolCallsFromContent(content: String): List<ToolExecutionRequest> {
        val result = mutableListOf<ToolExecutionRequest>()
        try {
            // 尝试匹配 {"tool_calls":[...]} 格式
            val toolCallsRegex = Regex("""\{\s*"tool_calls"\s*:\s*(\[.*?\])\s*\}""", RegexOption.DOT_MATCHES_ALL)
            val match = toolCallsRegex.find(content)
            if (match != null) {
                val toolCallsJson = match.groupValues[1]
                val array = JSONArray(toolCallsJson)
                for (i in 0 until array.length()) {
                    val tc = array.getJSONObject(i)
                    val func = tc.getJSONObject("function")
                    val request = ToolExecutionRequest.builder()
                        .id(tc.optString("id", "call_$i"))
                        .name(func.getString("name"))
                        .arguments(func.optString("arguments", "{}"))
                        .build()
                    result.add(request)
                }
            }
        } catch (e: Exception) {
            // 解析失败，忽略
        }
        return result
    }
}
