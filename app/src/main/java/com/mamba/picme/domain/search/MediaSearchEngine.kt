package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.domain.model.AiAgentCommand

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
    private val mediaDao: MediaDao
) {

    companion object {
        /** 人物语义搜索关键词 */
        val PEOPLE_SEARCH_KEYWORDS = setOf(
            "人", "人物", "人脸", "合照", "合影", "自拍", "头像",
            "people", "person", "face", "portrait", "selfie"
        )
    }

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

        // 人物关键词回退：加入有人脸的照片
        if (QueryParser.isPeopleSearch(query)) {
            results.addAll(mediaDao.searchByHasFace().map { it.toDomain() })
        }

        return SearchResult(results.distinct(), query)
    }

    /**
     * 执行结构化过滤
     */
    private suspend fun executeFilter(filter: StructuredFilter): List<MediaAsset> {
        val resultSet = mutableSetOf<MediaAsset>()

        // 时间过滤
        if (filter.timeRange != null) {
            val timeResults = mediaDao.searchByTimeRange(
                filter.timeRange.startMs,
                filter.timeRange.endMs
            )
            resultSet.addAll(timeResults.map { it.toDomain() })
            // 如果时间匹配结果为空，提前返回
            if (resultSet.isEmpty() && filter.keywords.isEmpty()) return emptyList()
        }

        // 关键词搜索（标签 + OCR + 地名 + 文件名）
        if (filter.keywords.isNotEmpty()) {
            // 检查是否包含人物语义关键词
            val hasPeopleKeyword = filter.keywords.any { it in PEOPLE_SEARCH_KEYWORDS }

            for (keyword in filter.keywords) {
                val labelResults = mediaDao.searchByLabel(keyword)
                val ocrResults = mediaDao.searchByOcrText(keyword)
                val locationResults = mediaDao.searchByLocation(keyword)
                val nameResults = mediaDao.searchByFileName(keyword)

                resultSet.addAll(labelResults.map { it.toDomain() })
                resultSet.addAll(ocrResults.map { it.toDomain() })
                resultSet.addAll(locationResults.map { it.toDomain() })
                resultSet.addAll(nameResults.map { it.toDomain() })
            }

            // 人物语义关键词：额外返回所有有人脸的照片
            if (hasPeopleKeyword) {
                resultSet.addAll(mediaDao.searchByHasFace().map { it.toDomain() })
            }
        }

        // 如果只有关键词没有时间范围 → 纯关键词匹配
        if (filter.timeRange == null) {
            return resultSet
                .sortedByDescending { it.captureDate }
        }

        // 时间 + 关键词组合：取交集（都有结果时）或关键词优先
        val keywordResults = resultSet
            .sortedByDescending { it.captureDate }

        return keywordResults
    }

    /**
     * LLM 结构化查询模板
     *
     * 发送给 LLM 的 prompt，要求将 NL 转为结构化过滤条件
     */
    fun buildLlmSearchPrompt(query: String): String {
        return """
你是一个图片搜索助手。请将用户的自然语言查询转换为结构化过滤条件。

用户查询："$query"

请以 JSON 格式返回过滤条件：
{
  "timeRange": {"startMs": 开始时间戳毫秒, "endMs": 结束时间戳毫秒} 或 null,
  "keywords": ["关键词1", "关键词2"],
  "explanation": "解释你是如何理解这个查询的"
}

当前年份：${QueryParser.currentYear}，当前月份：${QueryParser.currentMonth}

注意：
- 时间词示例："去年"→${QueryParser.currentYear - 1}年，"夏天"→6-8月
- 关键词应为具体的物体、地点、人物等
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
                TimeRange(
                    startMs = timeObj.optLong("startMs", 0),
                    endMs = timeObj.optLong("endMs", 0)
                )
            } else null

            val keywordsArr = obj.optJSONArray("keywords")
            val keywords = if (keywordsArr != null) {
                (0 until keywordsArr.length()).map { keywordsArr.getString(it) }
            } else emptyList()

            StructuredFilter(
                timeRange = timeRange,
                keywords = keywords,
                originalQuery = llmResponse,
                needsLlm = false
            )
        } catch (e: Exception) {
            null
        }
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
