package com.mamba.picme.agent.core.runtime.tool

import com.mamba.picme.agent.core.langchain4j.ToolExecutionRequest
import com.mamba.picme.agent.core.platform.logging.Logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

object ToolCallingOutputParser {

    private const val TAG = "ToolCallingOutputParser"

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

        val repaired = repairJson(text)
        if (repaired != text) {
            Logger.d(TAG, "Repaired JSON: '${text.replace("\n", "\\n")}' -> '${repaired.replace("\n", "\\n")}'")
        }

        return when (config.mode) {
            ToolCallingMode.OPENAI_TOOLS -> parseOpenAiTools(repaired)
            ToolCallingMode.REACT -> parseReAct(repaired)
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
        val singleResult = parseSimple(trimmed)?.let { listOf(it) } ?: emptyList()
        if (singleResult.isNotEmpty()) return singleResult

        // 5. 终极模糊提取：直接扫描已知工具名，完全不依赖 JSON 结构
        Logger.d(TAG, "All structured parsing failed, trying fuzzy extraction")
        return fuzzyExtractToolCall(trimmed)
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
        val results = mutableListOf<ToolExecutionRequest>()
    
        // 用括号匹配精确提取 function 块，替代 [^}]+（不能处理嵌套对象）
        val funcStartRegex = Regex(""""function"\s*:\s*\{""")
        var searchFrom = 0
    
        while (true) {
            val match = funcStartRegex.find(trimmed, searchFrom) ?: break
            val funcBlockStart = match.range.last + 1
    
            // 括号匹配找到 function 块的闭合 }
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
    
                // 提取 name
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
     * 使用括号匹配确保嵌套对象正确提取。
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
                // 括号匹配精确提取完整对象
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

    /**
     * 自动修复小模型输出的不合法 JSON，在解析前执行。
     *
     * 端侧小模型（Qwen3-1.7B/2B）输出的 JSON 常见问题：
     * 1. 多余/缺少闭合括号（{}、[]）
     * 2. 末尾有多余字符
     * 3. <think> 标签未闭合
     * 4. 字符串使用单引号
     * 5. 末尾多余逗号
     * 6. key 缺少引号
     */
    private fun repairJson(raw: String): String {
        var s = raw.trim()

        // 1. 移除 <think>...</think> 和 <thinking>...</thinking>（含未闭合）
        s = s.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        s = s.replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "").trim()
        s = s.replace(Regex("<think>.*"), "").trim()  // 未闭合
        s = s.replace(Regex("<thinking>.*"), "").trim()  // 未闭合
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

        // 5. 定位 JSON 结束位置（找到主结构完全闭合后的位置）
        val endPos = findJsonEnd(s)
        if (endPos > 0 && endPos < s.length) {
            s = s.substring(0, endPos).trim()
        }

        // 6. 单引号转双引号（只在 JSON 上下文中）
        // 用 " " 替换 ' ' 时小心不要破坏内部转义的 ' 。
        s = s.replace("\\'", "\u0000_ESC_SQ")
        s = s.replace("'", "\"")
        s = s.replace("\u0000_ESC_SQ", "\\'")

        // 7. 移除末尾逗号（在 } 或 ] 之前）
        s = s.replace(Regex(",\\s*\\}"), "}")
        s = s.replace(Regex(",\\s*\\]"), "]")

        // 8. 补充未闭合的括号
        s = balanceBraces(s)

        return s
    }

    /**
     * 找到 JSON 主结构完全闭合的位置。
     * 遍历字符串，跟踪括号深度，当深度回到 0 时记录为可能的结束点。
     * 返回主结构最后一次完全闭合后的位置。
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
                        // 记录 JSON 主结构闭合位置
                        lastEnd = i + 1
                    }
                }
            }
        }

        return if (lastEnd > 0) lastEnd else s.length
    }

    /**
     * 补充括号使 JSON 平衡。
     * 统计 { } [ ] 数量，在末尾追加缺失的括号。
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

        // 如果括号不平衡，在末尾添加缺失的
        val sb = StringBuilder(s)
        while (braceCount > 0) { sb.append('}'); braceCount-- }
        while (braceCount < 0 && sb.length > 0 && sb.last() == '}') {
            // 如果有多余的 } 在末尾，移除
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
     * 直接扫描文本中的已知工具名和参数，完全不依赖 JSON 结构。
     *
     * 处理场景：
     * - JSON 格式严重损坏，Moshi 和正则都失败
     * - 模型在 JSON 周围输出了大量自然语言
     * - 只有零散的工具名可辨认
     */
    private fun fuzzyExtractToolCall(text: String): List<ToolExecutionRequest> {
        val results = mutableListOf<ToolExecutionRequest>()

        // 已知工具名列表（需要保持与注册的工具一致）
        val knownTools = setOf(
            "navigate_to", "navigateTo",
            "launch_app", "launchApp",
            "set_beauty", "setBeauty", "adjust_beauty",
            "take_photo", "takePhoto",
            "open_gallery", "openGallery",
            "switch_camera", "switchCamera"
        )

        // 模式1: 搜索 "name":"xxx" 模式（key value 格式）
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

        // 模式2: 搜索 name=xxx 或 name:xxx 格式（非 JSON）
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

        // 模式3: 在文本中直接搜索已知工具名（如 "navigate_to" 出现在任何位置）
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
     * 尝试多种格式：
     * - JSON 对象: "destination":"camera","source":"chat"
     * - 简单键值: destination=camera
     */
    private fun extractArgumentsNearby(text: String, aroundPosition: Int): String {
        // 在工具名前后 200 字符内搜索
        val searchStart = maxOf(0, aroundPosition - 100)
        val searchEnd = minOf(text.length, aroundPosition + 200)
        val context = text.substring(searchStart, searchEnd)

        // 尝试提取 JSON 对象格式的 arguments
        val argObjectRegex = Regex(""""arguments"\s*[:=]\s*(\{.*\})""")
        val argMatch = argObjectRegex.find(context)
        if (argMatch != null) {
            val objStr = argMatch.groupValues[1].trim()
            // 验证是不是完整的 JSON 对象
            if (objStr.startsWith("{") && objStr.endsWith("}")) {
                return objStr
            }
        }

        // 尝试提取字符串格式的 arguments
        val argStringRegex = Regex(""""arguments"\s*[:=]\s*"(.*?)"""")
        val strMatch = argStringRegex.find(context)
        if (strMatch != null) {
            return strMatch.groupValues[1]
        }

        // 尝试提取 destination 等常见参数
        val destRegex = Regex(""""destination"\s*[:=]\s*"([^"]+)"""")
        val destMatch = destRegex.find(context)
        if (destMatch != null) {
            return "{\"destination\":\"${destMatch.groupValues[1]}\"}"
        }

        return "{}"
    }
}
