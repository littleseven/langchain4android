package com.mamba.picme.data.download

import android.content.Context
import java.io.File

/**
 * 模型路径配置管理
 *
 * 【设计目标】
 * - 集中管理所有模型的存储路径
 * - 支持新增模型时自动兼容
 * - 避免路径硬编码散落在代码各处
 * - 统一的模型查询和验证接口
 *
 * 【目录结构】
 * /data/data/com.mamba.picme/files/llm_models/
 * ├── qwen-1.7b/                              # LLM 模型
 * ├── sherpa-onnx-asr-zipformer/              # ASR 模型
 * ├── sherpa-onnx-kws-zipformer-wenetspeech/  # KWS 唤醒词模型
 * └── (其他模型...)
 */
object ModelPathConfig {

    /**
     * 获取模型基础目录
     * @param context Android Context
     * @return `/data/data/com.mamba.picme/files/llm_models`
     */
    fun getModelsBaseDir(context: Context): File {
        return File(context.filesDir, "llm_models").also { it.mkdirs() }
    }

    /**
     * 获取指定模型ID的目录
     * @param context Android Context
     * @param modelId 模型ID（e.g., "qwen-1.7b", "sherpa-onnx-kws-zipformer-wenetspeech"）
     * @return 模型目录路径
     */
    fun getModelDir(context: Context, modelId: String): File {
        return File(getModelsBaseDir(context), modelId)
    }

    /**
     * 获取 ASR 模型目录
     * @param context Android Context
     * @return ASR 模型目录路径
     */
    fun getAsrModelDir(context: Context): File {
        return getModelDir(context, MODEL_ID_ASR)
    }

    /**
     * 获取 KWS 唤醒词模型目录
     * @param context Android Context
     * @return KWS 模型目录路径
     */
    fun getKwsModelDir(context: Context): File {
        return getModelDir(context, MODEL_ID_KWS)
    }

    /**
     * 验证模型文件完整性
     * @param modelDir 模型目录
     * @param requiredFiles 必需的文件列表
     * @return 是否所有必需文件都存在
     */
    fun validateModelFiles(modelDir: File, requiredFiles: List<String>): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return false
        }
        return requiredFiles.all { File(modelDir, it).exists() }
    }

    /**
     * 获取缺失的模型文件
     * @param modelDir 模型目录
     * @param requiredFiles 必需的文件列表
     * @return 缺失的文件列表
     */
    fun getMissingFiles(modelDir: File, requiredFiles: List<String>): List<String> {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return requiredFiles
        }
        return requiredFiles.filter { !File(modelDir, it).exists() }
    }

    // ===== 模型 ID 常量定义 =====

    const val MODEL_ID_LLM = "qwen-1.7b"
    const val MODEL_ID_ASR = "sherpa-onnx-asr-zipformer"
    const val MODEL_ID_KWS = "sherpa-onnx-kws-zipformer-wenetspeech"

    // ===== 模型文件列表 =====

    /**
     * LLM 模型文件列表
     */
    val LLM_MODEL_FILES = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "tokenizer.txt"
    )

    /**
     * ASR 模型文件列表
     */
    val ASR_MODEL_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.int8.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt"
    )

    /**
     * KWS 唤醒词模型文件列表
     */
    val KWS_MODEL_FILES = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt",
        "keywords.txt"
    )

    // ===== 模型验证辅助方法 =====

    /**
     * 检查 ASR 模型是否完整
     */
    fun isAsrModelReady(context: Context): Boolean {
        return validateModelFiles(getAsrModelDir(context), ASR_MODEL_FILES)
    }

    /**
     * 检查 KWS 模型是否完整
     */
    fun isKwsModelReady(context: Context): Boolean {
        return validateModelFiles(getKwsModelDir(context), KWS_MODEL_FILES)
    }

    /**
     * 获取模型状态诊断信息
     */
    fun getDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== Model Path Diagnostics ===\n")
        sb.append("Base Dir: ${getModelsBaseDir(context).absolutePath}\n\n")

        // ASR 诊断
        val asrDir = getAsrModelDir(context)
        sb.append("ASR Model (${MODEL_ID_ASR}):\n")
        sb.append("  Path: ${asrDir.absolutePath}\n")
        sb.append("  Exists: ${asrDir.exists()}\n")
        if (asrDir.exists()) {
            val missing = getMissingFiles(asrDir, ASR_MODEL_FILES)
            sb.append("  Status: ${if (missing.isEmpty()) "✓ Complete" else "✗ Missing ${missing.size} files"}\n")
            if (missing.isNotEmpty()) {
                sb.append("  Missing: ${missing.joinToString(", ")}\n")
            }
        }
        sb.append("\n")

        // KWS 诊断
        val kwsDir = getKwsModelDir(context)
        sb.append("KWS Model (${MODEL_ID_KWS}):\n")
        sb.append("  Path: ${kwsDir.absolutePath}\n")
        sb.append("  Exists: ${kwsDir.exists()}\n")
        if (kwsDir.exists()) {
            val missing = getMissingFiles(kwsDir, KWS_MODEL_FILES)
            sb.append("  Status: ${if (missing.isEmpty()) "✓ Complete" else "✗ Missing ${missing.size} files"}\n")
            if (missing.isNotEmpty()) {
                sb.append("  Missing: ${missing.joinToString(", ")}\n")
            }
        }

        return sb.toString()
    }
}

