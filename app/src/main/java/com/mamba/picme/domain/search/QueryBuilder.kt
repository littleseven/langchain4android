package com.mamba.picme.domain.search

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 跨维度查询构建器
 *
 * 将 [StructuredFilter] 转换为跨标签/OCR/地点/时间/人脸等维度的并发 Room 查询，
 * 合并去重后交由 [SearchRanker] 排序。
 *
 * 这是 Agent 搜索桥接的核心：LLM 意图 → StructuredFilter → QueryBuilder → 排序结果。
 */
class QueryBuilder(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
    private val ocrWordDao: OcrWordDao,
    private val personDao: PersonDao,
    private val locationDao: LocationDao
) {
    companion object {
        private const val TAG = "PicMe:QueryBuilder"
    }

    private val ranker = SearchRanker()

    /**
     * 执行跨维度搜索。
     *
     * 策略：
     * 1. 为 filter 中每个非空维度生成子查询
     * 2. 子查询并发执行（coroutineScope async）
     * 3. 按 MediaEntity 合并结果，每个 entity 记录命中的维度名集合
     * 4. 应用时间范围过滤
     * 5. 排序返回
     */
    suspend fun search(filter: StructuredFilter): List<ScoredMedia> = coroutineScope {
        val deferredQueries = mutableListOf<kotlinx.coroutines.Deferred<List<MediaEntity>>>()

        // 标签维度搜索
        for (keyword in filter.keywords) {
            deferredQueries += async {
                tagDao.searchByExactTag(keyword).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "Tag exact match '$keyword': ${it.size}")
                }
            }
            deferredQueries += async {
                tagDao.searchByTagName(keyword).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "Tag fuzzy match '$keyword': ${it.size}")
                }
            }
        }

        // OCR 维度搜索
        for (keyword in filter.ocrKeywords) {
            val normalized = keyword.lowercase().trim()
            deferredQueries += async {
                ocrWordDao.searchByExactWord(normalized).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "OCR exact match '$normalized': ${it.size}")
                }
            }
            deferredQueries += async {
                ocrWordDao.searchByWordPrefix(normalized).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "OCR prefix match '$normalized': ${it.size}")
                }
            }
        }

        // 也搜索 legacy LIKE（兼容未迁移到倒排索引的数据）
        for (keyword in filter.keywords + filter.ocrKeywords) {
            deferredQueries += async { mediaDao.searchByOcrText(keyword) }
            deferredQueries += async { mediaDao.searchByLabel(keyword) }
        }

        // 地点维度搜索
        for (keyword in filter.locationKeywords) {
            deferredQueries += async {
                locationDao.searchByPlace(keyword).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "Location match '$keyword': ${it.size}")
                }
            }
            // Legacy
            deferredQueries += async { mediaDao.searchByLocation(keyword) }
        }

        // 人物维度搜索
        if (filter.personName != null) {
            deferredQueries += async { mediaDao.searchByFileName(filter.personName) }
        }

        // 人脸过滤
        if (filter.hasFaces == true) {
            deferredQueries += async { mediaDao.searchByHasFace() }
        }

        // 时间范围搜索
        if (filter.timeRange != null) {
            deferredQueries += async {
                mediaDao.searchByTimeRange(filter.timeRange.startMs, filter.timeRange.endMs)
            }
        }

        // 收敛所有结果
        val allResults = deferredQueries.map { deferred ->
            try {
                deferred.await()
            } catch (e: Exception) {
                Logger.w(TAG, "Query failed: ${e.message}")
                emptyList()
            }
        }.flatten()

        // 合并去重，记录每个 MediaEntity 的命中维度
        val merged = mutableMapOf<MediaEntity, MutableSet<String>>()
        for (entity in allResults) {
            merged.getOrPut(entity) { mutableSetOf() }.add("keyword_match")
        }

        // 如果设置了时间范围，额外过滤
        val filtered = if (filter.timeRange != null) {
            merged.filterKeys { entity ->
                entity.captureDate in filter.timeRange.startMs..filter.timeRange.endMs
            }
        } else merged

        Logger.i(TAG, "Query complete: ${allResults.size} raw, ${merged.size} unique, " +
            "${filtered.size} after time filter")

        ranker.rank(
            results = filtered.mapValues { it.value.toSet() },
            filter = filter
        )
    }

    /**
     * 简化搜索：单一关键词跨所有维度搜索
     */
    suspend fun searchSimple(query: String): List<ScoredMedia> {
        return search(
            StructuredFilter(
                keywords = listOf(query),
                ocrKeywords = listOf(query),
                locationKeywords = listOf(query)
            )
        )
    }
}
