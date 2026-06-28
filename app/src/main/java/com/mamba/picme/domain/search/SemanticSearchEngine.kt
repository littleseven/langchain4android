package com.mamba.picme.domain.search

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.tag.MobileClipEngine
import com.mamba.picme.domain.tag.MobileClipTokenizer
import com.mamba.picme.domain.tag.i18n.ChineseQueryTranslator

/**
 * MobileCLIP 语义搜索引擎
 *
 * 实现文本→图像、图像→图像的跨模态语义搜索：
 * 1. 文本编码：Tokenizer → tokenIds → MobileClipEngine.encodeText() → 512-dim embedding
 * 2. 图像编码：MobileClipEngine.encodeImage() → 512-dim embedding
 * 3. 相似度计算：余弦相似度 → Top-K 排序
 *
 * 与 MediaSearchEngine 协同工作，作为三层混合检索中的"语义召回"层。
 *
 * @param context Application Context（用于初始化 MobileClipEngine 和 MobileClipTokenizer）
 * @param mediaDao 媒体数据访问对象
 * @param mobileClipEngine MobileCLIP 编码引擎（外部注入，可复用已有实例）
 */
class SemanticSearchEngine(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val mobileClipEngine: MobileClipEngine? = null,
    private val queryTranslator: ChineseQueryTranslator? = null
) {
    companion object {
        private const val TAG = "SemanticSearchEngine"
        private const val EMBEDDING_DIM = 512
    }

    /** Tokenizer（延迟加载） */
    private val tokenizer: MobileClipTokenizer by lazy {
        MobileClipTokenizer(context)
    }

    /** 本地持有的 MobileClipEngine 实例（如果外部未注入） */
    private val engine: MobileClipEngine by lazy {
        mobileClipEngine ?: MobileClipEngine(context)
    }

    /** 中文查询翻译器（如果外部未注入） */
    private val translator: ChineseQueryTranslator by lazy {
        queryTranslator ?: ChineseQueryTranslator(context)
    }

    /** 引擎是否已初始化（含模型加载和 tokenizer 加载） */
    val isReady: Boolean
        get() = engine.isInitialized && tokenizer.isReady()

    /**
     * 初始化引擎（加载模型和 tokenizer）
     *
     * @param useGpu 是否尝试使用 GPU 加载 vision 模型
     * @return 是否成功
     */
    fun initialize(useGpu: Boolean = false): Boolean {
        // 1. 初始化 MobileClipEngine（加载 MNN 模型）
        if (!engine.isInitialized) {
            if (!engine.initialize(useGpu)) {
                Log.w(TAG, "Failed to initialize MobileClipEngine")
                return false
            }
        }

        // 2. 加载 tokenizer
        if (!tokenizer.isReady()) {
            if (!tokenizer.load()) {
                Log.w(TAG, "Failed to load tokenizer")
                return false
            }
        }

        Log.i(TAG, "SemanticSearchEngine initialized (vocab=${tokenizer.vocabSize()})")
        return true
    }

    /**
     * 文本→图像语义搜索
     *
     * 执行流程：
     * 1. Tokenizer 编码查询文本 → tokenIds
     * 2. MobileClipEngine.encodeText() → textEmbedding
     * 3. 从数据库获取候选集（可选结构化过滤）
     * 4. 计算余弦相似度并排序
     * 5. 返回 Top-K 结果
     *
     * @param query 用户输入的自然语言查询（如"温馨的家庭聚餐"）
     * @param filter 结构化过滤条件（时间/地点/人脸），用于先缩小候选集范围
     * @param topK 返回结果数量上限
     * @return 按语义相似度排序的带分媒体列表
     */
    suspend fun searchByText(
        query: String,
        filter: StructuredFilter? = null,
        topK: Int = 50
    ): List<SemanticScoredMedia> {
        if (!isReady && !initialize()) {
            Log.w(TAG, "Engine not ready, skipping semantic search")
            return emptyList()
        }

        if (query.isBlank()) return emptyList()

        // 1. 中文查询翻译（如果含中文）
        val translatedQuery = translator.translateForClip(query)

        // 2. 文本编码
        val textEmbedding = encodeTextQuery(translatedQuery) ?: run {
            Log.w(TAG, "Failed to encode text query: $translatedQuery")
            return emptyList()
        }

        // 2. 获取候选集
        val candidates = getCandidates(filter)
        if (candidates.isEmpty()) {
            Log.d(TAG, "No candidates found for semantic search")
            return emptyList()
        }

        // 3. 计算余弦相似度并排序
        val scoredResults = candidates
            .mapNotNull { entity ->
                val imageEmbedding = base64ToFloatArray(entity.semanticEmbedding) ?: return@mapNotNull null
                val similarity = engine.cosineSimilarity(textEmbedding, imageEmbedding)
                if (similarity.isNaN()) {
                    Log.w(TAG, "NaN similarity for mediaId=${entity.id}")
                    return@mapNotNull null
                }
                SemanticScoredMedia(entity.toDomain(), similarity)
            }
            .sortedByDescending { it.score }
            .take(topK)

        // 日志：展示召回结果详情
        if (scoredResults.isNotEmpty()) {
            val topResults = scoredResults.take(3).joinToString { "${it.media.fileName}=${String.format("%.3f", it.score)}" }
            Log.i(TAG, "Semantic recall: query='$query' -> '$translatedQuery', candidates=${candidates.size}, returned=${scoredResults.size}, top3=[$topResults]")
        } else {
            Log.w(TAG, "Semantic recall empty: query='$query' -> '$translatedQuery', candidates=${candidates.size}, no match above threshold")
        }

        return scoredResults
    }

    /**
     * 以图搜图
     *
     * @param bitmap 查询图像
     * @param filter 结构化过滤条件
     * @param topK 返回结果数量上限
     * @return 按语义相似度排序的带分媒体列表
     */
    suspend fun searchByImage(
        bitmap: Bitmap,
        filter: StructuredFilter? = null,
        topK: Int = 50
    ): List<SemanticScoredMedia> {
        if (!isReady && !initialize()) {
            Log.w(TAG, "Engine not ready, skipping image search")
            return emptyList()
        }

        // 1. 图像编码
        val imageEmbedding = engine.encodeImage(bitmap) ?: run {
            Log.w(TAG, "Failed to encode query image")
            return emptyList()
        }

        // 2. 获取候选集
        val candidates = getCandidates(filter)
        if (candidates.isEmpty()) return emptyList()

        // 3. 计算余弦相似度并排序
        return candidates
            .mapNotNull { entity ->
                val candidateEmbedding = base64ToFloatArray(entity.semanticEmbedding) ?: return@mapNotNull null
                val similarity = engine.cosineSimilarity(imageEmbedding, candidateEmbedding)
                SemanticScoredMedia(entity.toDomain(), similarity)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .also {
                Log.d(TAG, "Image search: candidates=${candidates.size}, results=${it.size}")
            }
    }

    /**
     * 编码查询文本为 embedding（供外部复用）
     */
    fun encodeTextQuery(query: String): FloatArray? {
        if (!isReady && !initialize()) return null

        // 1. Tokenizer → tokenIds
        val tokenIds = tokenizer.encode(query) ?: return null

        // 2. MobileClipEngine → textEmbedding
        return engine.encodeText(tokenIds)
    }

    /**
     * 获取候选集（支持结构化过滤先缩小范围）
     *
     * 策略：
     * - 如果有结构化过滤（时间/地点/人脸），先 SQL 过滤
     * - 否则返回全量有 semanticEmbedding 的照片
     */
    private suspend fun getCandidates(filter: StructuredFilter?): List<MediaEntity> {
        return if (filter != null) {
            // 优先使用结构化过滤缩小候选集
            // 注意：这里复用 MediaDao 的已有方法，组合过滤条件
            getFilteredCandidates(filter)
        } else {
            // 全量有 embedding 的照片
            mediaDao.getMediaWithSemanticEmbedding()
        }
    }

    /**
     * 根据结构化过滤条件获取候选集
     *
     * 当前实现：组合时间范围 + 人脸过滤，与语义搜索叠加
     */
    private suspend fun getFilteredCandidates(filter: StructuredFilter): List<MediaEntity> {
        val candidates = mutableListOf<MediaEntity>()

        // 时间范围过滤（最优先，通常能大幅缩小范围）
        val timeRange = filter.timeRange
        if (timeRange != null) {
            candidates.addAll(
                mediaDao.searchByTimeRange(timeRange.startMs, timeRange.endMs)
                    .filter { !it.semanticEmbedding.isNullOrBlank() }
            )
        }

        // 如果无时间过滤，获取全量有 embedding 的
        if (candidates.isEmpty()) {
            candidates.addAll(mediaDao.getMediaWithSemanticEmbedding())
        }

        // 人脸过滤（在已有候选集上筛选）
        if (filter.hasFaces == true) {
            candidates.retainAll { it.hasFace }
        }

        return candidates.distinctBy { it.id }
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        // 仅释放自己创建的 engine，外部注入的不释放
        if (mobileClipEngine == null) {
            engine.release()
        }
        // 仅释放自己创建的 translator
        if (queryTranslator == null) {
            translator.release()
        }
        Log.i(TAG, "SemanticSearchEngine released")
    }
}

/**
 * 带语义相似度分数的媒体结果（语义搜索专用）
 */
data class SemanticScoredMedia(
    val media: MediaAsset,
    val score: Float
)

/**
 * Base64 编码的 semanticEmbedding → FloatArray 反序列化
 *
 * 编码方案（小端序 float32）：
 * FloatArray → ByteArray（每 float 4 bytes，小端序）→ Base64.NO_WRAP
 */
private fun base64ToFloatArray(base64String: String?): FloatArray? {
    if (base64String.isNullOrBlank()) return null

    return try {
        val bytes = android.util.Base64.decode(base64String, android.util.Base64.NO_WRAP)
        if (bytes.size % 4 != 0) {
            Log.w("SemanticSearchEngine", "Invalid embedding size: ${bytes.size}")
            return null
        }

        val floatCount = bytes.size / 4
        FloatArray(floatCount) { i ->
            val b0 = bytes[i * 4].toInt() and 0xFF
            val b1 = bytes[i * 4 + 1].toInt() and 0xFF
            val b2 = bytes[i * 4 + 2].toInt() and 0xFF
            val b3 = bytes[i * 4 + 3].toInt() and 0xFF
            val bits = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            java.lang.Float.intBitsToFloat(bits)
        }
    } catch (e: Exception) {
        Log.w("SemanticSearchEngine", "Failed to decode embedding", e)
        null
    }
}

/**
 * MediaEntity → MediaAsset 转换（用于搜索结果）
 */
private fun MediaEntity.toDomain() =
    MediaAsset(
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
