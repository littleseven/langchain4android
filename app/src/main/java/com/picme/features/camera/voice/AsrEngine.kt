package com.picme.features.camera.voice

/**
 * ASR（自动语音识别）引擎抽象接口
 */
interface AsrEngine {

    /**
     * 将音频数据转录为文本
     *
     * @param audioData PCM 音频数据（16kHz, 16bit, 单声道）
     * @return 识别结果
     */
    suspend fun transcribe(audioData: ByteArray): Result<String>

    /**
     * 当前引擎是否可用
     */
    fun isAvailable(): Boolean
}
