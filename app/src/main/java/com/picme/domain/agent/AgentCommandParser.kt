package com.picme.domain.agent

import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.model.MediaType

/**
 * Agent 命令解析器
 *
 * 将 LLM 文本响应解析为结构化 [AgentCommand]。
 * 提取为独立 object 以便在纯 JVM 单元测试中直接调用，
 * 避免实例化 [AgentOrchestrator] 时触发 JNI/MNN 加载。
 */
object AgentCommandParser {

    private const val TAG = "AgentCommandParser"

    /**
     * 解析 LLM 响应为 AgentCommand
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
                cleaned = if (afterTag.contains("\"action\"")) {
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

        Logger.i(TAG, "Cleaned response: '$cleaned'")

        // 3. 检查是否包含 JSON action 字段
        val hasJsonAction = cleaned.contains("\"action\"")
        if (!hasJsonAction) {
            // 兜底 1：尝试从原始响应中直接提取 JSON（绕过 think 标签截断问题）
            val fallbackJson = tryExtractJsonFromRaw(response)
            if (fallbackJson != null) {
                Logger.i(TAG, "Fallback JSON extraction succeeded: '$fallbackJson'")
                cleaned = fallbackJson
            } else {
                // 兜底 2：关键词匹配（小模型不输出 JSON 时的最终防线）
                // 先尝试清理后的内容，再尝试原始响应（think 标签内可能包含关键词）
                val keywordCommand = tryParseByKeywords(cleaned)
                    ?: tryParseByKeywords(response)
                if (keywordCommand != null) {
                    Logger.i(TAG, "Keyword fallback matched: ${keywordCommand::class.simpleName}")
                    return keywordCommand
                }
                Logger.d(TAG, "No JSON action found, treating as free chat")
                return AgentCommand.TextReply(cleaned.ifBlank { "你好，我是小觅，有什么可以帮你的吗？" })
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
            val action = extractJsonField(json, "action") ?: "text_reply"
            parseCommandByAction(action, json, context, cleaned)
        } catch (exception: Exception) {
            Logger.w(TAG, "Failed to parse LLM response, fallback to text: $json", exception)
            AgentCommand.TextReply(cleaned.ifBlank { "收到你的消息了，但没理解具体意图，请再描述一下~" })
        }
    }

    /**
     * 根据 action 字段解析为具体命令
     *
     * @param action action 字段值
     * @param json 原始 JSON 字符串
     * @param context 当前 Agent 上下文
     * @param fallbackText 解析失败时的回退文本
     * @return 解析后的命令
     */
    fun parseCommandByAction(
        action: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        return when (action) {
            "adjust_beauty" -> {
                val smoothing = extractJsonFloat(json, "smoothing") ?: context.beautySettings.smoothing
                val whitening = extractJsonFloat(json, "whitening") ?: context.beautySettings.whitening
                val slimFace = extractJsonFloat(json, "slim_face") ?: context.beautySettings.slimFace
                val bigEyes = extractJsonFloat(json, "big_eyes") ?: context.beautySettings.bigEyes
                val lipColor = extractJsonFloat(json, "lip_color") ?: context.beautySettings.lipColor
                val blush = extractJsonFloat(json, "blush") ?: context.beautySettings.blush
                val eyebrow = extractJsonFloat(json, "eyebrow") ?: context.beautySettings.eyebrow
                AgentCommand.AdjustBeauty(
                    context.beautySettings.copy(
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
                AgentCommand.SwitchFilter(filterType)
            }
            "switch_style" -> {
                val styleName = extractJsonField(json, "style") ?: "NONE"
                val styleFilter = resolveStyleFilter(styleName)
                AgentCommand.SwitchStyle(styleFilter)
            }
            "switch_scene" -> {
                val scene = extractJsonField(json, "scene") ?: "none"
                AgentCommand.SwitchScene(scene)
            }
            "switch_ratio" -> {
                val ratio = extractJsonField(json, "ratio") ?: "full"
                AgentCommand.SwitchRatio(ratio)
            }
            "adjust_exposure" -> {
                val exposure = extractJsonInt(json, "exposure") ?: 0
                AgentCommand.AdjustExposure(exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = extractJsonFloat(json, "zoom") ?: 1f
                AgentCommand.AdjustZoom(zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera
            "capture", "photo" -> AgentCommand.CapturePhoto
            "toggle_recording" -> AgentCommand.ToggleRecording
            "switch_mode" -> {
                val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(mode)
            }

            // ===== Gallery 命令 =====
            "view_media" -> {
                val mediaId = extractJsonField(json, "media_id")
                AgentCommand.ViewMedia(mediaId)
            }
            "delete_media" -> {
                val mediaIds = extractJsonStringList(json, "media_ids")
                AgentCommand.DeleteMedia(mediaIds)
            }
            "share_media" -> {
                val mediaIds = extractJsonStringList(json, "media_ids")
                AgentCommand.ShareMedia(mediaIds)
            }
            "select_media" -> {
                val mediaId = extractJsonField(json, "media_id") ?: ""
                val selected = extractJsonBoolean(json, "selected") ?: true
                AgentCommand.SelectMedia(mediaId, selected)
            }
            "search_media" -> {
                val query = extractJsonField(json, "query") ?: ""
                AgentCommand.SearchMedia(query)
            }
            "switch_view_mode" -> {
                val mode = extractJsonField(json, "mode") ?: "grid"
                AgentCommand.SwitchViewMode(mode)
            }
            "favorite_media" -> {
                val mediaId = extractJsonField(json, "media_id") ?: ""
                val favorite = extractJsonBoolean(json, "favorite") ?: true
                AgentCommand.FavoriteMedia(mediaId, favorite)
            }

            // ===== 导航命令 =====
            "navigate_to" -> {
                val destination = extractJsonField(json, "destination") ?: ""
                AgentCommand.NavigateTo(destination)
            }
            "go_back" -> AgentCommand.GoBack

            // ===== 设置命令 =====
            "change_theme" -> {
                val theme = extractJsonField(json, "theme") ?: "system"
                AgentCommand.ChangeTheme(theme)
            }
            "change_language" -> {
                val language = extractJsonField(json, "language") ?: "zh"
                AgentCommand.ChangeLanguage(language)
            }
            "download_model" -> {
                val modelId = extractJsonField(json, "model_id") ?: ""
                AgentCommand.DownloadModel(modelId)
            }
            "switch_face_engine" -> {
                val engine = extractJsonField(json, "engine") ?: "mlkit"
                AgentCommand.SwitchFaceEngine(engine)
            }
            "toggle_setting" -> {
                val key = extractJsonField(json, "key") ?: ""
                val enabled = extractJsonBoolean(json, "enabled") ?: true
                AgentCommand.ToggleSetting(key, enabled)
            }

            // ===== 默认文本回复 =====
            else -> {
                val message = extractJsonField(json, "message")
                    ?: fallbackText.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                AgentCommand.TextReply(message)
            }
        }
    }

    /**
     * 兜底提取：从原始响应中直接提取 JSON 对象。
     *
     * 当 think 标签清理导致内容丢失时使用。
     * 扫描原始文本中第一个 `{...}` 结构，忽略 think 标签内容。
     */
    private fun tryExtractJsonFromRaw(raw: String): String? {
        // 策略：找到第一个 `{` 和最后一个 `}`，且中间包含 "action"
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val candidate = raw.substring(jsonStart, jsonEnd + 1)
            if (candidate.contains("\"action\"")) {
                return candidate
            }
        }
        return null
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
                lower.contains("拍一下") || lower.contains("快门") -> AgentCommand.CapturePhoto
            // 翻转摄像头
            lower.contains("翻转") || lower.contains("切换摄像头") || lower.contains("前后") ||
                lower.contains("前置") || lower.contains("后置") || lower.contains("前摄") -> AgentCommand.FlipCamera
            // 美颜相关
            lower.contains("调高美颜") || lower.contains("增强美颜") || lower.contains("提亮美颜") ->
                AgentCommand.AdjustBeauty(
                    com.picme.beauty.api.BeautySettings(enabled = true, smoothing = 65f, whitening = 65f)
                )
            lower.contains("美颜") && (lower.contains("关") || lower.contains("关闭")) ->
                AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = false))
            lower.contains("美颜") || lower.contains("开美颜") ->
                AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true))
            // 冷调滤镜
            lower.contains("冷调") || lower.contains("冷色") || lower.contains("冷滤镜") ||
                (lower.contains("冷") && lower.contains("滤镜")) ->
                AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.COOL)
            // 暖调滤镜
            lower.contains("暖调") || lower.contains("暖色") || lower.contains("暖滤镜") ||
                (lower.contains("暖") && lower.contains("滤镜")) ->
                AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.WARM)
            // 录像
            lower.contains("录像") || lower.contains("录制") || lower.contains("拍视频") -> AgentCommand.ToggleRecording
            // 导航相关 - 去相册（最高优先级，多种说法）
            lower.contains("去相册") || lower.contains("打开相册") || lower.contains("看照片") ||
                lower.contains("图库") || lower.contains("照片库") ||
                (lower.contains("相册") && (lower.contains("去") || lower.contains("打开") || lower.contains("看"))) ->
                AgentCommand.NavigateTo("gallery")
            // 导航相关 - 去设置
            lower.contains("去设置") || lower.contains("打开设置") || lower.contains("设置页") ||
                lower.contains("app设置") || lower.contains("应用设置") ||
                (lower.contains("设置") && (lower.contains("去") || lower.contains("打开"))) ->
                AgentCommand.NavigateTo("settings")
            // 导航相关 - 去相机
            lower.contains("去相机") || lower.contains("回相机") || lower.contains("打开相机") ||
                lower.contains("回拍照") || lower.contains("去拍照") ||
                (lower.contains("相机") && (lower.contains("去") || lower.contains("打开") || lower.contains("回"))) ->
                AgentCommand.NavigateTo("camera")
            // 导航相关 - 去调试
            lower.contains("去调试") || lower.contains("打开调试") || lower.contains("debug") ->
                AgentCommand.NavigateTo("debug")
            // 导航相关 - 返回
            lower.contains("返回") || lower.contains("回去") || lower.contains("上一页") ||
                lower.contains("后退") || lower.contains("回退") ->
                AgentCommand.GoBack
            // Gallery 相关
            lower.contains("删除") || lower.contains("删掉") ->
                AgentCommand.DeleteMedia(emptyList())
            lower.contains("分享") ->
                AgentCommand.ShareMedia(emptyList())
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
