package com.picme.features.camera.voice

import android.content.Context
import com.picme.core.common.Logger

/**
 * MNN ASR 本地模型客户端（预留实现）
 *
 * 通过 JNI 桥接调用 MNN ASR C++ 库，实现端侧语音识别。
 * 需要 MNN ASR 模型文件支持。
 *
 * @param context Application Context
 */
class MnnAsrClient(private val context: Context) : AsrEngine {

    private val tag = "PicMe:MnnAsrClient"
    private var nativeHandle: Long = 0L

    override fun isAvailable(): Boolean {
        return nativeHandle != 0L || tryLoadModel()
    }

    override suspend fun transcribe(audioData: ByteArray): Result<String> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("MNN ASR model not loaded"))
        }

        @Suppress("TooGenericExceptionCaught")
        return try {
            val result = nativeTranscribe(nativeHandle, audioData)
            Logger.d(tag, "MNN ASR result: $result")
            Result.success(result)
        } catch (runtimeException: RuntimeException) {
            Logger.e(tag, "MNN ASR transcription failed", runtimeException)
            Result.failure(runtimeException)
        }
    }

    /**
     * 尝试加载本地 ASR 模型
     *
     * 检查模型是否已下载到 llm_models 目录。
     */
    private fun tryLoadModel(): Boolean {
        val modelDir = context.filesDir.resolve("llm_models/mnn-asr")
        if (!modelDir.exists()) {
            Logger.d(tag, "MNN ASR model not found at ${modelDir.absolutePath}")
            return false
        }

        // JNI model loading reserved for future implementation
        // nativeHandle = nativeCreate(modelDir.absolutePath)
        // return nativeHandle != 0L

        Logger.i(tag, "MNN ASR model directory exists, but JNI not yet implemented")
        return false
    }

    /**
     * 释放模型资源
     */
    fun unload() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            Logger.d(tag, "MNN ASR unloaded")
        }
    }

    // ── Native Methods (预留) ─────────────────────────────────────

    @Suppress("UnusedPrivateMember")
    private external fun nativeCreate(modelDir: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeTranscribe(handle: Long, audioData: ByteArray): String

    companion object {
        init {
            try {
                System.loadLibrary("picme_native")
            } catch (linkError: UnsatisfiedLinkError) {
                Logger.d("PicMe:MnnAsrClient", "Native library not available: ${linkError.message}")
            }
        }
    }
}
