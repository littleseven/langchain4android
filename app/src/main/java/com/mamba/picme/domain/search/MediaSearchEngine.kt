package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator
import org.json.JSONException

/**
 * 媒体搜索引擎
 *
 * 两层查询策略：
 * - Layer 1: QueryParser 规则匹配（快速、离线、免费）
 * - Layer 2: Agent LLM 解析复杂混合查询
 *
 * 搜索结果按匹配相关性排序：标签匹配 > OCR 匹配 > 地名匹配 > 文件名匹配
 *
 * 支持跨语言搜索：通过 [TagTranslator] 把用户输入的英文查询扩展为中文 canonical 词，
 * 从而命中已有中文 TAG，无需全量重生成。
 */
class MediaSearchEngine(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty())
) {
    /**
     * 执行搜索
     *
     * @param query 自然语言查询（如"猫""去年的照片""上海"）
     * @param llmSearch LLM 结构化查询回调（仅在规则无法匹配时调用）
     * @return 匹配的媒体列表
     */
    suspend fun search(
        query: String,
        llmSearch: (suspend (String) -> StructuredFilter?)? = null
    ): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), query)

        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        // Layer 1: 规则匹配
        val filter = QueryParser.parse(query, uiLang)
        if (filter != null && !filter.needsLlm) {
            val results = executeFilter(filter)
            return SearchResult(results, query)
        }

        // Layer 2: LLM 解析
        if (llmSearch != null) {
            val llmFilter = llmSearch(query)
            if (llmFilter != null) {
                val results = executeFilter(llmFilter)
                return SearchResult(results, query)
            }
        }

        // 回退：全字段模糊搜索（跨语言扩展查询词）
        val queryCandidates = tagTranslator.expandForSearch(query, uiLang)
        val results = queryCandidates
            .flatMap { mediaDao.searchAll(it) }
            .map { it.toDomain() }
            .toMutableList()

        // 人物关键词回退
        if (QueryParser.isPeopleSearch(query)) {
            results.addAll(mediaDao.searchByHasFace().map { it.toDomain() })
        }

        return SearchResult(results.distinct(), query)
    }

    /**
     * 执行结构化过滤（支持新 DAO 的多维度查询）
     */
    private suspend fun executeFilter(filter: StructuredFilter): List<MediaAsset> {
        val resultMap = mutableMapOf<Long, MediaAsset>()
        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        applyTimeRange(filter, resultMap)
        applyContentKeywords(filter.keywords, uiLang, resultMap)
        applyOcrKeywords(filter.ocrKeywords, resultMap)
        applyLocationKeywords(filter.locationKeywords, resultMap)
        applyFaceFilter(filter.hasFaces, resultMap)
        applyPeopleFallback(filter.keywords, resultMap)

        return resultMap.values.sortedByDescending { it.captureDate }
    }

    private suspend fun applyTimeRange(
        filter: StructuredFilter,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        val timeRange = filter.timeRange ?: return
        val timeResults = mediaDao.searchByTimeRange(timeRange.startMs, timeRange.endMs)
        timeResults.forEach { resultMap[it.id] = it.toDomain() }
    }

    /**
     * 内容关键词搜索（标签 + OCR + 文件名），带跨语言扩展。
     */
    private suspend fun applyContentKeywords(
        keywords: List<String>,
        uiLang: AppLanguage,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        for (keyword in keywords) {
            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                searchByCandidate(candidate, resultMap)
            }
        }
    }

    private suspend fun searchByCandidate(
        candidate: String,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        if (tagDao != null) {
            tagDao.searchByExactTag(candidate).forEach { resultMap[it.id] = it.toDomain() }
        }
        if (ocrWordDao != null) {
            ocrWordDao.searchByWordPrefix(candidate.lowercase()).forEach { resultMap[it.id] = it.toDomain() }
        }

        val labelResults = mediaDao.searchByLabel(candidate)
        val ocrResults = mediaDao.searchByOcrText(candidate)
        val nameResults = mediaDao.searchByFileName(candidate)
        (labelResults + ocrResults + nameResults).forEach { resultMap[it.id] = it.toDomain() }
    }

    private suspend fun applyOcrKeywords(
        ocrKeywords: List<String>,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        for (keyword in ocrKeywords) {
            if (ocrWordDao != null) {
                ocrWordDao.searchByExactWord(keyword.lowercase()).forEach { resultMap[it.id] = it.toDomain() }
            }
            mediaDao.searchByOcrText(keyword).forEach { resultMap[it.id] = it.toDomain() }
        }
    }

    private suspend fun applyLocationKeywords(
        locationKeywords: List<String>,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        for (keyword in locationKeywords) {
            if (locationDao != null) {
                locationDao.searchByPlace(keyword).forEach { resultMap[it.id] = it.toDomain() }
            }
            mediaDao.searchByLocation(keyword).forEach { resultMap[it.id] = it.toDomain() }
        }
    }

    private suspend fun applyFaceFilter(
        hasFaces: Boolean?,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        if (hasFaces != true) return
        mediaDao.searchByHasFace().forEach { resultMap[it.id] = it.toDomain() }
    }

    private suspend fun applyPeopleFallback(
        keywords: List<String>,
        resultMap: MutableMap<Long, MediaAsset>
    ) {
        if (keywords.none { it in PEOPLE_SEARCH_KEYWORDS }) return
        mediaDao.searchByHasFace().forEach { resultMap[it.id] = it.toDomain() }
    }

    /**
     * LLM 结构化查询模板
     */
    fun buildLlmSearchPrompt(query: String, lang: AppLanguage = AppLanguage.CHINESE): String {
        return if (lang == AppLanguage.ENGLISH) {
            buildEnglishLlmSearchPrompt(query)
        } else {
            buildChineseLlmSearchPrompt(query)
        }
    }

    private fun buildChineseLlmSearchPrompt(query: String): String {
        return """
你是一个图片搜索助手。请将用户的自然语言查询转换为结构化过滤条件。

用户查询："$query"

请以 JSON 格式返回过滤条件：
{
  "timeRange": {"startMs": 开始时间戳毫秒, "endMs": 结束时间戳毫秒} 或 null,
  "keywords": ["关键词1", "关键词2"],
  "ocrKeywords": ["OCR文字关键词"],
  "locationKeywords": ["地点关键词"],
  "hasFaces": true/false/null,
  "explanation": "解释你是如何理解这个查询的"
}

当前年份：${QueryParser.currentYear}，当前月份：${QueryParser.currentMonth}

注意：
- 时间词示例："去年"→${QueryParser.currentYear - 1}年，"夏天"→6-8月
- keywords 是场景/物体/标签关键词
- ocrKeywords 是图片中可能出现的文字
- locationKeywords 是地名（城市、区域）
- 只返回 JSON，不要其他文字
""".trimIndent()
    }

    private fun buildEnglishLlmSearchPrompt(query: String): String {
        return """
You are a photo search assistant. Convert the user's natural language query into a structured filter.

User query: "$query"

Return a JSON filter in this format:
{
  "timeRange": {"startMs": start timestamp in ms, "endMs": end timestamp in ms} or null,
  "keywords": ["keyword1", "keyword2"],
  "ocrKeywords": ["ocr text keywords"],
  "locationKeywords": ["place keywords"],
  "hasFaces": true/false/null,
  "explanation": "explain how you understood this query"
}

Current year: ${QueryParser.currentYear}, current month: ${QueryParser.currentMonth}

Notes:
- Time words example: "last year" → year ${QueryParser.currentYear - 1}, "summer" → June-August
- keywords are scene/object/tag keywords
- ocrKeywords are text that may appear in images
- locationKeywords are place names (city, district)
- Return only JSON, no other text
""".trimIndent()
    }

    /**
     * 解析 LLM 返回的结构化过滤条件
     */
    fun parseLlmResponse(llmResponse: String): StructuredFilter? {
        return try {
            val jsonStart = llmResponse.indexOf('{')
            val jsonEnd = llmResponse.lastIndexOf('}') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null

            val json = llmResponse.substring(jsonStart, jsonEnd)
            val obj = org.json.JSONObject(json)

            val timeObj = obj.optJSONObject("timeRange")
            val timeRange = if (timeObj != null) {
                com.mamba.picme.domain.model.TimeRange(
                    startMs = timeObj.optLong("startMs", 0),
                    endMs = timeObj.optLong("endMs", 0)
                )
            } else null

            val keywordsArr = obj.optJSONArray("keywords")
            val keywords = if (keywordsArr != null) {
                (0 until keywordsArr.length()).map { keywordsArr.getString(it) }
            } else emptyList()

            val ocrArr = obj.optJSONArray("ocrKeywords")
            val ocrKeywords = if (ocrArr != null) {
                (0 until ocrArr.length()).map { ocrArr.getString(it) }
            } else emptyList()

            val locArr = obj.optJSONArray("locationKeywords")
            val locationKeywords = if (locArr != null) {
                (0 until locArr.length()).map { locArr.getString(it) }
            } else emptyList()

            val hasFaces = if (obj.has("hasFaces")) obj.optBoolean("hasFaces") else null

            StructuredFilter(
                timeRange = timeRange,
                keywords = keywords,
                ocrKeywords = ocrKeywords,
                locationKeywords = locationKeywords,
                hasFaces = hasFaces,
                needsLlm = false
            )
        } catch (e: JSONException) {
            Logger.w(TAG, "Failed to parse LLM search response", e)
            null
        }
    }

    companion object {
        private const val TAG = "MediaSearchEngine"

        /** 人物语义搜索关键词 */
        val PEOPLE_SEARCH_KEYWORDS = setOf(
            "人", "人物", "人脸", "合照", "合影", "自拍", "头像",
            "people", "person", "face", "portrait", "selfie"
        )
    }
}

data class SearchResult(
    val media: List<MediaAsset>,
    val originalQuery: String,
    val resultCount: Int = media.size
)

/**
 * MediaEntity → MediaAsset 转换（精简版，用于搜索结果）
 */
private fun com.mamba.picme.data.model.MediaEntity.toDomain() =
    com.mamba.picme.agent.core.model.context.MediaAsset(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        source = source,
        labels = labels,
        ocrText = ocrText,
        latitude = latitude,
        longitude = longitude,
        locationName = locationName,
        indexedAt = indexedAt
    )
