package com.mamba.picme.domain.tag.i18n

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * ML Kit 标签中英文静态翻译器
 *
 * ML Kit Image Labeling API 使用固定的 ~400 个英文标签。
 * 本翻译器通过预编译的静态映射表实现零耗时的英文↔中文翻译，
 * 避免运行时 NMT 模型翻译的不确定性。
 *
 * 两类职责：
 * 1. **索引时翻译**：提取的英文标签 → 对应的中文标签（存储到 mlKitLabelsZh 列）
 * 2. **搜索时扩展**：中文查询词 → 对应的英文 ML Kit 标签（反向查找，命中英文列）
 *
 * 与 [TagTranslator] 的区别：
 * - TagTranslator：运行时翻译，依赖 BilingualVocab + OpusMtTranslator，覆盖中文 Qwen 标签
 * - MlKitLabelTranslator：静态映射，覆盖 ML Kit 固定标签集，零延迟
 */
class MlKitLabelTranslator(
    /** 英文→中文映射（如 "Cat" → "猫"） */
    val enToZh: Map<String, String>,
    /** 中文→英文映射（如 "猫" → ["Cat"]），一个中文词可能对应多个英文标签 */
    val zhToEn: Map<String, List<String>>
) {
    companion object {
        private const val TAG = "MlKitLabelTrans"
        private const val ASSET_NAME = "mlkit_labels_zh.json"

        /**
         * 从 assets 加载 ML Kit 标签翻译表。
         * 如果 asset 不存在或解析失败，返回空翻译器（系统仍可用，仅无 ML Kit 中文匹配）。
         */
        fun loadFromAssets(context: Context): MlKitLabelTranslator {
            return try {
                val json = context.assets.open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() }
                parseJson(json)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to load ML Kit label translations from assets", e)
                empty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse ML Kit label translations", e)
                empty()
            }
        }

        fun empty(): MlKitLabelTranslator = MlKitLabelTranslator(emptyMap(), emptyMap())

        private fun parseJson(jsonString: String): MlKitLabelTranslator {
            val root = JSONObject(jsonString)
            val enToZhObj = root.getJSONObject("en_to_zh")

            val enToZh = mutableMapOf<String, String>()
            // 反向索引：中文词 → 对应的所有英文标签列表
            val zhToEnAccum = mutableMapOf<String, MutableList<String>>()

            enToZhObj.keys().forEach { en ->
                val zh = enToZhObj.getString(en)
                enToZh[en] = zh
                zhToEnAccum.getOrPut(zh) { mutableListOf() }.add(en)
            }

            return MlKitLabelTranslator(
                enToZh = enToZh,
                zhToEn = zhToEnAccum.mapValues { it.value.toList() }
            )
        }
    }

    /**
     * 将 ML Kit 英文标签列表翻译为中文。
     *
     * @param englishLabels 英文标签列表（如 ["Cat", "Outdoor", "Food"]）
     * @return 中文标签列表（如 ["猫", "户外", "食物"]），未命中词表的词被过滤
     */
    fun translateToZh(englishLabels: List<String>): List<String> {
        return englishLabels.mapNotNull { enToZh[it] }
    }

    /**
     * 将 ML Kit 英文标签列表翻译为中文 JSON 数组字符串。
     *
     * @param englishLabels 英文标签列表
     * @return JSON 数组字符串（如 `["猫","户外","食物"]`），无匹配时返回 "[]"
     */
    fun translateToZhJson(englishLabels: List<String>): String {
        val zhLabels = translateToZh(englishLabels)
        if (zhLabels.isEmpty()) return "[]"
        return zhLabels.joinToString(prefix = "[", postfix = "]", separator = ",") { label ->
            "\"${label.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
    }

    /**
     * 中文查询词反向扩展：找到对应的英文 ML Kit 标签。
     *
     * 用于搜索场景：用户输入中文 "猫" → 反向查找 → ["Cat"]
     * 这样可以用英文 "Cat" 去 `mlKitLabels` 列做 LIKE 匹配。
     *
     * @param chineseQuery 中文查询词
     * @return 对应的英文 ML Kit 标签列表，未命中时返回空列表
     */
    fun reverseLookup(chineseQuery: String): List<String> {
        return zhToEn[chineseQuery] ?: emptyList()
    }
}
