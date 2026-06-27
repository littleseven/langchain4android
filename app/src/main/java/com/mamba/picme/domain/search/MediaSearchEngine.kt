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
 * 三层混合检索策略：
 * - Layer 1: QueryParser 规则匹配（快速、离线、免费）
 * - Layer 2: Agent LLM 解析复杂混合查询
 * - Layer 2.5: MobileCLIP 语义召回（连续语义空间匹配）
 * - Layer 3: 融合排序（结构化分 + 标签分 + 语义分 + 时间衰减）
 *
 * 搜索结果按匹配相关性排序：语义相似度 > 标签匹配 > OCR 匹配 > 地名匹配 > 文件名匹配
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
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val semanticSearchEngine: SemanticSearchEngine? = null
) {
    /**
     * 执行搜索（三层混合检索）
     *
     * @param query 自然语言查询（如"猫""去年的照片""上海""温馨的家庭聚餐"）
     * @param llmSearch LLM 结构化查询回调（仅在规则无法匹配时调用）
     * @param enableSemanticSearch 是否启用 MobileCLIP 语义召回（默认 true）
     * @return 匹配的媒体列表
     */
    suspend fun search(
        query: String,
        llmSearch: (suspend (String) -> StructuredFilter?)? = null,
        enableSemanticSearch: Boolean = true
    ): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), query)

        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        // Layer 1: 规则匹配
        val filter = QueryParser.parse(query, uiLang)
        if (filter != null && !filter.needsLlm) {
            val results = executeFilter(filter)

            // Layer 2.5: 语义召回增强（如果规则匹配结果较少，补充语义结果）
            val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                searchSemantic(query, filter, results.size)
            } else emptyList()

            // Layer 3: 融合排序
            val merged = mergeAndRank(results, semanticResults, filter)
            return SearchResult(merged, query)
        }

        // Layer 2: LLM 解析
        if (llmSearch != null) {
            val llmFilter = llmSearch(query)
            if (llmFilter != null) {
                val results = executeFilter(llmFilter)

                // 语义召回增强
                val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
                    searchSemantic(query, llmFilter, results.size)
                } else emptyList()

                val merged = mergeAndRank(results, semanticResults, llmFilter)
                return SearchResult(merged, query)
            }
        }

        // 回退：全字段模糊搜索 + 语义召回
        val queryCandidates = tagTranslator.expandForSearch(query, uiLang)
        val sqlResults = queryCandidates
            .flatMap { mediaDao.searchAll(it) }
            .map { it.toDomain() }
            .toMutableList()

        // 人物关键词回退
        if (QueryParser.isPeopleSearch(query)) {
            sqlResults.addAll(mediaDao.searchByHasFace().map { it.toDomain() })
        }

        // 语义召回（无结构化过滤时全量语义搜索）
        val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
            searchSemantic(query, null, sqlResults.size)
        } else emptyList()

        val merged = mergeAndRank(sqlResults.distinct(), semanticResults, null)
        return SearchResult(merged, query)
    }

    /**
     * 执行 MobileCLIP 语义召回
     *
     * @param query 用户原始查询
     * @param filter 结构化过滤条件（用于缩小候选集）
     * @param sqlResultCount 已有 SQL 结果数量（用于判断是否补充语义结果）
     * @return 语义搜索结果
     */
    private suspend fun searchSemantic(
        query: String,
        filter: StructuredFilter?,
        sqlResultCount: Int
    ): List<SemanticScoredMedia> {
        // 如果 SQL 结果已足够多（> 50），语义召回优先级降低
        // 但仍执行语义搜索，用于融合排序中的语义分
        return try {
            semanticSearchEngine?.searchByText(
                query = query,
                filter = filter,
                topK = 50
            ) ?: emptyList()
        } catch (e: Exception) {
            Logger.w(TAG, "Semantic search failed", e)
            emptyList()
        }
    }

    /**
     * Layer 3: 融合排序
     *
     * 将 SQL 搜索结果和语义搜索结果合并，按综合分数排序：
     * - 结构化匹配分：时间/地点/人脸命中 boost（0~1.0）
     * - 标签匹配分：标签命中 boost（0~0.8）
     * - 语义相似度分：CLIP 余弦相似度（0~1.0）
     * - 时间衰减：新照片 boost（0~0.3）
     *
     * 综合分 = 结构化分 * 0.3 + 标签分 * 0.2 + 语义分 * 0.4 + 时间衰减 * 0.1
     */
    private fun mergeAndRank(
        sqlResults: List<MediaAsset>,
        semanticResults: List<SemanticScoredMedia>,
        filter: StructuredFilter?
    ): List<MediaAsset> {
        // 构建 ID → 综合分数映射
        val scoreMap = mutableMapOf<Long, Float>()
        val mediaMap = mutableMapOf<Long, MediaAsset>()

        // SQL 结果赋予基础分（标签匹配分）
        sqlResults.forEachIndexed { index, media ->
            mediaMap[media.id] = media
            // 基础分：按排序位置衰减（越靠前分数越高）
            val baseScore = 1.0f - (index.toFloat() / (sqlResults.size + 1))
            scoreMap[media.id] = baseScore * 0.5f // 标签匹配权重 0.5
        }

        // 语义结果赋予语义相似度分
        semanticResults.forEach { scored ->
            mediaMap[scored.media.id] = scored.media
            val existingScore = scoreMap.getOrDefault(scored.media.id, 0f)
            // 语义相似度权重 0.4，与已有分数叠加
            scoreMap[scored.media.id] = existingScore + scored.score * 0.4f
        }

        // 时间衰减 boost（新照片加分）
        val now = System.currentTimeMillis()
        scoreMap.keys.forEach { id ->
            val media = mediaMap[id] ?: return@forEach
            val daysSinceCapture = (now - media.captureDate) / (1000 * 60 * 60 * 24)
            val timeBoost = when {
                daysSinceCapture < 30 -> 0.3f
                daysSinceCapture < 365 -> 0.15f
                else -> 0f
            }
            scoreMap[id] = scoreMap.getOrDefault(id, 0f) + timeBoost * 0.1f
        }

        // 按综合分数降序排序
        return scoreMap.entries
            .sortedByDescending { it.value }
            .mapNotNull { mediaMap[it.key] }
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
