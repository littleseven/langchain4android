package com.picme.agent.core.llm

import android.content.Context
import com.picme.agent.core.Logger
import com.picme.agent.core.mnn.MnnGlobalReleaseLock
import org.json.JSONObject
import java.io.File

/**
 * MNN-LLM 本地模型客户端
 *
 * 通过 JNI 桥接调用 MNN-LLM C++ 库，实现端侧 LLM 推理。
 * 模型文件通过 [LlmModelManager] 从 assets 复制到 filesDir。
 *
 * **线程安全说明**：本类不做线程切换，所有操作应在调用方指定的线程上执行。
 * [LocalLlmEngine] 使用专用单线程调度器串行化所有调用，避免并发冲突。
 *
 * @param context Application Context
 */
class MnnLlmClient(private val context: Context) {

    enum class NativeReleaseTarget {
        KV_CACHE,
        WEIGHTS_INTERPRETER_TENSORS
    }

    private var nativeHandle: Long = 0L
    private val tag = "MnnLlmClient"

    /**
     * 模型加载状态
     */
    val isLoaded: Boolean
        get() = nativeHandle != 0L && MnnGlobalReleaseLock.withOperation {
            nativeIsLoaded(nativeHandle)
        }

    /**
     * 加载本地 LLM 模型
     *
     * **注意**：此方法应在专用线程上调用（由 [LocalLlmEngine] 统一调度），
     * 不做额外的 withContext 线程切换。
     *
     * @param modelKey LlmModelManager 中注册的模型 key，默认 "qwen3_1_7b"（下划线格式）
     * @return 加载是否成功
     */
    fun load(modelKey: String = "qwen3_1_7b", useOpencl: Boolean = false): Boolean {
        if (isLoaded) {
            Logger.d(tag, "Model already loaded")
            return true
        }

        return try {
            val modelDir = LlmModelManager(context).prepareModel(modelKey)
            val configPath = "$modelDir/config.json"

            // 验证 config.json 存在且有效
            val configFile = File(configPath)
            if (!configFile.exists() || configFile.length() == 0L) {
                Logger.e(tag, "LLM config not found or empty: $configPath")
                return false
            }

            // 验证模型文件存在（llm.mnn 或 llm.mnn.weight 至少有一个）
            val modelFile = File(modelDir, "llm.mnn")
            val weightFile = File(modelDir, "llm.mnn.weight")
            if (!modelFile.exists() && !weightFile.exists()) {
                Logger.e(tag, "LLM model files not found in: $modelDir")
                return false
            }

            // 验证模型文件不是 Git LFS 指针（检查文件头）
            if (modelFile.exists() && modelFile.length() < 1000) {
                modelFile.bufferedReader().use { reader ->
                    val firstLine = reader.readLine() ?: ""
                    if (firstLine.contains("git-lfs")) {
                        Logger.e(tag, "LLM model file is a Git LFS pointer, not actual model: ${modelFile.absolutePath}")
                        return false
                    }
                }
            }

            val runtimeConfigPath = if (useOpencl) {
                runCatching {
                    createRuntimeConfig(modelDir, configFile)
                }.onFailure { exception ->
                    Logger.w(tag, "Failed to patch backend_type=opencl, fallback to original config", exception)
                }.getOrNull()?.also { patchedPath ->
                    Logger.i(tag, "Using OpenCL runtime config: $patchedPath")
                } ?: configPath
            } else {
                configPath
            }

            Logger.i(tag, "Loading LLM model from: $runtimeConfigPath")
            nativeHandle = MnnGlobalReleaseLock.withOperation {
                nativeCreate(runtimeConfigPath)
            }

            if (nativeHandle == 0L) {
                Logger.e(tag, "Failed to create LLM native instance")
                return false
            }

            Logger.i(tag, "LLM model loaded successfully")
            true
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to load LLM model: ${exception.message}", exception)
            false
        }
    }

    /**
     * 生成文本回复
     *
     * **注意**：此方法应在专用线程上调用（由 [LocalLlmEngine] 统一调度）。
     *
     * @param prompt 用户输入提示词
     * @param maxNewTokens 最大生成 token 数，默认 128
     * @return 生成的文本
     */
    fun generate(prompt: String, maxNewTokens: Int = 128): String {
        if (!isLoaded) {
            Logger.w(tag, "LLM not loaded, cannot generate")
            return ""
        }

        return try {
            MnnGlobalReleaseLock.withOperation {
                nativeGenerate(nativeHandle, prompt, maxNewTokens)
            }
        } catch (exception: Exception) {
            Logger.e(tag, "Generation failed", exception)
            ""
        }
    }

    /**
     * 使用 system prompt + user prompt 生成回复
     *
     * **注意**：此方法应在专用线程上调用（由 [LocalLlmEngine] 统一调度）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @param maxNewTokens 最大生成 token 数，默认 128
     * @return 生成的文本
     */
    fun generateWithSystem(
        systemPrompt: String,
        userPrompt: String,
        maxNewTokens: Int = 128
    ): String {
        if (!isLoaded) {
            Logger.w(tag, "LLM not loaded, cannot generate")
            return ""
        }

        return try {
            MnnGlobalReleaseLock.withOperation {
                nativeGenerateWithSystem(nativeHandle, systemPrompt, userPrompt, maxNewTokens)
            }
        } catch (exception: Exception) {
            Logger.e(tag, "Generation with system prompt failed", exception)
            ""
        }
    }

    /**
     * 显式释放 native 资源。
     *
     * - KV_CACHE: 仅清理 KV cache / history，保留权重
     * - WEIGHTS_INTERPRETER_TENSORS: 彻底卸载（权重 + interpreter + tensor）
     */
    fun releaseNative(target: NativeReleaseTarget) {
        if (nativeHandle == 0L) {
            return
        }
        when (target) {
            NativeReleaseTarget.KV_CACHE -> {
                MnnGlobalReleaseLock.withOperation {
                    nativeReset(nativeHandle)
                }
                Logger.d(tag, "LLM KV cache released (history cleared)")
            }
            NativeReleaseTarget.WEIGHTS_INTERPRETER_TENSORS -> {
                MnnGlobalReleaseLock.withLock {
                    nativeDestroy(nativeHandle)
                }
                nativeHandle = 0L
                Logger.d(tag, "LLM weights/interpreter/tensors released")
            }
        }
    }

    /**
     * 释放模型资源
     */
    fun unload() {
        releaseNative(NativeReleaseTarget.WEIGHTS_INTERPRETER_TENSORS)
    }

    /**
     * 重置模型状态，清理历史记录和 KV Cache（不卸载模型）
     *
     * 用于相机场景降低内存占用，避免与 Sherpa-MNN ASR 的 MNN 全局状态冲突。
     */
    fun reset() {
        releaseNative(NativeReleaseTarget.KV_CACHE)
    }

    private fun createRuntimeConfig(modelDir: String, configFile: File): String {
        val rawJson = configFile.readText()
        val root = JSONObject(rawJson)
        root.put("backend_type", "opencl")

        val mllmObject = root.optJSONObject("mllm") ?: JSONObject().also { objectNode ->
            root.put("mllm", objectNode)
        }
        mllmObject.put("backend_type", "opencl")

        val runtimeConfigFile = File(modelDir, "config_runtime_opencl.json")
        runtimeConfigFile.writeText(root.toString(2))
        return runtimeConfigFile.absolutePath
    }

    // ── Native Methods ─────────────────────────────────────

    private external fun nativeCreate(configPath: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
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
            System.loadLibrary("agent_native")
        }
    }
}
