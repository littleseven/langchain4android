package com.mamba.picme.agent.core.platform.llm.local

import android.content.Context
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.mnn.MnnGlobalReleaseLock
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

            val runtimeConfigPath = runCatching {
                createRuntimeConfig(modelDir, configFile, useOpencl)
            }.onFailure { exception ->
                Logger.w(tag, "Failed to create runtime config, fallback to original config", exception)
            }.getOrNull()?.also { patchedPath ->
                Logger.i(tag, "Using runtime config: $patchedPath (opencl=$useOpencl)")
            } ?: configPath

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
     * 生成文本回复（同步阻塞）
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
     * 使用 system prompt + user prompt 生成回复（同步阻塞）
     *
     * **注意**：此方法应在专用线程上调用（由 [LocalLlmEngine] 统一调度）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @param maxNewTokens 最大生成 token 数，默认 128
     * @return 包含完整回复和性能指标的 [StreamResult]
     */
    fun generateWithSystem(
        systemPrompt: String,
        userPrompt: String,
        maxNewTokens: Int = 128
    ): StreamResult {
        if (!isLoaded) {
            Logger.w(tag, "LLM not loaded, cannot generate")
            return StreamResult(error = "LLM not loaded")
        }

        return try {
            val resultMap = MnnGlobalReleaseLock.withOperation {
                nativeGenerateWithSystem(nativeHandle, systemPrompt, userPrompt, maxNewTokens)
            }
            StreamResult.fromHashMap(resultMap)
        } catch (exception: Exception) {
            Logger.e(tag, "Generation with system prompt failed", exception)
            StreamResult(error = exception.message ?: "Unknown error")
        }
    }

    // ── 流式生成 + 性能指标（新增）─────────────────────────────

    /**
     * 流式生成文本回复，逐 token 回调 + 性能指标
     *
     * **注意**：此方法应在专用线程上调用（由 [LocalLlmEngine] 统一调度）。
     * 回调在 native 线程执行，通过 JNI 同步调用 Java 方法。
     *
     * @param prompt 用户输入提示词
     * @param maxNewTokens 最大生成 token 数，默认 128
     * @param listener 流式回调监听器，每生成一个 token 调用一次
     * @return 包含完整回复和性能指标的 StreamResult
     */
    fun generateStream(
        prompt: String,
        maxNewTokens: Int = 128,
        listener: StreamGenerateListener
    ): StreamResult {
        if (!isLoaded) {
            Logger.w(tag, "LLM not loaded, cannot generate")
            return StreamResult(error = "LLM not loaded")
        }

        return try {
            val resultMap = MnnGlobalReleaseLock.withOperation {
                nativeGenerateStream(nativeHandle, prompt, maxNewTokens, listener)
            }
            StreamResult.fromHashMap(resultMap)
        } catch (exception: Exception) {
            Logger.e(tag, "Stream generation failed", exception)
            StreamResult(error = exception.message ?: "Unknown error")
        }
    }

    /**
     * 使用多轮对话历史进行流式生成
     *
     * @param history 对话历史列表，每个 Pair 为 (role, content)
     * @param maxNewTokens 最大生成 token 数
     * @param listener 流式回调监听器
     * @return 包含完整回复和性能指标的 StreamResult
     */
    fun generateWithHistoryStream(
        history: List<Pair<String, String>>,
        maxNewTokens: Int = 128,
        listener: StreamGenerateListener
    ): StreamResult {
        if (!isLoaded) {
            Logger.w(tag, "LLM not loaded, cannot generate")
            return StreamResult(error = "LLM not loaded")
        }

        return try {
            // 转换为 android.util.Pair 列表供 JNI 使用
            val androidHistory = history.map { android.util.Pair(it.first, it.second) }
            val resultMap = MnnGlobalReleaseLock.withOperation {
                nativeGenerateWithHistoryStream(nativeHandle, androidHistory, maxNewTokens, listener)
            }
            StreamResult.fromHashMap(resultMap)
        } catch (exception: Exception) {
            Logger.e(tag, "History stream generation failed", exception)
            StreamResult(error = exception.message ?: "Unknown error")
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

    private fun createRuntimeConfig(modelDir: String, configFile: File, useOpencl: Boolean): String {
        val rawJson = configFile.readText()
        val root = JSONObject(rawJson)

        // 1. 禁用 Qwen3 思考模式，避免输出 <think>...</think> 占用 token
        val jinjaObject = root.optJSONObject("jinja") ?: JSONObject().also { root.put("jinja", it) }
        val contextObject = jinjaObject.optJSONObject("context") ?: JSONObject().also { jinjaObject.put("context", it) }
        contextObject.put("enable_thinking", false)

        // 2. 设置低 temperature 提高 JSON 输出稳定性（Function Calling 场景推荐 0.1-0.2）
        root.put("temperature", root.optDouble("temperature", 0.1))

        // 3. 可选：使用 OpenCL 后端加速
        if (useOpencl) {
            root.put("backend_type", "opencl")
            val mllmObject = root.optJSONObject("mllm") ?: JSONObject().also { root.put("mllm", it) }
            mllmObject.put("backend_type", "opencl")
        }

        val fileName = if (useOpencl) "config_runtime_opencl.json" else "config_runtime.json"
        val runtimeConfigFile = File(modelDir, fileName)
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
    ): HashMap<String, Any>

    private external fun nativeGenerateStream(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        listener: StreamGenerateListener
    ): HashMap<String, Any>

    private external fun nativeGenerateWithHistoryStream(
        handle: Long,
        history: List<android.util.Pair<String, String>>,
        maxNewTokens: Int,
        listener: StreamGenerateListener
    ): HashMap<String, Any>

    private external fun nativeIsLoaded(handle: Long): Boolean

    companion object {
        init {
            System.loadLibrary("agent_native")
        }
    }
}

/**
 * 流式生成回调接口
 *
 * 每生成一个 token（或遇到 <eop> 结束标记）时调用。
 * 返回 true 表示请求停止生成。
 */
interface StreamGenerateListener {
    /**
     * @param token 新生成的 token 文本，isEop=true 时为 null
     * @param isEop 是否为结束标记
     * @return true 表示请求停止生成
     */
    fun onToken(token: String?, isEop: Boolean): Boolean
}

/**
 * 流式生成结果，包含完整回复和性能指标
 */
data class StreamResult(
    val response: String = "",
    val promptLen: Long = 0,
    val decodeLen: Long = 0,
    val visionTime: Long = 0,
    val audioTime: Long = 0,
    val prefillTime: Long = 0,
    val decodeTime: Long = 0,
    val error: String? = null
) {
    val isSuccess: Boolean
        get() = error == null

    /**
     * 计算 prefill 速度（tokens/秒）
     */
    val prefillSpeed: Float
        get() = if (prefillTime > 0) promptLen * 1_000_000f / prefillTime else 0f

    /**
     * 计算 decode 速度（tokens/秒）
     */
    val decodeSpeed: Float
        get() = if (decodeTime > 0) decodeLen * 1_000_000f / decodeTime else 0f

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromHashMap(map: HashMap<String, Any>): StreamResult {
            val error = map["error"] as? String
            return StreamResult(
                response = map["response"] as? String ?: "",
                promptLen = (map["prompt_len"] as? Long) ?: 0,
                decodeLen = (map["decode_len"] as? Long) ?: 0,
                visionTime = (map["vision_time"] as? Long) ?: 0,
                audioTime = (map["audio_time"] as? Long) ?: 0,
                prefillTime = (map["prefill_time"] as? Long) ?: 0,
                decodeTime = (map["decode_time"] as? Long) ?: 0,
                error = error
            )
        }
    }
}