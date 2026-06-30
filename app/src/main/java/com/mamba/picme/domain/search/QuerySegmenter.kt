package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.TimeRange

/**
 * 查询分词分段器
 *
 * 把自然语言搜索查询切分为带类型的语义段。
 * 基于规则 + 词典实现，不引入外部模型。
 *
 * 词典优先级（高到低）：SCENE > LOCATION > OBJECT > ACTIVITY > OCR > PERSON
 * 当一个词同时出现在多个词典中时，按此优先级归类。
 */
class QuerySegmenter(
    private val locationVocab: Set<String> = SearchVocabulary.LOCATION,
    private val personVocab: Set<String> = SearchVocabulary.PERSON,
    private val sceneVocab: Set<String> = SearchVocabulary.SCENE,
    private val objectVocab: Set<String> = SearchVocabulary.OBJECT,
    private val ocrVocab: Set<String> = SearchVocabulary.OCR,
    private val activityVocab: Set<String> = SearchVocabulary.ACTIVITY,
    private val queryParser: QueryParser = QueryParser
) {

    companion object {
        private const val MAX_SEGMENT_LENGTH = 8

        private val STOP_WORDS = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "都", "一",
            "把", "一个", "上面", "下面", "可以", "这个", "那个", "拍", "找", "搜索",
            "显示", "查看", "包含", "给我", "帮我", "里", "中", "上", "下", "与", "及", "还有"
        )

        private val TIME_PATTERN = Regex(
            "^(\\d{4}年\\d{1,2}月|\\d{4}年|去年\\d{1,2}月|今年\\d{1,2}月|前年\\d{1,2}月|去年|今年|前年|上个月|本周|上周|今天|昨天|前天|春天|夏天|秋天|冬天)"
        )
    }

    /**
     * 把查询切分为语义段
     */
    fun segment(query: String): SegmentedQuery {
        val normalized = query.trim()
        if (normalized.isEmpty()) return SegmentedQuery(normalized, emptyList())

        val segments = mutableListOf<Segment>()
        var remaining = normalized

        while (remaining.isNotEmpty()) {
            val segment = findNextSegment(remaining)
            if (segment != null) {
                if (segment.type != SegmentType.UNKNOWN) {
                    segments.add(segment)
                }
                remaining = remaining.substring(segment.text.length).trimStart()
            } else {
                // 未匹配任何词典，按字前进
                val firstChar = remaining[0].toString()
                if (firstChar !in STOP_WORDS) {
                    segments.add(Segment(SegmentType.UNKNOWN, firstChar))
                }
                remaining = remaining.substring(1).trimStart()
            }
        }

        return SegmentedQuery(normalized, mergeConsecutiveUnknown(segments))
    }

    /**
     * 将分段结果转换为显式约束和内容检索条件
     */
    fun toFilters(segmented: SegmentedQuery): Pair<ExplicitFilter, ContentFilter> {
        val explicit = ExplicitFilter(
            timeRange = parseTimeRange(segmented),
            locationKeywords = segmented.explicitSegments
                .filter { it.type == SegmentType.LOCATION }
                .map { it.text },
            hasFaces = if (hasPersonSegment(segmented)) true else null,
            personKeywords = segmented.explicitSegments
                .filter { it.type == SegmentType.PERSON }
                .map { it.text }
        )

        val contentKeywords = mutableListOf<String>()
        val ocrKeywords = mutableListOf<String>()

        // 人物关键词同时作为内容关键词，支持在标签/OCR 中命中
        contentKeywords.addAll(explicit.personKeywords)

        for (segment in segmented.contentSegments) {
            when (segment.type) {
                SegmentType.OCR -> ocrKeywords.add(segment.text)
                else -> contentKeywords.add(segment.text)
            }
        }

        val content = ContentFilter(
            keywords = contentKeywords,
            ocrKeywords = ocrKeywords,
            semanticQuery = segmented.original
        )

        return explicit to content
    }

    private fun findNextSegment(query: String): Segment? {
        // 优先匹配时间词
        val timeMatch = TIME_PATTERN.find(query)
        if (timeMatch != null) {
            return Segment(SegmentType.TIME, timeMatch.value)
        }

        // 优先匹配最长词
        val maxLen = minOf(query.length, MAX_SEGMENT_LENGTH)
        for (len in maxLen downTo 1) {
            val sub = query.substring(0, len)
            when {
                sub in STOP_WORDS -> return Segment(SegmentType.UNKNOWN, sub)
                sub in sceneVocab -> return Segment(SegmentType.SCENE, sub)
                sub in locationVocab -> return Segment(SegmentType.LOCATION, sub)
                sub in personVocab -> return Segment(SegmentType.PERSON, sub)
                sub in objectVocab -> return Segment(SegmentType.OBJECT, sub)
                sub in activityVocab -> return Segment(SegmentType.ACTIVITY, sub)
                sub in ocrVocab -> return Segment(SegmentType.OCR, sub)
            }
        }
        return null
    }

    private fun parseTimeRange(segmented: SegmentedQuery): TimeRange? {
        val timeText = segmented.segments
            .filter { it.type == SegmentType.TIME }
            .joinToString("") { it.text }
        return if (timeText.isNotEmpty()) {
            queryParser.parseTimeRange(timeText)
        } else null
    }

    private fun hasPersonSegment(segmented: SegmentedQuery): Boolean {
        return segmented.segments.any { it.type == SegmentType.PERSON }
    }

    private fun mergeConsecutiveUnknown(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments
        val result = mutableListOf<Segment>()
        var current = segments[0]
        for (i in 1 until segments.size) {
            val next = segments[i]
            if (current.type == SegmentType.UNKNOWN && next.type == SegmentType.UNKNOWN) {
                current = current.copy(text = current.text + next.text)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
