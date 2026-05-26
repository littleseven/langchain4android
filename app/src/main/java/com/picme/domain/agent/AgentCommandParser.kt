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

    private const val TAG = "PicMe:AgentCommandParser"

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
                    cleaned = cleaned.removeRange(thinkStart, thinkEnd + endTag.length).trim()
                } else {
                    break
                }
            }
            // 处理未闭合标签：不要简单截断，尝试从标签后提取 JSON
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
        }

        // 2. 移除 markdown 代码块标记
        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

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
                val filterType = runCatching { FilterType.valueOf(filterName) }.getOrDefault(FilterType.NONE)
                AgentCommand.SwitchFilter(filterType)
            }
            "switch_style" -> {
                val styleName = extractJsonField(json, "style") ?: "NONE"
                val styleFilter = runCatching { StyleFilter.valueOf(styleName) }.getOrDefault(StyleFilter.NONE)
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
            lower.contains("翻转") || lower.contains("切换摄像头") || lower.contains("前后") -> AgentCommand.FlipCamera
            // 录像
            lower.contains("录像") || lower.contains("录制") || lower.contains("拍视频") -> AgentCommand.ToggleRecording
            else -> null
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
}
