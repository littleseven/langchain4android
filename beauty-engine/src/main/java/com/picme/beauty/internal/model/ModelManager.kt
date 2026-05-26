package com.picme.beauty.internal.model

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 统一模型文件管理器
 *
 * 负责所有推理模型文件的元数据管理和 assets → filesDir 复制逻辑。
 * 替代各检测器中重复的 ensureModelFile() 方法。
 */
object ModelManager {

    private const val TAG = "PicMe:ModelManager"

    /**
     * 模型文件元数据
     *
     * @param assetPath assets 中的路径（相对于 assets 根目录）
     * @param cacheName 复制到 filesDir 后的文件名
     * @param version 模型版本号，用于校验是否需要更新
     */
    data class ModelInfo(
        val assetPath: String,
        val cacheName: String,
        val version: String
    )

    /**
     * NCNN 模型需要 .param + .bin 两个文件
     *
     * @param paramAssetPath .param 文件在 assets 中的路径
     * @param binAssetPath .bin 文件在 assets 中的路径
     * @param paramCacheName 复制到 filesDir 后的 .param 文件名
     * @param binCacheName 复制到 filesDir 后的 .bin 文件名
     * @param version 模型版本号
     */
    data class NcnnModelInfo(
        val paramAssetPath: String,
        val binAssetPath: String,
        val paramCacheName: String,
        val binCacheName: String,
        val version: String
    )

    /**
     * LLM 模型信息（MNN-LLM 需要整个目录）
     *
     * 模型文件不再打包在 assets 中，改为从 ModelScope 远程下载到 filesDir/llm_models/。
     *
     * @param cacheDirName 下载到 filesDir 后的目录名
     * @param version 模型版本号
     */
    data class LlmModelInfo(
        val cacheDirName: String,
        val version: String
    )

    // ── 模型注册表 ───────────────────────────────────────────

    private val MODEL_REGISTRY = mapOf(
        // ONNX 模型
        "det10g_onnx" to ModelInfo(
            assetPath = "models/onnx/det_10g.onnx",
            cacheName = "det_10g.onnx",
            version = "1.0"
        ),
        "2d106_onnx" to ModelInfo(
            assetPath = "models/onnx/2d106det.onnx",
            cacheName = "2d106det.onnx",
            version = "1.0"
        ),

        // MNN 模型
        "det10g_mnn" to ModelInfo(
            assetPath = "models/mnn/det_10g.mnn",
            cacheName = "det_10g.mnn",
            version = "1.0"
        ),
        "2d106_mnn" to ModelInfo(
            assetPath = "models/mnn/2d106det.mnn",
            cacheName = "2d106det.mnn",
            version = "1.0"
        )
    )

    private val NCNN_MODEL_REGISTRY = mapOf(
        "det10g_ncnn" to NcnnModelInfo(
            paramAssetPath = "models/ncnn/det_10g.param",
            binAssetPath = "models/ncnn/det_10g.bin",
            paramCacheName = "det_10g.param",
            binCacheName = "det_10g.bin",
            version = "1.0"
        ),
        "2d106_ncnn" to NcnnModelInfo(
            paramAssetPath = "models/ncnn/2d106det.param",
            binAssetPath = "models/ncnn/2d106det.bin",
            paramCacheName = "2d106det.param",
            binCacheName = "2d106det.bin",
            version = "1.0"
        )
    )

    private val LLM_MODEL_REGISTRY = mapOf(
        "qwen3_0_6b" to LlmModelInfo(
            cacheDirName = "qwen3-0-6b",
            version = "1.0"
        ),
        "qwen3-0.6b" to LlmModelInfo(
            cacheDirName = "qwen3-0-6b",
            version = "1.0"
        ),
        "qwen3_5_2b" to LlmModelInfo(
            cacheDirName = "qwen3-5-2b",
            version = "1.0"
        ),
        "qwen3.5-2b" to LlmModelInfo(
            cacheDirName = "qwen3-5-2b",
            version = "1.0"
        )
    )

    // ── 公共 API ─────────────────────────────────────────────

    /**
     * 准备单个模型文件（assets → filesDir）
     *
     * @param key 模型注册表中的 key
     * @param context Context
     * @return 复制后的文件
     * @throws IllegalArgumentException 如果 key 不存在
     * @throws RuntimeException 如果复制失败
     */
    fun prepareModel(key: String, context: Context): File {
        val info = MODEL_REGISTRY[key]
            ?: throw IllegalArgumentException("Unknown model key: $key")

        return copyAssetToCache(info.assetPath, info.cacheName, context)
    }

    /**
     * 准备 NCNN 模型文件对（.param + .bin）
     *
     * @param key NCNN 模型注册表中的 key
     * @param context Context
     * @return Pair<paramFile, binFile>
     * @throws IllegalArgumentException 如果 key 不存在
     * @throws RuntimeException 如果复制失败
     */
    fun prepareNcnnModel(key: String, context: Context): Pair<File, File> {
        val info = NCNN_MODEL_REGISTRY[key]
            ?: throw IllegalArgumentException("Unknown NCNN model key: $key")

        val paramFile = copyAssetToCache(info.paramAssetPath, info.paramCacheName, context)
        val binFile = copyAssetToCache(info.binAssetPath, info.binCacheName, context)

        return Pair(paramFile, binFile)
    }

    /**
     * 检查模型是否已缓存（无需复制）
     */
    fun isModelCached(key: String, context: Context): Boolean {
        val info = MODEL_REGISTRY[key] ?: return false
        val file = File(context.filesDir, info.cacheName)
        return file.exists() && file.length() > 0L
    }

