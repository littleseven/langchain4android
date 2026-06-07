package com.picme.agent.core

import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.agent.core.AgentLogger
import com.picme.agent.core.model.AgentCommand
import com.picme.agent.core.model.AgentContext
import com.picme.agent.core.model.AgentIdGenerator
import com.picme.agent.core.model.MediaType
import com.picme.beauty.api.BeautySettings

/**
 * Agent 命令解析器
 *
 * 将 LLM 文本响应解析为结构化 [AgentCommand]。
 * 只支持精简 JSON 格式（method + params），不兼容旧格式。
 * 提取为独立 object 以便在纯 JVM 单元测试中直接调用，
 * 避免实例化 [AgentOrchestrator] 时触发 JNI/MNN 加载。
 */
object AgentCommandParser {

    private const val TAG = "AgentCommandParser"

    /**
     * 解析 LLM 响应为 AgentCommand
     *
     * 支持精简 JSON 格式：{"method":"...","params":{...}}
     * 不兼容旧格式（不再支持 {"action":"..."}）。
     *
     * @param response LLM 原始文本输出
     * @param context 当前 Agent 上下文
     * @return 解析后的命令
     */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand {
        var cleaned = response.trim()

        // 1. 移除 <think>...</think> 和 <thinking>...</thinking> 标签（含未闭合情况）
        val thinkTags = listOf("<think>" to "</think>", "<thinking>" to "</thinking>")
        for ((startTag, endTag) in thinkTags) {
            while (true) {
                val thinkStart = cleaned.indexOf(startTag)
                val thinkEnd = cleaned.indexOf(endTag)
                if (thinkStart >= 0 && thinkEnd > thinkStart) {
                    // 先尝试从 think 标签内部提取 JSON（小模型常把 JSON 藏在思考过程里）
                    val thinkContent = cleaned.substring(thinkStart + startTag.length, thinkEnd)
                    val innerJson = tryExtractJsonFromRaw(thinkContent)
                    if (innerJson != null) {
                        cleaned = innerJson
                        break
                    }
                    cleaned = cleaned.removeRange(thinkStart, thinkEnd + endTag.length).trim()
                } else {
                    break
                }
            }
            // 处理未闭合开始标签：尝试从标签后提取 JSON
            val orphanStart = cleaned.indexOf(startTag)
            if (orphanStart >= 0) {
                val afterTag = cleaned.substring(orphanStart + startTag.length).trim()
                val beforeTag = cleaned.substring(0, orphanStart).trim()
                // 优先使用标签后的内容（通常 JSON 在 think 标签后）
                cleaned = if (afterTag.contains("method")) {
                    afterTag
                } else {
                    beforeTag
                }
            }
            // 处理单独的结束标签（如模型只输出了 </think>）
            cleaned = cleaned.replace(endTag, "").trim()
        }

        // 2. 移除 markdown 代码块标记 (```json ... ``` 或 ``` ... ```)
        cleaned = cleaned.replace(Regex("^```\\w*\\n?"), "").replace(Regex("\\n?```\\s*$"), "").trim()

        AgentLogger.i(TAG, "Cleaned response: '$cleaned'")

        // 3. 检查是否包含 JSON method 字段
        val hasJsonMethod = cleaned.contains("method")
        if (!hasJsonMethod) {
            // 兜底 1：尝试从原始响应中直接提取 JSON（绕过 think 标签截断问题）
            val fallbackJson = tryExtractJsonFromRaw(response)
            if (fallbackJson != null) {
                AgentLogger.i(TAG, "Fallback JSON extraction succeeded: '$fallbackJson'")
                cleaned = fallbackJson
            } else {
                // 兜底 2：关键词匹配（小模型不输出 JSON 时的最终防线）
                // 先尝试清理后的内容，再尝试原始响应（think 标签内可能包含关键词）
                val keywordCommand = tryParseByKeywords(cleaned)
                    ?: tryParseByKeywords(response)
                if (keywordCommand != null) {
                    AgentLogger.i(TAG, "Keyword fallback matched: ${keywordCommand::class.simpleName}")
                    return keywordCommand
                }
                AgentLogger.d(TAG, "No JSON method found, treating as free chat")
                return AgentCommand.TextReply(message = cleaned.ifBlank { "你好，我是小觅，有什么可以帮你的吗？" })
            }
        }

        // 4. 提取 JSON 部分
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        val json = if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            cleaned
        }

