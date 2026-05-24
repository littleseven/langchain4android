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
