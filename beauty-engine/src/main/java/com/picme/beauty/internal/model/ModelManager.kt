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
     * @param assetDir assets 中的目录路径
     * @param cacheDirName 复制到 filesDir 后的目录名
     * @param version 模型版本号
     */
    data class LlmModelInfo(
        val assetDir: String,
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
            assetDir = "models/llm/Qwen3-0.6B-MNN",
            cacheDirName = "Qwen3-0.6B-MNN",
            version = "1.0"
        ),
        "qwen3-0.6b" to LlmModelInfo(
            assetDir = "models/llm/Qwen3-0.6B-MNN",
            cacheDirName = "Qwen3-0.6B-MNN",
            version = "1.0"
        ),
        "qwen3-0-6b" to LlmModelInfo(
            assetDir = "models/llm/Qwen3-0.6B-MNN",
            cacheDirName = "Qwen3-0.6B-MNN",
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
     * 支持三种模式：
     * 1. 下载目录：files/llm_models/<cacheDirName>/
     * 2. 传统缓存目录：files/<cacheDirName>/
     * 3. 从 assets 复制（兜底）
     *
     * @param key LLM 模型注册表中的 key
     * @param context Context
     * @return 模型目录绝对路径
     * @throws IllegalArgumentException 如果 key 不存在且无法动态发现
     */
    fun prepareLlmModel(key: String, context: Context): String {
        val info = LLM_MODEL_REGISTRY[key]

        if (info != null) {
            // 1. 优先检查下载目录 (llm_models/)
            val downloadDir = File(context.filesDir, "llm_models/${info.cacheDirName}")
            if (downloadDir.exists() && isLlmModelComplete(downloadDir)) {
                Log.d(TAG, "LLM model found in download dir: ${downloadDir.absolutePath}")
                return downloadDir.absolutePath
            }

            // 2. 检查传统缓存目录
            val destDir = File(context.filesDir, info.cacheDirName)
            if (destDir.exists() && isLlmModelComplete(destDir)) {
                Log.d(TAG, "LLM model already cached: ${destDir.absolutePath}")
                return destDir.absolutePath
            }

            // 3. 从 assets 复制
            copyAssetDir(info.assetDir, destDir, context)
            Log.i(TAG, "LLM model prepared from assets: ${destDir.absolutePath}")
            return destDir.absolutePath
        }

        // 4. 动态发现：直接在 llm_models/<key>/ 查找（支持任意下载的模型）
        val dynamicDir = File(context.filesDir, "llm_models/$key")
        if (dynamicDir.exists() && isLlmModelComplete(dynamicDir)) {
            Log.d(TAG, "LLM model found dynamically: ${dynamicDir.absolutePath}")
            return dynamicDir.absolutePath
        }

        // 5. 规范化 key 尝试
        val normalizedKeys = generateNormalizedKeys(key)
        for (normalizedKey in normalizedKeys) {
            val normalizedDir = File(context.filesDir, "llm_models/$normalizedKey")
            if (normalizedDir.exists() && isLlmModelComplete(normalizedDir)) {
                Log.d(TAG, "LLM model found with normalized key '$normalizedKey': ${normalizedDir.absolutePath}")
                return normalizedDir.absolutePath
            }
        }

        throw IllegalArgumentException("Unknown LLM model key: $key")
    }

    /**
     * 检查 LLM 模型是否已缓存（支持下载目录和 assets 复制目录）
     */
    fun isLlmModelCached(key: String, context: Context): Boolean {
        val info = LLM_MODEL_REGISTRY[key]

        if (info != null) {
            // 检查下载目录
            val downloadDir = File(context.filesDir, "llm_models/${info.cacheDirName}")
            if (downloadDir.exists() && isLlmModelComplete(downloadDir)) {
                return true
            }

            // 检查传统缓存目录
            val destDir = File(context.filesDir, info.cacheDirName)
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

    private fun copyAssetDir(assetPath: String, destDir: File, context: Context) {
        destDir.mkdirs()
        val assets = context.assets
        val files = assets.list(assetPath) ?: return

        for (file in files) {
            val srcPath = "$assetPath/$file"
            val dstFile = File(destDir, file)

            if (assets.list(srcPath)?.isNotEmpty() == true) {
                copyAssetDir(srcPath, dstFile, context)
            } else {
                assets.open(srcPath).use { input ->
                    dstFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied: $srcPath -> ${dstFile.absolutePath}")
            }
        }
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
