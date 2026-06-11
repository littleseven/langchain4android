package com.mamba.picme.agent.core.platform.voice

import android.content.Context

/**
 * MNN ASR 本地模型客户端（预留实现）
 *
 * 通过 JNI 桥接调用 MNN ASR C++ 库，实现端侧语音识别。
 * 需要 MNN ASR 模型文件支持。
 *
 * @param context Application Context
 */
class MnnAsrClient(
    private val context: Context,
    private val modelId: String = ""
) : AsrEngine {

    private val tag = "MnnAsrClient"

    override fun isAvailable(): Boolean = false

    override suspend fun transcribe(audioData: ByteArray): Result<String> {
        return Result.failure(IllegalStateException("MNN ASR not yet implemented"))
    }

    companion object {
        private const val TAG = "MnnAsrClient"
    }
}