        return try {
            // 只支持 method + params 格式
            val method = extractJsonField(json, "method")
            val commandId = extractJsonInt(json, "id") ?: AgentIdGenerator.nextId()

            val effectiveAction = method ?: "text_reply"

            // 如果有 params 对象，将 params 中的字段合并到 json 中用于解析
            val paramsJson = extractJsonObject(json, "params")
            val mergedJson = if (paramsJson != null) {
                // 将 params 内容扁平化合并到顶层，便于现有解析逻辑处理
                mergeParamsIntoJson(json, paramsJson)
            } else {
                json
            }

            parseCommandByMethod(effectiveAction, mergedJson, context, cleaned, commandId)
        } catch (exception: Exception) {
            AgentLogger.w(TAG, "Failed to parse LLM response, fallback to text: $json", exception)
            AgentCommand.TextReply(message = cleaned.ifBlank { "收到你的消息了，但没理解具体意图，请再描述一下~" })
        }
    }

    /**
     * 根据 method 字段解析为具体命令
     *
     * @param method method 字段值
     * @param json 原始 JSON 字符串（已合并 params）
     * @param context 当前 Agent 上下文
     * @param fallbackText 解析失败时的回退文本
     * @param commandId 命令唯一标识（32位自增整型）
     * @return 解析后的命令
     */
    fun parseCommandByMethod(
        method: String,
        json: String,
        context: AgentContext,
        fallbackText: String,
        commandId: Int = AgentIdGenerator.nextId()
    ): AgentCommand {
        return when (method) {
            "adjust_beauty" -> {
                val smoothing = extractJsonFloat(json, "smoothing") ?: context.beautySettings.smoothing
                val whitening = extractJsonFloat(json, "whitening") ?: context.beautySettings.whitening
                val slimFace = extractJsonFloat(json, "slim_face") ?: context.beautySettings.slimFace
                val bigEyes = extractJsonFloat(json, "big_eyes") ?: context.beautySettings.bigEyes
                val lipColor = extractJsonFloat(json, "lip_color") ?: context.beautySettings.lipColor
                val blush = extractJsonFloat(json, "blush") ?: context.beautySettings.blush
                val eyebrow = extractJsonFloat(json, "eyebrow") ?: context.beautySettings.eyebrow
                AgentCommand.AdjustBeauty(
                    commandId = commandId,
                    settings = context.beautySettings.copy(
                        enabled = true,
                        smoothing = smoothing,
                        whitening = whitening,
                        slimFace = slimFace,
                        bigEyes = bigEyes,
                        lipColor = lipColor,
                        blush = blush,
                        eyebrow = eyebrow
                    )
                )
            }
            "switch_filter" -> {
                val filterName = extractJsonField(json, "filter") ?: "NONE"
                val filterType = resolveFilterType(filterName)
                AgentCommand.SwitchFilter(commandId = commandId, filterType = filterType)
            }
            "switch_style" -> {
                val styleName = extractJsonField(json, "style") ?: "NONE"
                val styleFilter = resolveStyleFilter(styleName)
                AgentCommand.SwitchStyle(commandId = commandId, styleFilter = styleFilter)
            }
            "switch_scene" -> {
                val scene = extractJsonField(json, "scene") ?: "none"
                AgentCommand.SwitchScene(commandId = commandId, sceneName = scene)
            }
            "switch_ratio" -> {
                val ratio = extractJsonField(json, "ratio") ?: "full"
                AgentCommand.SwitchRatio(commandId = commandId, ratio = ratio)
            }
            "adjust_exposure" -> {
                val exposure = extractJsonInt(json, "exposure") ?: 0
                AgentCommand.AdjustExposure(commandId = commandId, exposure = exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = extractJsonFloat(json, "zoom") ?: 1f
                AgentCommand.AdjustZoom(commandId = commandId, zoomRatio = zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera(commandId = commandId)
            "capture", "photo" -> AgentCommand.CapturePhoto(commandId = commandId)
            "toggle_recording" -> AgentCommand.ToggleRecording(commandId = commandId)
            "delay" -> {
                val delayMs = extractJsonInt(json, "delay_ms") ?: (extractJsonInt(json, "delay_seconds")?.times(1000) ?: 3000)
                AgentCommand.Delay(commandId = commandId, delayMs = delayMs.coerceIn(1, 300000).toLong())
            }
            "switch_mode" -> {
                val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(commandId = commandId, mode = mode)
            }

            // ===== Gallery 命令 =====
            "view_media" -> {
                val mediaId = extractJsonField(json, "media_id")
                AgentCommand.ViewMedia(commandId = commandId, mediaId = mediaId)
            }
            "delete_media" -> {
                val mediaIds = extractJsonStringList(json, "media_ids")
                AgentCommand.DeleteMedia(commandId = commandId, mediaIds = mediaIds)
            }
            "share_media" -> {
                val mediaIds = extractJsonStringList(json, "media_ids")
                AgentCommand.ShareMedia(commandId = commandId, mediaIds = mediaIds)
            }
            "select_media" -> {
                val mediaId = extractJsonField(json, "media_id") ?: ""
                val selected = extractJsonBoolean(json, "selected") ?: true
                AgentCommand.SelectMedia(commandId = commandId, mediaId = mediaId, selected = selected)
            }
            "search_media" -> {
                val query = extractJsonField(json, "query") ?: ""
                AgentCommand.SearchMedia(commandId = commandId, query = query)
            }
            "switch_view_mode" -> {
                val mode = extractJsonField(json, "mode") ?: "grid"
                AgentCommand.SwitchViewMode(commandId = commandId, mode = mode)
            }
            "favorite_media" -> {
                val mediaId = extractJsonField(json, "media_id") ?: ""
                val favorite = extractJsonBoolean(json, "favorite") ?: true
                AgentCommand.FavoriteMedia(commandId = commandId, mediaId = mediaId, favorite = favorite)
            }

            // ===== 导航命令 =====
            "navigate_to" -> {
                val destination = extractJsonField(json, "destination") ?: ""
                AgentCommand.NavigateTo(commandId = commandId, destination = destination)
            }
            "go_back" -> AgentCommand.GoBack(commandId = commandId)

            // ===== 设置命令 =====
            "change_theme" -> {
                val theme = extractJsonField(json, "theme") ?: "system"
                AgentCommand.ChangeTheme(commandId = commandId, theme = theme)
            }
            "change_language" -> {
                val language = extractJsonField(json, "language") ?: "zh"
                AgentCommand.ChangeLanguage(commandId = commandId, language = language)
            }
            "download_model" -> {
                val modelId = extractJsonField(json, "model_id") ?: ""
                AgentCommand.DownloadModel(commandId = commandId, modelId = modelId)
            }
            "switch_face_engine" -> {
                val engine = extractJsonField(json, "engine") ?: "mlkit"
                AgentCommand.SwitchFaceEngine(commandId = commandId, engine = engine)
            }
            "toggle_setting" -> {
                val key = extractJsonField(json, "key") ?: ""
                val enabled = extractJsonBoolean(json, "enabled") ?: true
                AgentCommand.ToggleSetting(commandId = commandId, settingKey = key, enabled = enabled)
            }

            // ===== 默认文本回复 =====
            else -> {
                val message = extractJsonField(json, "message")
                    ?: fallbackText.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                AgentCommand.TextReply(commandId = commandId, message = message)
            }
        }
    }

    /**
     * 兜底提取：从原始响应中直接提取 JSON 对象。
     *
     * 当 think 标签清理导致内容丢失时使用。
     * 扫描原始文本中第一个 `{...}` 结构，且中间包含 "method"。
     */
    private fun tryExtractJsonFromRaw(raw: String): String? {
        // 策略：找到第一个 `{` 和最后一个 `}`，且中间包含 "method"
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val candidate = raw.substring(jsonStart, jsonEnd + 1)
            if (candidate.contains("\"method\"")) {
                return candidate
            }
        }
        return null
    }

    /**
     * 从 JSON 字符串中提取指定 key 的对象值
     *
     * 用于提取 params 对象。
     */
    private fun extractJsonObject(json: String, key: String): String? {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex == -1) return null

        val colonIndex = json.indexOf(':', keyIndex)
        if (colonIndex == -1) return null

        var braceStart = colonIndex + 1
        while (braceStart < json.length && json[braceStart].isWhitespace()) braceStart++

        if (braceStart >= json.length || json[braceStart] != '{') return null

        var depth = 1
        var pos = braceStart + 1
        while (pos < json.length && depth > 0) {
            when (json[pos]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    // 跳过字符串
                    pos++
                    while (pos < json.length && json[pos] != '"') {
                        if (json[pos] == '\\' && pos + 1 < json.length) pos++
                        pos++
                    }
                }
            }
            pos++
        }

        return if (depth == 0) json.substring(braceStart, pos) else null
    }

    /**
     * 将 params 对象的内容合并到顶层 JSON 中
     *
     * 参数在 params 对象内，但现有解析逻辑期望扁平字段。
     * 此方法将 params 的键值对提升到顶层。
     */
    private fun mergeParamsIntoJson(originalJson: String, paramsJson: String): String {
        // 简单策略：将 originalJson 中 "params":{...} 替换为空，
        // 然后将 paramsJson 的内容（去掉外层 {}）追加到顶层
        val paramsContent = paramsJson.removePrefix("{").removeSuffix("}")
        if (paramsContent.isBlank()) return originalJson

        // 移除 originalJson 中的 params 字段
        val paramsPattern = """"params"\s*:\s*\{[^{}]*\}""".toRegex()
        val withoutParams = originalJson.replace(paramsPattern, "").trim()

        // 在最后一个 } 前插入 params 内容
        val lastBrace = withoutParams.lastIndexOf('}')
        return if (lastBrace > 0) {
            val prefix = withoutParams.substring(0, lastBrace).trimEnd()
            val separator = if (prefix.endsWith(',')) "" else ","
            "$prefix$separator$paramsContent}"
        } else {
            originalJson
        }
    }

    /**
     * 关键词兜底解析：当 LLM 不输出 JSON 时，通过关键词理解用户意图。
     *
     * 适用于小模型（如 Qwen3-0.6B）无法严格遵循 system prompt 指令的场景。
     */
    private fun tryParseByKeywords(text: String): AgentCommand? {
        val lower = text.lowercase()
        return when {
            // 拍照相关
            lower.contains("拍照") || lower.contains("拍张") || lower.contains("拍照片") ||
                lower.contains("拍一下") || lower.contains("快门") -> AgentCommand.CapturePhoto()
            // 翻转摄像头
            lower.contains("翻转") || lower.contains("切换摄像头") || lower.contains("前后") ||
                lower.contains("前置") || lower.contains("后置") || lower.contains("前摄") -> AgentCommand.FlipCamera()
            // 美颜相关
            lower.contains("调高美颜") || lower.contains("增强美颜") || lower.contains("提亮美颜") ->
                AgentCommand.AdjustBeauty(
                    settings = BeautySettings(enabled = true, smoothing = 65f, whitening = 65f)
                )
            lower.contains("美颜") && (lower.contains("关") || lower.contains("关闭")) ->
                AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = false))
            lower.contains("美颜") || lower.contains("开美颜") ->
                AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true))
            // 冷调滤镜
            lower.contains("冷调") || lower.contains("冷色") || lower.contains("冷滤镜") ||
                (lower.contains("冷") && lower.contains("滤镜")) ->
                AgentCommand.SwitchFilter(filterType = FilterType.COOL)
            // 暖调滤镜
            lower.contains("暖调") || lower.contains("暖色") || lower.contains("暖滤镜") ||
                (lower.contains("暖") && lower.contains("滤镜")) ->
                AgentCommand.SwitchFilter(filterType = FilterType.WARM)
            // 录像
            lower.contains("录像") || lower.contains("录制") || lower.contains("拍视频") -> AgentCommand.ToggleRecording()
            // 导航相关 - 去相册（最高优先级，多种说法）
            lower.contains("去相册") || lower.contains("打开相册") || lower.contains("看照片") ||
                lower.contains("图库") || lower.contains("照片库") ||
                (lower.contains("相册") && (lower.contains("去") || lower.contains("打开") || lower.contains("看"))) ->
                AgentCommand.NavigateTo(destination = "gallery")
            // 导航相关 - 去设置
            lower.contains("去设置") || lower.contains("打开设置") || lower.contains("设置页") ||
                lower.contains("app设置") || lower.contains("应用设置") ||
                (lower.contains("设置") && (lower.contains("去") || lower.contains("打开"))) ->
                AgentCommand.NavigateTo(destination = "settings")
            // 导航相关 - 去相机
            lower.contains("去相机") || lower.contains("回相机") || lower.contains("打开相机") ||
                lower.contains("回拍照") || lower.contains("去拍照") ||
                (lower.contains("相机") && (lower.contains("去") || lower.contains("打开") || lower.contains("回"))) ->
                AgentCommand.NavigateTo(destination = "camera")
            // 导航相关 - 去调试
            lower.contains("去调试") || lower.contains("打开调试") || lower.contains("debug") ->
                AgentCommand.NavigateTo(destination = "debug")
            // 导航相关 - 返回
            lower.contains("返回") || lower.contains("回去") || lower.contains("上一页") ||
                lower.contains("后退") || lower.contains("回退") ->
                AgentCommand.GoBack()
            // 复合指令：延迟 + 滤镜/美颜 + 拍照
            // 例如："5秒后换暖色滤镜拍照" -> [Delay, SwitchFilter(WARM), CapturePhoto]
            (lower.contains("延迟") || lower.contains("延时") || lower.contains("倒计时") ||
                lower.contains("几秒后") || lower.contains("秒后") || lower.contains("稍后")) &&
                (lower.contains("拍") || lower.contains("照")) &&
                (lower.contains("滤镜") || lower.contains("美颜") || lower.contains("磨皮") ||
                    lower.contains("美白") || lower.contains("瘦脸") || lower.contains("风格")) -> {
                val delaySeconds = extractDelaySeconds(lower)
                val commands = mutableListOf<AgentCommand>(
                    AgentCommand.Delay(delayMs = delaySeconds * 1000L)
                )
                // 识别滤镜
                when {
                    lower.contains("暖") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.WARM))
                    lower.contains("冷") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.COOL))
                    lower.contains("徕卡") && lower.contains("经典") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.LEICA_CLASSIC))
                    lower.contains("徕卡") && (lower.contains("鲜艳") || lower.contains(" vibrant")) -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.LEICA_VIBRANT))
                    lower.contains("徕卡") && (lower.contains("黑白") || lower.contains("bw")) -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.LEICA_BW))
                    lower.contains("胶片") && lower.contains("金") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.FILM_GOLD))
                    lower.contains("胶片") && lower.contains("富士") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.FILM_FUJI))
                    lower.contains("复古") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.VINTAGE))
                }
                commands.add(AgentCommand.CapturePhoto())
                AgentCommand.BatchExecute(commands = commands)
            }
            // 纯延迟拍摄（关键词兜底：解析为 BatchExecute([Delay, CapturePhoto])）
            (lower.contains("延迟") || lower.contains("延时") || lower.contains("倒计时") ||
                lower.contains("几秒后") || lower.contains("秒后") || lower.contains("稍后")) &&
                (lower.contains("拍") || lower.contains("照")) -> {
                val delaySeconds = extractDelaySeconds(lower)
                AgentCommand.BatchExecute(
                    commands = listOf(
                        AgentCommand.Delay(delayMs = delaySeconds * 1000L),
                        AgentCommand.CapturePhoto()
                    )
                )
            }
            // Gallery 相关
            lower.contains("删除") || lower.contains("删掉") ->
                AgentCommand.DeleteMedia(mediaIds = emptyList())
            lower.contains("分享") ->
                AgentCommand.ShareMedia(mediaIds = emptyList())
            else -> null
        }
    }

    /**
     * 将 LLM 输出的 filter 名称解析为 FilterType
     *
     * 支持别名映射和模糊匹配，增强对小模型输出偏差的容错。
     * 例如 LLM 输出 LEICA_MONOCHROME/RETRO/VIVID 等旧名称或近似名称时也能正确映射。
     */
    private fun resolveFilterType(name: String): FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> FilterType.NONE
            "LEICA_CLASSIC" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME" -> FilterType.LEICA_BW
            "FILM_GOLD" -> FilterType.FILM_GOLD
            "FILM_FUJI" -> FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD" -> FilterType.VINTAGE
            "COOL", "COLD" -> FilterType.COOL
            "WARM" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    /**
     * 将 LLM 输出的 style 名称解析为 StyleFilter
     */
    private fun resolveStyleFilter(name: String): StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> StyleFilter.NONE
            "TOON", "CARTOON", "COMIC" -> StyleFilter.TOON
            "SKETCH" -> StyleFilter.SKETCH
            "POSTERIZE", "POSTER" -> StyleFilter.POSTERIZE
            "EMBOSS" -> StyleFilter.EMBOSS
            "CROSSHATCH", "CROSS_HATCH" -> StyleFilter.CROSSHATCH
            else -> runCatching { StyleFilter.valueOf(normalized) }.getOrDefault(StyleFilter.NONE)
        }
    }

    private fun extractJsonField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val regex = """"$key"\s*:\s*(-?\d+\.?\d*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJsonBoolean(json: String, key: String): Boolean? {
        val regex = """"$key"\s*:\s*(true|false)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    /**
     * 提取字符串列表
     * 支持格式: "key": ["item1", "item2"] 或 "key": "item1,item2"
     */
    private fun extractJsonStringList(json: String, key: String): List<String> {
        // 尝试解析数组格式
        val arrayRegex = """"$key"\s*:\s*\[([^\]]*)\]""".toRegex()
        val arrayMatch = arrayRegex.find(json)
        if (arrayMatch != null) {
            val content = arrayMatch.groupValues[1]
            return content.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        }

        // 尝试解析逗号分隔的字符串
        val stringValue = extractJsonField(json, key)
        if (stringValue != null) {
            return stringValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        return emptyList()
    }

    /**
     * 提取 Float 类型的 Map
     */
    /**
     * 从文本中提取延迟秒数
     *
     * 支持 "3秒后"、"5秒"、"十秒后" 等说法，默认 3 秒。
     */
    private fun extractDelaySeconds(text: String): Int {
        val numberMap = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        // 先尝试匹配阿拉伯数字
        val digitRegex = "(\\d+)\\s*[秒s]".toRegex()
        val digitMatch = digitRegex.find(text)
        if (digitMatch != null) {
            return digitMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 30) ?: 3
        }
        // 再尝试匹配中文数字
        for ((cn, num) in numberMap) {
            if (text.contains(cn + "秒") || text.contains(cn + "秒后")) {
                return num.coerceIn(1, 30)
            }
        }
        return 3
    }

    private fun extractJsonFloatMap(json: String, key: String): Map<String, Float> {
        val result = mutableMapOf<String, Float>()

        // 尝试找到 key 对应的值（对象格式）
        val objectRegex = """"$key"\s*:\s*\{([^}]*)\}""".toRegex()
        val objectMatch = objectRegex.find(json)

        if (objectMatch != null) {
            val content = objectMatch.groupValues[1]
            // 解析内部的 key: value 对
            val pairRegex = """"([^"]+)"\s*:\s*(-?\d+\.?\d*)""".toRegex()
            pairRegex.findAll(content).forEach { matchResult ->
                val paramKey = matchResult.groupValues[1]
                val paramValue = matchResult.groupValues[2].toFloatOrNull()
                if (paramValue != null) {
                    result[paramKey] = paramValue
                }
            }
        }

        return result
    }
}
