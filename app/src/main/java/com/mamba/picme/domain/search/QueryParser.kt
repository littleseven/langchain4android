package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.model.TimeRange
import java.util.Calendar

/**
 * 自然语言查询解析器（Layer 1：无需 LLM 的规则匹配）
 *
 * 支持：
 * - 时间词解析（去年、上个月、夏天、春节等）
 * - 关键词提取（用于标签/OCR/地名搜索）
 * - 地点词检测（"北京"、"三里屯" 等中国城市名 → locationKeywords）
 * - 判断是否需要 LLM 解析复杂混合查询
 */
object QueryParser {

    /** 当前年份偏移（用于测试注入） */
    var currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    /** 当前月份偏移 */
    var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1

    /** 中国城市关键词（用于检测地点词） */
    private val CITY_KEYWORDS = setOf(
        "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "重庆",
        "武汉", "西安", "苏州", "天津", "长沙", "郑州", "东莞", "青岛",
        "沈阳", "宁波", "昆明", "大连", "厦门", "合肥", "佛山", "福州",
        "哈尔滨", "济南", "温州", "长春", "石家庄", "常州", "泉州", "南宁",
        "贵阳", "南昌", "太原", "烟台", "嘉兴", "南通", "金华", "珠海",
        "惠州", "徐州", "海口", "乌鲁木齐", "兰州", "呼和浩特", "银川", "西宁",
        "三里屯", "国贸", "陆家嘴", "外滩", "西湖", "故宫", "长城", "天安门"
    )

    /**
     * 解析查询，返回结构化过滤条件
     *
     * @return StructuredFilter 如果规则能完全解析；null 表示需要 LLM 协助
     */
    fun parse(query: String): StructuredFilter? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val timeRange = extractTimeRange(trimmed)
        val (contentKeywords, locationKeywords) = extractCategorizedKeywords(trimmed)

        // 只有时间条件且无复杂语义 → 规则匹配
        if (timeRange != null && contentKeywords.isEmpty() && locationKeywords.isEmpty()) {
            return StructuredFilter(
                timeRange = timeRange,
                keywords = emptyList(),
                locationKeywords = emptyList(),
                needsLlm = false
            )
        }

        // 纯关键词（无时间词）→ 规则匹配
        if (timeRange == null && (contentKeywords.isNotEmpty() || locationKeywords.isNotEmpty())) {
            return StructuredFilter(
                timeRange = null,
                keywords = contentKeywords,
                locationKeywords = locationKeywords,
                needsLlm = false
            )
        }

        // 有时间 + 有关键词 → 规则匹配
        if (timeRange != null && (contentKeywords.isNotEmpty() || locationKeywords.isNotEmpty())) {
            return StructuredFilter(
                timeRange = timeRange,
                keywords = contentKeywords,
                locationKeywords = locationKeywords,
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

    private fun monthStartMs(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun monthEndMs(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun extractTimeRange(query: String): TimeRange? {
        // "去年" → 上一年全年
        if (query.contains("去年")) {
            return TimeRange(
                startMs = monthStartMs(currentYear - 1, 0),
                endMs = monthEndMs(currentYear - 1, 11)
            )
        }

        // "今年" / "今年夏天"
        if (query.contains("今年")) {
            val startMonth = if (query.contains("夏天")) 5 else 0
            val endMonth = if (query.contains("夏天")) 7 else 11
            return TimeRange(
                startMs = monthStartMs(currentYear, startMonth),
                endMs = monthEndMs(currentYear, endMonth)
            )
        }

        // 独立 "夏天"（假设指今年夏天）
        if (query.contains("夏天")) {
            return TimeRange(
                startMs = monthStartMs(currentYear, 5),
                endMs = monthEndMs(currentYear, 7)
            )
        }

        // "春天"/"秋天"/"冬天"
        val seasonMap = mapOf("春天" to 2, "秋天" to 8, "冬天" to 11)
        for ((season, startMonth) in seasonMap) {
            if (query.contains(season)) {
                return TimeRange(
                    startMs = monthStartMs(currentYear, startMonth),
                    endMs = monthEndMs(currentYear, (startMonth + 2) % 12)
                )
            }
        }

        // "上个月"
        if (query.contains("上个月")) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -1)
            val month = cal.get(Calendar.MONTH)
            val year = cal.get(Calendar.YEAR)
            return TimeRange(
                startMs = monthStartMs(year, month),
                endMs = monthEndMs(year, month)
            )
        }

        // "昨天"/"今天"/"前天"
        val relativeDayMap = mapOf("前天" to -2, "昨天" to -1, "今天" to 0)
        for ((word, offset) in relativeDayMap) {
            if (query.contains(word)) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, offset)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                return TimeRange(startMs = start, endMs = cal.timeInMillis)
            }
        }

        return null
    }

    // ── 关键词提取（分类为内容词和地点词） ──────────────────

    /**
     * 从查询中提取关键词，区分内容词和地点词
     */
    fun extractCategorizedKeywords(query: String): Pair<List<String>, List<String>> {
        val allKeywords = extractKeywords(query)
        val locationWords = mutableListOf<String>()
        val contentWords = mutableListOf<String>()

        for (kw in allKeywords) {
            if (kw in CITY_KEYWORDS) {
                locationWords.add(kw)
            } else {
                contentWords.add(kw)
            }
        }
        return contentWords to locationWords
    }

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
            .map { kw -> kw.trim() }
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
