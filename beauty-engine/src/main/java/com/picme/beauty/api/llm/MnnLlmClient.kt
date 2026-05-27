package com.picme.beauty.api.llm

import android.content.Context
import android.util.Log
import com.picme.beauty.internal.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MNN-LLM 本地模型客户端
 *
 * 通过 JNI 桥接调用 MNN-LLM C++ 库，实现端侧 LLM 推理。
 * 模型文件通过 [ModelManager] 从 assets 复制到 filesDir。
 *
 * @param context Application Context
 */
class MnnLlmClient(private val context: Context) {

    private var nativeHandle: Long = 0L
    private val tag = "PicMe:MnnLlmClient"

    /**
     * 模型加载状态
     */
    val isLoaded: Boolean
        get() = nativeHandle != 0L && nativeIsLoaded(nativeHandle)

    /**
     * 加载本地 LLM 模型
     *
     * @param modelKey ModelManager 中注册的模型 key，默认 "qwen3_0_6b"
     * @return 加载是否成功
     */
    suspend fun load(modelKey: String = "qwen3_0_6b"): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) {
            Log.d(tag, "Model already loaded")
            return@withContext true
        }

        try {
            val modelDir = ModelManager.prepareLlmModel(modelKey, context)
            val configPath = "$modelDir/config.json"

            // 验证 config.json 存在且有效
            val configFile = java.io.File(configPath)
            if (!configFile.exists() || configFile.length() == 0L) {
                Log.e(tag, "LLM config not found or empty: $configPath")
                return@withContext false
            }

            // 验证模型文件存在（llm.mnn 或 llm.mnn.weight 至少有一个）
            val modelFile = java.io.File(modelDir, "llm.mnn")
            val weightFile = java.io.File(modelDir, "llm.mnn.weight")
            if (!modelFile.exists() && !weightFile.exists()) {
                Log.e(tag, "LLM model files not found in: $modelDir")
                return@withContext false
            }

            // 验证模型文件不是 Git LFS 指针（检查文件头）
            if (modelFile.exists() && modelFile.length() < 1000) {
                modelFile.bufferedReader().use { reader ->
                    val firstLine = reader.readLine() ?: ""
                    if (firstLine.contains("git-lfs")) {
                        Log.e(tag, "LLM model file is a Git LFS pointer, not actual model: ${modelFile.absolutePath}")
                        return@withContext false
                    }
                }
            }

            Log.i(tag, "Loading LLM model from: $configPath")
            nativeHandle = nativeCreate(configPath)

            if (nativeHandle == 0L) {
                Log.e(tag, "Failed to create LLM native instance")
                return@withContext false
            }

            Log.i(tag, "LLM model loaded successfully")
            true
        } catch (exception: Exception) {
            Log.e(tag, "Failed to load LLM model: ${exception.message}", exception)
            false
        }
    }

    /**
     * 生成文本回复
     *
     * @param prompt 用户输入提示词
     * @param maxNewTokens 最大生成 token 数，默认 128
     * @return 生成的文本
     */
    suspend fun generate(prompt: String, maxNewTokens: Int = 128): String = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            Log.w(tag, "LLM not loaded, cannot generate")
            return@withContext ""
        }

        try {
            nativeGenerate(nativeHandle, prompt, maxNewTokens)
        } catch (exception: Exception) {
            Log.e(tag, "Generation failed", exception)
            ""
        }
    }

    /**
     * 使用 system prompt + user prompt 生成回复
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @param maxNewTokens 最大生成 token 数，默认 128
     * @return 生成的文本
     */
    suspend fun generateWithSystem(
        systemPrompt: String,
        userPrompt: String,
        maxNewTokens: Int = 128
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            Log.w(tag, "LLM not loaded, cannot generate")
            return@withContext ""
        }

        try {
            nativeGenerateWithSystem(nativeHandle, systemPrompt, userPrompt, maxNewTokens)
        } catch (exception: Exception) {
            Log.e(tag, "Generation with system prompt failed", exception)
            ""
        }
    }

    /**
     * 释放模型资源
     */
    fun unload() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            Log.d(tag, "LLM unloaded")
        }
    }

    // ── Native Methods ─────────────────────────────────────

    private external fun nativeCreate(configPath: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGenerate(handle: Long, prompt: String, maxNewTokens: Int): String
    private external fun nativeGenerateWithSystem(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxNewTokens: Int
    ): String

    private external fun nativeIsLoaded(handle: Long): Boolean

    companion object {
        init {
            System.loadLibrary("picme_native")
        }
    }
}
