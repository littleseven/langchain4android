package com.mamba.picme.agent.core.remote.parser

import com.mamba.picme.agent.core.api.ToolExecutionRequest
import com.mamba.picme.agent.core.platform.logging.Logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

/**
 * 远程 LLM Tool Calls 解析器
 *
 * 将远程 LLM 输出的标准 OpenAI tool_calls 格式解析为 [ToolExecutionRequest] 列表。
 * 协议格式：{"tool_calls":[{"id":"call_x","type":"function","function":{"name":"...","arguments":"..."}}]}
 *
 * 仅支持 OpenAI tool_calls 协议，不支持 REACT，不支持 method/params 兜底。
 */
object ToolCallParser {

    private const val TAG = "ToolCallParser"

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

    private val openAiWrapperAdapter = moshi.adapter(OpenAiToolCallsWrapper::class.java)
    private val flexibleMapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parse(text: String): List<ToolExecutionRequest> {
        if (text.isBlank()) return emptyList()

        val repaired = repairJson(text)
        if (repaired != text) {
            Logger.d(TAG, "Repaired JSON: '${text.replace("\n", "\\n")}' -> '${repaired.replace("\n", "\\n")}'")
        }

        return parseOpenAiTools(repaired)
    }

    private fun parseOpenAiTools(text: String): List<ToolExecutionRequest> {
        val trimmed = text.trim()

        if (trimmed.contains("\"tool_calls\"")) {
            // 1. 宽松解析优先：兼容 arguments 为对象（推荐）或字符串两种格式
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

        // 2. JSON 数组 [{"name":"...","arguments":{}}]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            runCatching {
                parseSimpleArray(trimmed)
            }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }

        // 3. 单个 JSON 对象
        val singleResult = parseSimple(trimmed)?.let { listOf(it) } ?: emptyList()
        if (singleResult.isNotEmpty()) return singleResult