    /**
     * 检查 NCNN 模型是否已缓存
     */
    fun isNcnnModelCached(key: String, context: Context): Boolean {
        val info = NCNN_MODEL_REGISTRY[key] ?: return false
        val paramFile = File(context.filesDir, info.paramCacheName)
        val binFile = File(context.filesDir, info.binCacheName)
        return paramFile.exists() && paramFile.length() > 0L &&
            binFile.exists() && binFile.length() > 0L
    }

    // ── LLM 模型 API ─────────────────────────────────────────

    /**
     * 准备 LLM 模型目录（MNN-LLM 需要整个目录结构）
     *
     * 支持两种模式：
     * 1. 注册表模式：key 在 LLM_MODEL_REGISTRY 中存在，使用对应的 cacheDirName
     * 2. 动态发现模式：key 不在注册表中，直接尝试在 llm_models/<key>/ 查找
     *
     * @param key LLM 模型 key（如 "qwen3_0_6b" 或 "qwen3-1-7b"）
     * @param context Context
     * @return 模型目录绝对路径
     * @throws IllegalStateException 如果模型未下载
     */
    fun prepareLlmModel(key: String, context: Context): String {
        // 1. 注册表命中：使用预定义的 cacheDirName
        val registryInfo = LLM_MODEL_REGISTRY[key]
        if (registryInfo != null) {
            val downloadDir = File(context.filesDir, "llm_models/${registryInfo.cacheDirName}")
            if (downloadDir.exists() && isLlmModelComplete(downloadDir)) {
                Log.d(TAG, "LLM model found in download dir: ${downloadDir.absolutePath}")
                return downloadDir.absolutePath
            }

            val destDir = File(context.filesDir, registryInfo.cacheDirName)
            if (destDir.exists() && isLlmModelComplete(destDir)) {
                Log.d(TAG, "LLM model already cached: ${destDir.absolutePath}")
                return destDir.absolutePath
            }
        }

        // 2. 动态发现：直接在 llm_models/<key>/ 查找（支持任意下载的模型）
        val dynamicDir = File(context.filesDir, "llm_models/$key")
        if (dynamicDir.exists() && isLlmModelComplete(dynamicDir)) {
            Log.d(TAG, "LLM model found dynamically: ${dynamicDir.absolutePath}")
            return dynamicDir.absolutePath
        }

        // 3. 尝试规范化 key（下划线/点号/连字符互转）
        val normalizedKeys = generateNormalizedKeys(key)
        for (normalizedKey in normalizedKeys) {
            val normalizedDir = File(context.filesDir, "llm_models/$normalizedKey")
            if (normalizedDir.exists() && isLlmModelComplete(normalizedDir)) {
                Log.d(TAG, "LLM model found with normalized key '$normalizedKey': ${normalizedDir.absolutePath}")
                return normalizedDir.absolutePath
            }
        }

        // 4. 模型未找到
        throw IllegalStateException(
            "LLM model '$key' not found. Please download it from ModelScope first. " +
                "Expected at: ${File(context.filesDir, "llm_models/$key").absolutePath}"
        )
    }

    /**
     * 检查 LLM 模型是否已缓存
     */
    fun isLlmModelCached(key: String, context: Context): Boolean {
        // 注册表命中
        val registryInfo = LLM_MODEL_REGISTRY[key]
        if (registryInfo != null) {
            val downloadDir = File(context.filesDir, "llm_models/${registryInfo.cacheDirName}")
            if (downloadDir.exists() && isLlmModelComplete(downloadDir)) {
                return true
            }
            val destDir = File(context.filesDir, registryInfo.cacheDirName)
            if (destDir.exists() && isLlmModelComplete(destDir)) {
                return true
            }
        }

        // 动态发现
        val dynamicDir = File(context.filesDir, "llm_models/$key")
        if (dynamicDir.exists() && isLlmModelComplete(dynamicDir)) {
            return true
        }

        // 规范化 key 尝试
        val normalizedKeys = generateNormalizedKeys(key)
        for (normalizedKey in normalizedKeys) {
            val normalizedDir = File(context.filesDir, "llm_models/$normalizedKey")
            if (normalizedDir.exists() && isLlmModelComplete(normalizedDir)) {
                return true
            }
        }

        return false
    }

    /**
     * 生成规范化的 key 变体（处理下划线/点号/连字符差异）
     */
    private fun generateNormalizedKeys(key: String): List<String> {
        val result = mutableSetOf<String>()
        result.add(key.replace("_", "-"))
        result.add(key.replace("-", "_"))
        result.add(key.replace(".", "-"))
        result.add(key.replace(".", "_"))
        result.add(key.replace("_", "-").replace(".", "-"))
        result.add(key.replace("-", "_").replace(".", "_"))
        return result.filter { it != key }.toList()
    }

    private fun isLlmModelComplete(dir: File): Boolean {
        return dir.walkTopDown().any { it.name.endsWith(".mnn") }
    }

    // ── 内部实现 ─────────────────────────────────────────────

    private fun copyAssetToCache(assetPath: String, cacheName: String, context: Context): File {
        val file = File(context.filesDir, cacheName)

        if (file.exists() && file.length() > 0L) {
            return file
        }

        try {
            context.assets.open(assetPath).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model copied: $assetPath -> ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model: $assetPath", e)
            throw RuntimeException("Failed to copy model from assets: $assetPath", e)
        }

        return file
    }
}
