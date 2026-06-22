package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.domain.model.StructuredFilter

/**
 * 媒体搜索引擎
 *
 * 两层查询策略：
 * - Layer 1: QueryParser 规则匹配（快速、离线、免费）
 * - Layer 2: Agent LLM 解析复杂混合查询
 *
 * 搜索结果按匹配相关性排序：标签匹配 > OCR 匹配 > 地名匹配 > 文件名匹配
 */
class MediaSearchEngine(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null
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

        // Layer 1: 规则匹配
        val filter = QueryParser.parse(query)
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

        // 回退：全字段模糊搜索
        val results = mediaDao.searchAll(query).map { it.toDomain() }.toMutableList()

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

        // 时间过滤
        if (filter.timeRange != null) {
            val timeResults = mediaDao.searchByTimeRange(
                filter.timeRange.startMs,
                filter.timeRange.endMs
            )
            for (entity in timeResults) {
                resultMap[entity.id] = entity.toDomain()
            }
        }

        // 内容关键词搜索（标签 + OCR + 文件名）
        for (keyword in filter.keywords) {
            // 新 DAO 路径（优先）
            if (tagDao != null) {
                val tagResults = tagDao.searchByExactTag(keyword)
                for (entity in tagResults) { resultMap[entity.id] = entity.toDomain() }
            }
            if (ocrWordDao != null) {
                val wordResults = ocrWordDao.searchByWordPrefix(keyword.lowercase())
                for (entity in wordResults) { resultMap[entity.id] = entity.toDomain() }
            }

            // Legacy LIKE 路径（兼容）
            val labelResults = mediaDao.searchByLabel(keyword)
            val ocrResults = mediaDao.searchByOcrText(keyword)
            val nameResults = mediaDao.searchByFileName(keyword)
            for (entity in labelResults + ocrResults + nameResults) {
                resultMap[entity.id] = entity.toDomain()
            }
        }

        // OCR 关键词（来自 LLM 的独立 OCR 维度）
        for (keyword in filter.ocrKeywords) {
            if (ocrWordDao != null) {
                val wordResults = ocrWordDao.searchByExactWord(keyword.lowercase())
                for (entity in wordResults) { resultMap[entity.id] = entity.toDomain() }
            }
            val ocrResults = mediaDao.searchByOcrText(keyword)
            for (entity in ocrResults) { resultMap[entity.id] = entity.toDomain() }
        }

        // 地点关键词搜索
        for (keyword in filter.locationKeywords) {
            if (locationDao != null) {
                val locResults = locationDao.searchByPlace(keyword)
                for (entity in locResults) { resultMap[entity.id] = entity.toDomain() }
            }
            val locResults = mediaDao.searchByLocation(keyword)
            for (entity in locResults) { resultMap[entity.id] = entity.toDomain() }
        }

        // 人脸过滤
        if (filter.hasFaces == true) {
            val faceResults = mediaDao.searchByHasFace()
            for (entity in faceResults) { resultMap[entity.id] = entity.toDomain() }
        }

        // 人物关键词回退
        if (filter.keywords.any { it in PEOPLE_SEARCH_KEYWORDS }) {
            val faceResults = mediaDao.searchByHasFace()
            for (entity in faceResults) { resultMap[entity.id] = entity.toDomain() }
        }

        return resultMap.values.sortedByDescending { it.captureDate }
    }

    /**
     * LLM 结构化查询模板
     */
    fun buildLlmSearchPrompt(query: String): String {
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
        } catch (e: Exception) {
            null
        }
    }

    companion object {
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
