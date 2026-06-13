package com.mamba.picme.agent.core.runtime.tool

import com.mamba.picme.agent.core.langchain4j.ToolExecutionRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

object ToolCallingOutputParser {

    private data class SimpleToolCall(
        val name: String,
        val arguments: Map<String, Any?>?
    )

    private data class OpenAiToolCallFunction(
        val name: String,
        val arguments: String
    )

    private data class OpenAiToolCall(
        val id: String,
        val type: String = "function",
        val function: OpenAiToolCallFunction
    )

    private data class OpenAiToolCallsWrapper(
        val tool_calls: List<OpenAiToolCall>
    )

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val simpleAdapter = moshi.adapter(SimpleToolCall::class.java)
    private val simpleListAdapter = moshi.adapter<List<SimpleToolCall>>(
        Types.newParameterizedType(List::class.java, SimpleToolCall::class.java)
    )
    private val openAiWrapperAdapter = moshi.adapter(OpenAiToolCallsWrapper::class.java)

    fun parse(text: String, config: ToolCallingConfig = ToolCallingConfig()): List<ToolExecutionRequest> {
        if (text.isBlank()) return emptyList()

        return when (config.mode) {
            ToolCallingMode.OPENAI_TOOLS -> parseOpenAiTools(text)
            ToolCallingMode.REACT -> parseReAct(text)
        }
    }

    private fun parseOpenAiTools(text: String): List<ToolExecutionRequest> {
        val trimmed = text.trim()

        // 1. OpenAI 完整 wrapper: {"tool_calls":[...]}
        if (trimmed.contains("\"tool_calls\"")) {
            runCatching {
                openAiWrapperAdapter.fromJson(trimmed)?.tool_calls?.map { it.toRequest() }
            }.getOrNull()?.let { return it }
        }

        // 2. <tool_call>...</tool_call> 标签（兼容旧格式）
        val tagRegex = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val tagRequests = tagRegex.findAll(text).mapNotNull { match ->
            parseSimple(match.groupValues[1].trim())
        }.toList()
        if (tagRequests.isNotEmpty()) return tagRequests

        // 3. JSON 数组 [{"name":"...","arguments":{}}]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            runCatching {
                simpleListAdapter.fromJson(trimmed)?.map { it.toRequest() }
            }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }

        // 4. 单个 JSON 对象
        return parseSimple(trimmed)?.let { listOf(it) } ?: emptyList()
    }

    private fun parseReAct(text: String): List<ToolExecutionRequest> {
        // Thought: ... Action: {"name":"...","arguments":{}}
        val actionRegex = Regex("""Action:\s*(\{.*?\})\s*$""", RegexOption.DOT_MATCHES_ALL)
        val matches = actionRegex.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        return matches.mapNotNull { match ->
            parseSimple(match.groupValues[1].trim())
        }
    }

    private fun parseSimple(json: String): ToolExecutionRequest? {
        return runCatching {
            simpleAdapter.fromJson(json)?.toRequest()
        }.getOrNull()
    }

    private fun SimpleToolCall.toRequest(): ToolExecutionRequest {
        return ToolExecutionRequest(
            id = UUID.randomUUID().toString(),
            name = name,
            arguments = arguments?.let { mapToJsonString(it) } ?: "{}"
        )
    }

    private fun OpenAiToolCall.toRequest(): ToolExecutionRequest {
        return ToolExecutionRequest(
            id = id,
            name = function.name,
            arguments = function.arguments
        )
    }

    private fun mapToJsonString(map: Map<String, Any?>): String {
        val adapter = moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        return adapter.toJson(map)
    }
}
