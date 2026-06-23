package com.mamba.picme.domain.tag

import android.util.Log

/**
 * 标签后处理规范化器
 *
 * 将 Qwen 自由文本输出匹配到受控词表，同时保留未匹配的原始文本。
 * 匹配策略：精确匹配 → 包含匹配 → 编辑距离容错 → 保留原始值
 */
class TagNormalizer(private val vocab: ControlledVocab) {

    companion object {
        private const val TAG = "TagNormalizer"
    }

    /**
     * 规范化 Qwen 输出的原始标签
     */
    fun normalize(raw: QwenTags): QwenTagsNormalized {
        val normalizedTags = raw.tags.map { bestMatchAcrossCategories(it) }
        val normalizedObjects = raw.objects.map { bestMatchAcrossCategories(it) }

        val nonStandard = mutableListOf<String>()

        fun track(input: String, output: String) {
            if (input != output) {
                Log.d(TAG, "Normalized: '$input' -> '$output'")
            }
            // 未匹配的收集到 nonStandard
        }

        // 收集未匹配词
        raw.tags.forEach { tag ->
            val matched = bestMatchAcrossCategories(tag)
            if (matched == tag && matched !in vocab.allCategories) {
                nonStandard.add(tag)
            }
        }
        raw.objects.forEach { obj ->
            val matched = bestMatchAcrossCategories(obj)
            if (matched == obj && matched !in vocab.allCategories) {
                nonStandard.add(obj)
            }
        }

        return QwenTagsNormalized(
            scene = bestMatch(raw.scene, vocab.scene),
            activity = bestMatch(raw.activity, vocab.activity),
            objects = normalizedObjects,
            tags = normalizedTags,
            summary = raw.summary,
            nonStandard = nonStandard.distinct()
        )
    }

    /**
     * 从指定候选集中查找最佳匹配
     * 1. 精确匹配
     * 2. 包含匹配（输入包含候选词 或 候选词包含输入）
     * 3. 编辑距离 ≤ 1 容错
     * 4. 未匹配则返回原始值
     */
    private fun bestMatch(input: String, candidates: List<String>): String {
        if (input.isBlank()) return input

        val trimmed = input.trim()

        // 1. 精确匹配
        if (trimmed in candidates) return trimmed

        // 2. 包含匹配
        for (candidate in candidates) {
            if (trimmed.contains(candidate) || candidate.contains(trimmed)) {
                return candidate
            }
        }

        // 3. 编辑距离 ≤ 1 容错（仅对长度 ≥ 2 的词做容错）
        if (trimmed.length >= 2) {
            for (candidate in candidates) {
                if (levenshteinDistance(trimmed, candidate) <= 1) {
                    return candidate
                }
            }
        }

        // 4. 未匹配
        return trimmed
    }

    /**
     * 跨所有类别模糊匹配（用于 objects / tags 字段）
     */
    private fun bestMatchAcrossCategories(input: String): String {
        return bestMatch(input, vocab.allCategories)
    }

    /**
     * 计算两个字符串的编辑距离（Levenshtein 距离）
     */
    internal fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // 删除
                    dp[i][j - 1] + 1,       // 插入
                    dp[i - 1][j - 1] + cost  // 替换
                )
            }
        }

        return dp[a.length][b.length]
    }
}
