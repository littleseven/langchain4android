package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * 受控词表 —— 从 assets/controlled_vocab.json 加载的标签规范化词库
 *
 * 用于后处理阶段将 Qwen 自由文本输出映射到标准标签。
 * 词表是软约束：未匹配的词保留原值到 [QwenTagsNormalized.nonStandard]。
 */
data class ControlledVocab(
    val scene: List<String> = emptyList(),
    val activity: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val atmosphere: List<String> = emptyList(),
    val people: List<String> = emptyList()
) {
    /** 返回所有类别的标签并集（用于跨类别模糊匹配） */
    val allCategories: List<String> by lazy {
        scene + activity + objects + atmosphere + people
    }

    companion object {
        private const val TAG = "ControlledVocab"

        /**
         * 从 assets 目录加载受控词表
         */
        fun loadFromAssets(context: Context): ControlledVocab {
            return try {
                val jsonString = context.assets.open("controlled_vocab.json")
                    .bufferedReader()
                    .use { it.readText() }
                parseJson(jsonString)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to load vocab from assets", e)
                ControlledVocab()
            }
        }

        private fun parseJson(jsonString: String): ControlledVocab {
            val root = JSONObject(jsonString)
            return ControlledVocab(
                scene = root.optJSONArray("scene")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                activity = root.optJSONArray("activity")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                objects = root.optJSONArray("objects")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                atmosphere = root.optJSONArray("atmosphere")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                people = root.optJSONArray("people")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )
        }
    }
}
