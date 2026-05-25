package com.picme.features.camera.voice

import com.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_DELAY_MS = 50L
private const val LOW_POWER_POLL_MS = 100L
private const val MAX_SEGMENT_DURATION_MS = 4000
private const val SEGMENT_SILENCE_TIMEOUT_MS = 800

/**
 * 唤醒词引擎
 *
 * 在后台持续监听音频，通过 VAD 检测语音活动，
 * 检测到语音后触发 ASR 识别，将结果通过回调返回。
 *
 * 注意：此引擎仅在相机预览可见时运行，页面退出自动停止。
 */
class WakeWordEngine(
    private val asrEngine: AsrEngine,
    private val scope: CoroutineScope
) {

    private val tag = "PicMe:WakeWord"
    private val audioRecorder = AudioRecorder()
    private val vadDetector = VadDetector(thresholdDb = 40f, minSpeechMs = 300)
    private var isRunning = false

    /**
     * 启动唤醒词监听
     *
     * @param onTranscript 识别到文本后的回调（在主线程）
     */
    fun start(onTranscript: (String) -> Unit) {
        if (isRunning) {
            Logger.w(tag, "Wake word engine already running")
            return
        }

        val started = audioRecorder.start()
        if (!started) {
            Logger.e(tag, "Failed to start audio recorder")
            return
        }

        isRunning = true
        vadDetector.reset()
        Logger.i(tag, "Wake word engine started")

        scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                val buffer = audioRecorder.read()
                if (buffer.isEmpty()) {
                    delay(POLL_DELAY_MS)
                    continue
                }

                val isSpeech = vadDetector.process(buffer)
                if (isSpeech) {
                    Logger.d(tag, "Speech detected, starting ASR")

                    val audioSegment = audioRecorder.readSegment(
                        maxDurationMs = MAX_SEGMENT_DURATION_MS,
                        silenceTimeoutMs = SEGMENT_SILENCE_TIMEOUT_MS
                    )

                    if (audioSegment.isNotEmpty()) {
                        val result = asrEngine.transcribe(audioSegment)
                        result.onSuccess { transcript ->
                            if (transcript.isNotBlank()) {
                                Logger.i(tag, "Wake word transcript: $transcript")
                                scope.launch(Dispatchers.Main) {
                                    onTranscript(transcript)
                                }
                            }
                        }.onFailure { error ->
                            Logger.e(tag, "ASR failed", error)
                        }
                    }

                    vadDetector.reset()
                }

                delay(LOW_POWER_POLL_MS)
            }

            audioRecorder.stop()
            Logger.i(tag, "Wake word engine stopped")
        }
    }

    /**
     * 停止唤醒词监听
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Logger.d(tag, "Wake word engine stopping...")
    }
}
