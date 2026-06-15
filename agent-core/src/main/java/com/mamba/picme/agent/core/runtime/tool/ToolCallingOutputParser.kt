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
    private val flexibleMapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parse(text: String, config: ToolCallingConfig = ToolCallingConfig()): List<ToolExecutionRequest> {
        if (text.isBlank()) return emptyList()

        return when (config.mode) {
            ToolCallingMode.OPENAI_TOOLS -> parseOpenAiTools(text)
            ToolCallingMode.REACT -> parseReAct(text)
        }
    }

    private fun parseOpenAiTools(text: String): List<ToolExecutionRequest> {
        val trimmed = text.trim()

        if (trimmed.contains("\"tool_calls\"")) {
            // 1. 宽松解析优先：兼容 arguments 为对象（推荐）或字符串两种格式
            // OpenAI 标准中 arguments 是字符串，但端侧小模型输出对象更可靠
            runCatching {
                parseOpenAiToolsLoosely(trimmed)
            }.getOrNull()?.let { if (it.isNotEmpty()) return it }

            // 1.5 严格解析：arguments 必须为字符串
            runCatching {
                openAiWrapperAdapter.fromJson(trimmed)?.tool_calls?.map { it.toRequest() }
            }.getOrNull()?.let { return it }

            // 1.6 正则兜底：当 JSON 被截断时从原始文本中提取 function name
            runCatching {
                parseOpenAiToolsByRegex(trimmed)
            }.getOrNull()?.let { if (it.isNotEmpty()) return it }
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

    /**
     * 宽松解析 OpenAI tool_calls：兼容 arguments 为对象或字符串的情况。
     *
     * 部分端侧/远程模型会把 arguments 直接输出为 JSON 对象（如
     * {"destination":"gallery"}），而非标准字符串 "{\"destination\":\"gallery\"}"。
     * 这里把对象重新序列化为字符串，保证下游统一按字符串处理。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseOpenAiToolsLoosely(text: String): List<ToolExecutionRequest> {
        val map = flexibleMapAdapter.fromJson(text) ?: return emptyList()
        val toolCalls = map["tool_calls"] as? List<Map<String, Any?>> ?: return emptyList()

        return toolCalls.mapNotNull { toolCall ->
            val id = toolCall["id"] as? String ?: UUID.randomUUID().toString()
            val function = toolCall["function"] as? Map<String, Any?> ?: return@mapNotNull null
            val name = function["name"] as? String ?: return@mapNotNull null
            val arguments = function["arguments"]
            val argumentsString = when (arguments) {
                is String -> arguments
                is Map<*, *> -> mapToJsonString(arguments as Map<String, Any?>)
                else -> "{}"
            }
            ToolExecutionRequest(
                id = id,
                name = name,
                arguments = argumentsString
            )
        }
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

    /**
     * 正则兜底解析：当 JSON 被截断时（如 maxNewTokens 不足导致 "]} 缺失），
     * 或有多余字符时，从原始文本中提取 function name 和 arguments。
     *
     * 截断示例：
     * {"tool_calls":[{"id":"call_1","type":"function","function":{"name":"navigate_to","arguments":"{\"destination\":\"camera\"}"}]}
     *                                                                                          ↑ 缺少 }]
     *
     * 多余字符示例（小模型常见）：
     * {"tool_calls":[{"id":"call_1","type":"function","function":{"name":"navigate_to","arguments":{"destination":"camera"}}]}}
     *                                                                                             ↑ 多一个 }
     */
    private fun parseOpenAiToolsByRegex(text: String): List<ToolExecutionRequest> {
        val trimmed = text.trim()
    
        // 找到所有 "function" 块的起始和结束位置
        val functionRegex = Regex(""""function"s*:s*{([^}]+)}?""")
        val functionMatches = functionRegex.findAll(trimmed)
    
        return functionMatches.mapNotNull { match ->
            val functionBlock = match.groupValues[1]
    
            // 从 function 块中提取 name
            val nameRegex = Regex(""""name"s*:s*"([^"]+)"""")
            val name = nameRegex.find(functionBlock)?.groupValues?.get(1) ?: return@mapNotNull null
    
            // 从 function 块中提取 arguments（兼容对象和字符串两种格式）
            val argBlockRegex = Regex(""""arguments"s*:s*({.*?}|\"(.*?)\")""")
            val argBlockMatch = argBlockRegex.find(functionBlock)
            val argumentsString = argBlockMatch?.let { matchResult ->
                // groupValues[1] = {...} 对象格式，groupValues[2] = "..." 字符串格式（不含""）
                val objectArgs = matchResult.groupValues[1]
                val stringArgs = matchResult.groupValues[2]
                when {
                    objectArgs.isNotBlank() && objectArgs.startsWith("{") -> objectArgs
                    stringArgs.isNotBlank() -> "{\"$stringArgs\"}"
                    else -> "{}"
                }
            } ?: "{}"
    
            ToolExecutionRequest(
                id = UUID.randomUUID().toString(),
                name = name,
                arguments = argumentsString
            )
        }.toList()
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