        // 4. 终极模糊提取
        Logger.d(TAG, "All structured parsing failed, trying fuzzy extraction")
        return fuzzyExtractToolCall(trimmed)
    }

    /**
     * 宽松解析 OpenAI tool_calls：兼容 arguments 为对象或字符串的情况。
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

    /**
     * 正则兜底解析：当 JSON 被截断时从原始文本中提取 function name 和 arguments。
     */
    private fun parseOpenAiToolsByRegex(text: String): List<ToolExecutionRequest> {
        val trimmed = text.trim()
        val results = mutableListOf<ToolExecutionRequest>()

        val funcStartRegex = Regex(""""function"\s*:\s*\{""")
        var searchFrom = 0

        while (true) {
            val match = funcStartRegex.find(trimmed, searchFrom) ?: break
            val funcBlockStart = match.range.last + 1

            var depth = 1
            var inStr = false
            var funcBlockEnd = -1
            var i = funcBlockStart
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (inStr) {
                    if (c == '"' && trimmed[i - 1] != '\\') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                funcBlockEnd = i + 1
                                break
                            }
                        }
                    }
                }
                i++
            }

            if (funcBlockEnd > 0) {
                val functionBlock = trimmed.substring(match.range.first, funcBlockEnd)

                val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(functionBlock)?.groupValues?.get(1)
                if (name != null) {
                    val argumentsString = extractArgumentsFromFunctionBlock(functionBlock)
                    results.add(ToolExecutionRequest(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        arguments = argumentsString
                    ))
                }
            }

            searchFrom = if (funcBlockEnd > 0) funcBlockEnd else match.range.last + 1
        }

        return results
    }

    /**
     * 从 function 块中提取 arguments（兼容对象和字符串两种格式）。
     */
    private fun extractArgumentsFromFunctionBlock(functionBlock: String): String {
        val argStartRegex = Regex(""""arguments"\s*:\s*""")
        val argStartMatch = argStartRegex.find(functionBlock) ?: return "{}"
        val afterKey = argStartMatch.range.last + 1
        if (afterKey >= functionBlock.length) return "{}"

        return when (functionBlock[afterKey]) {
            '"' -> {
                // 字符串格式："arguments":"{\"destination\":\"camera\"}"
                val sb = StringBuilder()
                var j = afterKey + 1
                while (j < functionBlock.length) {
                    val ch = functionBlock[j]
                    if (ch == '"') {
                        if (j > 0 && functionBlock[j - 1] != '\\') break
                    }
                    sb.append(ch)
                    j++
                }
                sb.toString()
            }
            '{' -> {
                // 对象格式："arguments":{"destination":"camera"}
                var objDepth = 1
                var inObjStr = false
                for (j in afterKey + 1 until functionBlock.length) {
                    val ch = functionBlock[j]
                    if (inObjStr) {
                        if (ch == '"' && functionBlock[j - 1] != '\\') inObjStr = false
                    } else {
                        when (ch) {
                            '"' -> inObjStr = true
                            '{' -> objDepth++
                            '}' -> {
                                objDepth--
                                if (objDepth == 0) {
                                    return functionBlock.substring(afterKey, j + 1)
                                }
                            }
                        }
                    }
                }
                "{}"
            }
            else -> "{}"
        }
    }

    /**
     * 解析简单 JSON 对象：{"name":"...","arguments":{...}}
     */
    private data class SimpleToolCall(
        val name: String,
        val arguments: Map<String, Any?>?
    )

    private val simpleAdapter = moshi.adapter(SimpleToolCall::class.java)
    private val simpleListAdapter = moshi.adapter<List<SimpleToolCall>>(
        Types.newParameterizedType(List::class.java, SimpleToolCall::class.java)
    )

    private fun parseSimple(json: String): ToolExecutionRequest? {
        return runCatching {
            simpleAdapter.fromJson(json)?.toRequest()
        }.getOrNull()
    }

    private fun parseSimpleArray(text: String): List<ToolExecutionRequest>? {
        return runCatching {
            simpleListAdapter.fromJson(text)?.map { it.toRequest() }
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

    /**
     * 自动修复小模型输出的不合法 JSON，在解析前执行。
     */
    private fun repairJson(raw: String): String {
        var s = raw.trim()

        // 1. 移除 <think>...</think> 和 <thinking>...</thinking>（含未闭合）
        s = s.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        s = s.replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "").trim()
        s = s.replace(Regex("<think>.*"), "").trim()
        s = s.replace(Regex("<thinking>.*"), "").trim()
        s = s.replace("</think>", "").trim()
        s = s.replace("</thinking>", "").trim()

        // 2. 移除代码块标记
        s = s.replace(Regex("^```[^\\n]*\\n?", RegexOption.MULTILINE), "").trim()
        s = s.replace(Regex("\\n?```\\s*\$"), "").trim()
        s = s.replace("```", "").trim()

        // 3. 如果以 JSON 开头但不包含 { 或 [，可能全是杂音
        if (!s.contains("{") && !s.contains("[")) return ""

        // 4. 定位第一个 { 或 [ 作为 JSON 起点
        val firstBrace = s.indexOf('{')
        val firstBracket = s.indexOf('[')
        val jsonStart = when {
            firstBrace >= 0 && firstBracket >= 0 -> minOf(firstBrace, firstBracket)
            firstBrace >= 0 -> firstBrace
            firstBracket >= 0 -> firstBracket
            else -> return ""
        }
        s = s.substring(jsonStart)

        // 5. 定位 JSON 结束位置
        val endPos = findJsonEnd(s)
        if (endPos > 0 && endPos < s.length) {
            s = s.substring(0, endPos).trim()
        }

        // 6. 单引号转双引号
        s = s.replace("\\'", "\u0000_ESC_SQ")
        s = s.replace("'", "\"")
        s = s.replace("\u0000_ESC_SQ", "\\'")

        // 7. 移除末尾逗号
        s = s.replace(Regex(",\\s*\\}"), "}")
        s = s.replace(Regex(",\\s*\\]"), "]")

        // 8. 补充未闭合的括号
        s = balanceBraces(s)

        return s
    }

    /**
     * 找到 JSON 主结构完全闭合的位置。
     */
    private fun findJsonEnd(s: String): Int {
        var depth = 0
        var inString = false
        var lastEnd = -1

        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        lastEnd = i + 1
                    }
                }
            }
        }

        return if (lastEnd > 0) lastEnd else s.length
    }

    /**
     * 补充括号使 JSON 平衡。
     */
    private fun balanceBraces(s: String): String {
        var inString = false
        var braceCount = 0
        var bracketCount = 0

        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> braceCount++
                '}' -> braceCount--
                '[' -> bracketCount++
                ']' -> bracketCount--
            }
        }

        val sb = StringBuilder(s)
        while (braceCount > 0) { sb.append('}'); braceCount-- }
        while (braceCount < 0 && sb.length > 0 && sb.last() == '}') {
            val startPos = sb.length - 1
            var pos = startPos
            while (pos >= 0 && sb[pos] == '}') pos--
            val removeCount = minOf(-braceCount, startPos - pos)
            if (removeCount > 0) {
                sb.delete(startPos - removeCount + 1, startPos + 1)
                braceCount += removeCount
            }
        }
        while (bracketCount > 0) { sb.append(']'); bracketCount-- }
        while (bracketCount < 0 && sb.length > 0 && sb.last() == ']') {
            val startPos = sb.length - 1
            var pos = startPos
            while (pos >= 0 && sb[pos] == ']') pos--
            val removeCount = minOf(-bracketCount, startPos - pos)
            if (removeCount > 0) {
                sb.delete(startPos - removeCount + 1, startPos + 1)
                bracketCount += removeCount
            }
        }

        return sb.toString()
    }

    /**
     * 终极模糊提取：当所有结构化解析都失败时，
     * 直接扫描文本中的已知工具名和参数。
     */
    private fun fuzzyExtractToolCall(text: String): List<ToolExecutionRequest> {
        val results = mutableListOf<ToolExecutionRequest>()

        val knownTools = setOf(
            "navigate_to", "navigateTo",
            "launch_app", "launchApp",
            "set_beauty", "setBeauty", "adjust_beauty",
            "take_photo", "takePhoto",
            "open_gallery", "openGallery",
            "switch_camera", "switchCamera"
        )

        // 模式1: 搜索 "name":"xxx" 模式
        val nameValueRegex = Regex(""""name"\s*[:=]\s*"([^"]+)"""")
        for (match in nameValueRegex.findAll(text)) {
            val name = match.groupValues[1]
            if (name in knownTools) {
                val request = ToolExecutionRequest(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    arguments = extractArgumentsNearby(text, match.range.last)
                )
                if (results.none { it.name == request.name }) {
                    results.add(request)
                }
            }
        }
        if (results.isNotEmpty()) {
            Logger.d(TAG, "Fuzzy extracted: ${results.map { "${it.name}(${it.arguments})" }}")
            return results
        }

        // 模式2: 搜索 name=xxx 或 name:xxx 格式
        val simpleNameRegex = Regex("""name\s*[:=]\s*([a-zA-Z_]+""")
        for (match in simpleNameRegex.findAll(text)) {
            val name = match.groupValues[1].trimEnd('"', '\'', ' ')
            if (name in knownTools) {
                val request = ToolExecutionRequest(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    arguments = extractArgumentsNearby(text, match.range.last)
                )
                if (results.none { it.name == request.name }) {
                    results.add(request)
                }
            }
        }
        if (results.isNotEmpty()) {
            Logger.d(TAG, "Fuzzy extracted (simple): ${results.map { "${it.name}(${it.arguments})" }}")
            return results
        }

        // 模式3: 直接搜索已知工具名
        for (toolName in knownTools) {
            if (text.contains(toolName, ignoreCase = true)) {
                val request = ToolExecutionRequest(
                    id = UUID.randomUUID().toString(),
                    name = toolName,
                    arguments = extractArgumentsNearby(text, text.indexOf(toolName))
                )
                if (results.none { it.name == request.name }) {
                    results.add(request)
                }
            }
        }
        if (results.isNotEmpty()) {
            Logger.d(TAG, "Fuzzy extracted (keyword): ${results.map { "${it.name}(${it.arguments})" }}")
            return results
        }

        return emptyList()
    }

    /**
     * 在文本中工具名附近搜索 arguments 参数。
     */
    private fun extractArgumentsNearby(text: String, aroundPosition: Int): String {
        val searchStart = maxOf(0, aroundPosition - 100)
        val searchEnd = minOf(text.length, aroundPosition + 200)
        val context = text.substring(searchStart, searchEnd)

        val argObjectRegex = Regex(""""arguments"\s*[:=]\s*(\{.*\})""")
        val argMatch = argObjectRegex.find(context)
        if (argMatch != null) {
            val objStr = argMatch.groupValues[1].trim()
            if (objStr.startsWith("{") && objStr.endsWith("}")) {
                return objStr
            }
        }

        val argStringRegex = Regex(""""arguments"\s*[:=]\s*"(.*?)"""")
        val strMatch = argStringRegex.find(context)
        if (strMatch != null) {
            return strMatch.groupValues[1]
        }

        val destRegex = Regex(""""destination"\s*[:=]\s*"([^"]+)"""")
        val destMatch = destRegex.find(context)
        if (destMatch != null) {
            return "{\"destination\":\"${destMatch.groupValues[1]}\"}"
        }

        return "{}"
    }
}
