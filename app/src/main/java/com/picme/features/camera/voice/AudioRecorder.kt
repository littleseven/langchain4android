package com.picme.features.camera.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.picme.core.common.Logger

/**
 * 音频录制工具类
 *
 * 配置：16kHz、单声道、16bit PCM
 *
 * 参考 MnnLlmChat AsrService 的音频优化：
 * - 使用 VOICE_COMMUNICATION 音源启用硬件 AEC
 * - 启用 AcousticEchoCanceler（回声消除）
 * - 启用 NoiseSuppressor（噪声抑制）
 */
class AudioRecorder {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize = 0
    private var isRecording = false

    /**
     * 启动录音
     *
     * 参考 AsrService.initMicrophone():
     * - 使用 VOICE_COMMUNICATION 替代 MIC，启用硬件级回声消除
     * - 动态创建 AEC 和 NS 音频效果器
     */
    @Suppress("ReturnCount")
    fun start(): Boolean {
        if (isRecording) {
            Logger.d(TAG, "Already recording")
            return true
        }

        bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(SAMPLE_RATE * 2) // 至少 1 秒缓冲区

        try {
            // 参考 AsrService: 使用 VOICE_COMMUNICATION 启用硬件 AEC
            val audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION

            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord initialization failed")
                return false
            }

            // 参考 AsrService: 启用 AcousticEchoCanceler
            if (AcousticEchoCanceler.isAvailable()) {
                val echoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                echoCanceler?.enabled = true
                Logger.i(TAG, "AcousticEchoCanceler enabled")
            } else {
                Logger.w(TAG, "AcousticEchoCanceler not available")
            }

            // 参考 AsrService: 启用 NoiseSuppressor
            if (NoiseSuppressor.isAvailable()) {
                val noiseSuppressor = NoiseSuppressor.create(audioRecord!!.audioSessionId)
                noiseSuppressor?.enabled = true
                Logger.i(TAG, "NoiseSuppressor enabled")
            } else {
                Logger.w(TAG, "NoiseSuppressor not available")
            }

            audioRecord?.startRecording()
            isRecording = true
            Logger.d(TAG, "Recording started (source=VOICE_COMMUNICATION)")
            return true
        } catch (securityException: SecurityException) {
            Logger.e(TAG, "Failed to start recording", securityException)
            return false
        } catch (illegalStateException: IllegalStateException) {
            Logger.e(TAG, "Failed to start recording", illegalStateException)
            return false
        }
    }

    /**
     * 读取一帧音频数据
     *
     * @return 音频数据，如果未录音返回空数组
     */
    fun read(): ByteArray {
        val record = audioRecord ?: return ByteArray(0)
        if (!isRecording) return ByteArray(0)

        val buffer = ByteArray(bufferSize)
        val readSize = record.read(buffer, 0, buffer.size)
        return if (readSize > 0) {
            buffer.copyOf(readSize)
        } else {
            ByteArray(0)
        }
    }

    /**
     * 读取 ShortArray 音频数据（用于流式 ASR）
     *
     * 参考 AsrService.processSamples() 直接读取 ShortArray 避免转换开销
     *
     * @param buffer 目标缓冲区
     * @param offset 偏移量
     * @param size 读取大小
     * @return 实际读取的样本数
     */
    fun readShortArray(buffer: ShortArray, offset: Int, size: Int): Int {
        val record = audioRecord ?: return 0
        if (!isRecording) return 0
        return record.read(buffer, offset, size)
    }

    /**
     * 读取指定时长的音频片段
     *
     * @param maxDurationMs 最大录制时长（毫秒）
     * @param silenceTimeoutMs 静音超时（毫秒），检测到持续静音则提前结束
     * @return 音频数据
     */
    fun readSegment(maxDurationMs: Int, silenceTimeoutMs: Int = 800): ByteArray {
        val record = audioRecord ?: return ByteArray(0)
        if (!isRecording) return ByteArray(0)

        val maxBytes = (SAMPLE_RATE * 2 * maxDurationMs / 1000)
        val output = java.io.ByteArrayOutputStream(maxBytes)
        val vadDetector = VadDetector(thresholdDb = 40f, minSpeechMs = 100)

        val startTime = System.currentTimeMillis()
        var lastSpeechTime = startTime
        val buffer = ByteArray(bufferSize)

        while (isRecording) {
            val readSize = record.read(buffer, 0, buffer.size)
            if (readSize > 0) {
                val chunk = buffer.copyOf(readSize)
                output.write(chunk)

                val isSpeech = vadDetector.process(chunk)
                val now = System.currentTimeMillis()
                if (isSpeech) {
                    lastSpeechTime = now
                }

                val elapsed = now - startTime
                val silenceDuration = now - lastSpeechTime

                if (elapsed >= maxDurationMs || silenceDuration >= silenceTimeoutMs) {
                    Logger.d(TAG, "Segment ended: elapsed=${elapsed}ms, silence=${silenceDuration}ms")
                    break
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * 停止录音
     */
    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (illegalStateException: IllegalStateException) {
            Logger.w(TAG, "Error stopping recorder", illegalStateException)
        }
        audioRecord = null
        Logger.d(TAG, "Recording stopped")
    }
}
