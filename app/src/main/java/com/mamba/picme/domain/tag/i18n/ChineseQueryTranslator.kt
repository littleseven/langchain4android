package com.mamba.picme.domain.tag.i18n

import android.content.Context
import android.util.Log
import com.mamba.picme.domain.model.AppLanguage

/**
 * 中文查询翻译器（专为 MobileCLIP 语义搜索优化）
 *
 * 采用分层翻译策略：
 * 1. **词表精确匹配**（零耗时）：通过 BilingualVocab 查找高频词
 * 2. **OPUS-MT 模型翻译**（~50ms）：词表未命中时，调用轻量 NMT 模型
 * 3. **保留原词兜底**：模型不可用时返回原查询
 *
 * 设计目标：让中文用户搜索 "公园里的猫" 时，MobileCLIP 能收到 "cat in park"
 * 从而正确匹配到语义相近的图像。
 *
 * @param context Application Context
 * @param vocab 双语词表（用于高频词快速匹配）
 * @param translator OPUS-MT 翻译引擎（可选注入）
 */
class ChineseQueryTranslator(
    private val context: Context,
    private val vocab: BilingualVocab = BilingualVocab.empty(),
    private val translator: OpusMtTranslator? = null
) {
    companion object {
        private const val TAG = "ChineseQueryTranslator"

        /** 中文 Unicode 范围检测正则 */
        private val CHINESE_REGEX = Regex("[\\u4e00-\\u9fff]")

        /** 常见中文停用词（搜索时无需翻译） */
        private val STOP_WORDS = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "那"
        )
    }

    /** 翻译引擎（延迟初始化） */
    private val lazyTranslator: OpusMtTranslator by lazy {
        translator ?: OpusMtTranslator(context)
    }

    /**
     * 将用户查询转换为 MobileCLIP 友好的英文查询
     *
     * 策略：
     * 1. 纯英文查询 → 直接返回（无需翻译）
     * 2. 含中文查询 → 先分词，逐词查词表，未命中走模型翻译
     * 3. 混合查询 → 翻译中文部分，保留英文部分
     *
     * @param query 用户原始查询
     * @return 翻译后的英文查询（或原查询）
     */
    fun translateForClip(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return trimmed

        // 1. 纯英文查询直接返回
        if (!containsChinese(trimmed)) {
            Log.d(TAG, "Query is English, no translation needed: $trimmed")
            return trimmed
        }

        // 2. 尝试词表精确匹配（零耗时）
        val vocabResult = tryVocabLookup(trimmed)
        if (vocabResult != null) {
            Log.d(TAG, "Vocab hit: '$trimmed' -> '$vocabResult'")
            return vocabResult
        }

        // 3. 尝试 OPUS-MT 模型翻译
        val modelResult = tryModelTranslate(trimmed)
        if (modelResult != null) {
            Log.d(TAG, "Model translated: '$trimmed' -> '$modelResult'")
            return modelResult
        }

        // 4. 兜底：保留原查询（MobileCLIP 对部分中文有弱泛化）
        Log.d(TAG, "Translation unavailable, returning original: $trimmed")
        return trimmed
    }

    /**
     * 批量翻译（用于扩展多个候选词）
     */
    fun translateBatch(queries: List<String>): List<String> =
        queries.map { translateForClip(it) }

    /**
     * 检查文本是否包含中文字符
     */
    fun containsChinese(text: String): Boolean =
        CHINESE_REGEX.containsMatchIn(text)

    /**
     * 尝试词表查找
     *
     * 支持两种模式：
     * 1. 整句精确匹配（如 "公园" → "park"）
     * 2. 分词后逐词匹配（如 "公园 猫" → "park cat"）
     */
    private fun tryVocabLookup(query: String): String? {
        // 1. 整句精确匹配
        vocab.zhToEn[query]?.let { return it }

        // 2. 分词后逐词匹配
        val words = segmentQuery(query)
        val translatedWords = words.map { word ->
            when {
                word in STOP_WORDS -> null  // 跳过停用词
                !containsChinese(word) -> word  // 保留英文词
                else -> vocab.zhToEn[word]
            }
        }

        // 如果所有词都命中词表，返回组合结果
        return if (translatedWords.all { it != null || words[translatedWords.indexOf(it)] in STOP_WORDS }) {
            translatedWords.filterNotNull().joinToString(" ")
        } else {
            null
        }
    }

    /**
     * 尝试模型翻译
     */
    private fun tryModelTranslate(query: String): String? {
        return try {
            val result = lazyTranslator.translate(query)
            // 如果翻译结果与输入相同，说明翻译失败
            if (result == query) null else result
        } catch (e: Exception) {
            Log.w(TAG, "Model translation failed", e)
            null
        }
    }

    /**
     * 简单中文分词（基于词典 + 最大正向匹配）
     *
     * 这是一个简化实现，足够处理搜索查询级别的短文本。
     * 复杂场景可引入 jieba 或类似分词库。
     */
    private fun segmentQuery(query: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = query

        while (remaining.isNotEmpty()) {
            // 先尝试匹配词表中的最长词
            var matched = false
            val maxLen = minOf(remaining.length, 8) // 最大匹配 8 个字

            for (len in maxLen downTo 1) {
                val sub = remaining.substring(0, len)
                if (vocab.zhToEn.containsKey(sub) || sub in STOP_WORDS) {
                    result.add(sub)
                    remaining = remaining.substring(len)
                    matched = true
                    break
                }
            }

            if (!matched) {
                // 未命中词表，按单字切分
                val firstChar = remaining[0].toString()
                result.add(firstChar)
                remaining = remaining.substring(1)
            }
        }

        return result
    }

    /**
     * 释放翻译引擎资源
     */
    fun release() {
        translator?.release() ?: lazyTranslator.release()
    }
}
