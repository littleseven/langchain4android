package com.mamba.picme.domain.search

import java.util.Calendar

/**
 * 自然语言查询解析器（Layer 1：无需 LLM 的规则匹配）
 *
 * 支持：
 * - 时间词解析（去年、上个月、夏天、春节等）
 * - 关键词提取（用于标签/OCR/地名搜索）
 * - 判断是否需要 LLM 解析复杂混合查询
 */
object QueryParser {

    /** 当前年份偏移（用于测试注入） */
    var currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    /** 当前月份偏移 */
    var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1

    /**
     * 解析查询，返回结构化过滤条件
     *
     * @return StructuredFilter 如果规则能完全解析；null 表示需要 LLM 协助
     */
    fun parse(query: String): StructuredFilter? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val timeRange = extractTimeRange(trimmed)
        val keywords = extractKeywords(trimmed)

        // 只有时间条件且无复杂语义 → 规则匹配
        if (timeRange != null && keywords.joinToString("") == removeTimeWords(trimmed).trim()) {
            return StructuredFilter(
                timeRange = timeRange.toTimeRange(),
                keywords = emptyList(),
                originalQuery = trimmed,
                needsLlm = false
            )
        }

        // 纯关键词（无时间词）→ 规则匹配
        if (timeRange == null && keywords.isNotEmpty()) {
            return StructuredFilter(
                timeRange = null,
                keywords = keywords,
                originalQuery = trimmed,
                needsLlm = false
            )
        }

        // 有时间 + 有关键词 → 可能可以规则匹配，但仍返回完整 filter
        if (timeRange != null && keywords.isNotEmpty()) {
            return StructuredFilter(
                timeRange = timeRange.toTimeRange(),
                keywords = keywords,
                originalQuery = trimmed,
                needsLlm = false
            )
        }

        // 无法规则解析 → 需要 LLM
        return null
    }

    /**
     * 判断查询需要 LLM 解析
     */
    fun needsLlm(query: String): Boolean {
        return parse(query) == null
    }

    // ── 时间词解析 ──────────────────────────────────────────

    private data class ParsedTimeRange(val startMs: Long, val endMs: Long) {
        fun toTimeRange() = TimeRange(startMs, endMs)
    }

    private fun extractTimeRange(query: String): ParsedTimeRange? {
        // "去年" → 上一年全年
        if (query.contains("去年")) {
            val start = Calendar.getInstance().apply {
                set(Calendar.YEAR, currentYear - 1)
                set(Calendar.MONTH, 0)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = Calendar.getInstance().apply {
                set(Calendar.YEAR, currentYear - 1)
                set(Calendar.MONTH, 11)
                set(Calendar.DAY_OF_MONTH, 31)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            return ParsedTimeRange(start.timeInMillis, end.timeInMillis)
        }

        // "今年" / "今年夏天"
        if (query.contains("今年")) {
            val start = Calendar.getInstance().apply {
                set(Calendar.YEAR, currentYear)
                set(Calendar.MONTH, 0)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = Calendar.getInstance()
            if (query.contains("夏天")) {
                start.set(Calendar.MONTH, 5)  // 6月
                end.set(Calendar.MONTH, 7)     // 8月底
                end.set(Calendar.DAY_OF_MONTH, 31)
            }
            return ParsedTimeRange(start.timeInMillis, end.timeInMillis)
        }

        // 独立 "夏天"（假设指今年夏天）
        if (query.contains("夏天")) {
            val start = Calendar.getInstance().apply {
                set(Calendar.MONTH, 5)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = Calendar.getInstance().apply {
                set(Calendar.MONTH, 7)
                set(Calendar.DAY_OF_MONTH, 31)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            return ParsedTimeRange(start.timeInMillis, end.timeInMillis)
        }

        // "春天"/"秋天"/"冬天" (近似的 3 个月窗口)
        val seasonMap = mapOf(
            "春天" to 2,  // 3-5月
            "秋天" to 8,  // 9-11月
            "冬天" to 11  // 12-2月
        )
        for ((season, startMonth) in seasonMap) {
            if (query.contains(season)) {
                val start = Calendar.getInstance().apply {
                    set(Calendar.MONTH, startMonth)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val end = Calendar.getInstance().apply {
                    set(Calendar.MONTH, (startMonth + 2) % 12)
                    set(Calendar.DAY_OF_MONTH, if (startMonth + 2 > 11) 28 else 30)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                return ParsedTimeRange(start.timeInMillis, end.timeInMillis)
            }
        }

        // "上个月"
        if (query.contains("上个月")) {
            val start = Calendar.getInstance().apply {
                add(Calendar.MONTH, -1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.DAY_OF_MONTH, -1)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            return ParsedTimeRange(start.timeInMillis, end.timeInMillis)
        }

        // "上上周"/"上周"/"本周"/"昨天"/"今天"
        val relativeDayMap = mapOf(
            "今天" to 0,
            "昨天" to -1,
            "前天" to -2,
            "本周" to null,
            "上周" to null
        )
        for ((word, offset) in relativeDayMap) {
            if (query.contains(word)) {
                val cal = Calendar.getInstance()
                if (offset != null) {
                    cal.add(Calendar.DAY_OF_YEAR, offset)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    val start = cal.timeInMillis
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    return ParsedTimeRange(start, cal.timeInMillis)
                }
            }
        }

        return null
    }

    // ── 关键词提取 ──────────────────────────────────────────

    /**
     * 从查询中提取有实体意义的关键词（移除时间词、停用词）
     */
    fun extractKeywords(query: String): List<String> {
        var text = removeTimeWords(query)

        // 移除常见停用词
        val stopWords = listOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "都", "一", "把",
            "一个", "上面", "下面", "可以", "这个", "那个", "拍", "照片", "图片",
            "找", "搜索", "显示", "查看", "包含", "给我", "帮我"
        )
        for (word in stopWords) {
            text = text.replace(word, " ")
        }

        return text
            .split(Regex("[\\s，,。.!！？?]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length >= 1 }
            .distinct()
    }

    /**
     * 判断是否是"人物"相关搜索
     */
    fun isPeopleSearch(query: String): Boolean {
        val peopleKeywords = listOf(
            "人", "人物", "人脸", "合照", "合影", "people", "person",
            "face", "portrait", "selfie", "自拍", "头像"
        )
        return peopleKeywords.any { query.contains(it, ignoreCase = true) }
    }

    private fun removeTimeWords(query: String): String {
        val timeWords = listOf(
            "去年", "今年", "上个月", "本周", "上周", "今天", "昨天", "前天",
            "春天", "夏天", "秋天", "冬天"
        )
        var text = query
        for (word in timeWords) {
            text = text.replace(word, "")
        }
        return text.trim()
    }
}

/**
 * 结构化过滤条件
 */
data class StructuredFilter(
    val timeRange: TimeRange?,
    val keywords: List<String>,
    val originalQuery: String,
    val needsLlm: Boolean
)

data class TimeRange(
    val startMs: Long,
    val endMs: Long
)
