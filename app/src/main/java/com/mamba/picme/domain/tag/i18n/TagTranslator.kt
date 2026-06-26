package com.mamba.picme.domain.tag.i18n

import com.mamba.picme.domain.model.AppLanguage

/**
 * TAG 运行时翻译器
 *
 * 承担两类职责：
 * 1. **展示翻译**：把数据库中存储的中文 canonical TAG 翻译成当前界面语言。
 * 2. **搜索扩展**：把用户输入的查询词扩展为数据库中可能存在的多语言形式，
 *    使英文用户搜 "cat" 也能命中中文标签 "猫"。
 *
 * 实现上完全依赖本地 [BilingualVocab]，不调用任何云端服务，符合 [PRIVACY] 红线。
 */
class TagTranslator(private val vocab: BilingualVocab) {

    /**
     * 把中文 canonical TAG 翻译成目标语言，用于 UI 展示。
     *
     * @param chineseTag 数据库中存储的中文标签（如 "猫"）
     * @param lang 当前用户界面语言
     * @return 翻译后的标签；未命中词表时回退原中文
     */
    fun display(chineseTag: String, lang: AppLanguage): String {
        if (lang != AppLanguage.ENGLISH) return chineseTag
        return vocab.zhToEn[chineseTag] ?: chineseTag
    }

    /**
     * 把用户输入的查询词扩展为一组候选词，用于跨语言搜索。
     *
     * 英文用户输入 "cat" 时，会扩展为 ["cat", "猫"]；
     * 中文用户输入 "猫" 时，会扩展为 ["猫", "cat"]（兼容未来可能存储的英文标签）。
     *
     * @param query 用户输入的原始查询词
     * @param uiLang 当前界面语言
     * @return 需要去重的候选词集合
     */
    fun expandForSearch(query: String, uiLang: AppLanguage): Set<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptySet()

        val result = linkedSetOf(normalized)

        if (uiLang == AppLanguage.ENGLISH) {
            // 英文标准词 -> 中文 canonical
            vocab.enToZh[normalized]?.let { result += it }
            // 英文同义词 -> 英文标准词 -> 中文 canonical
            vocab.enSynonyms[normalized]?.let { standardEn ->
                result += standardEn
                vocab.enToZh[standardEn]?.let { result += it }
            }
        } else {
            // 中文 canonical -> 英文标准词（兼容未来存储的英文标签）
            vocab.zhToEn[query.trim()]?.let { result += it.lowercase() }
        }

        return result
    }

    /**
     * 批量展示翻译，常用于把 labels 列表整体映射后显示。
     */
    fun displayAll(tags: List<String>, lang: AppLanguage): List<String> =
        tags.map { display(it, lang) }
}
