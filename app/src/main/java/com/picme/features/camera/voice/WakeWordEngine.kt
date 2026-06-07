package com.picme.features.camera.voice

import com.picme.core.common.Logger
import com.picme.agent.core.voice.AsrEngine
import com.picme.agent.core.voice.AudioRecorder
import com.picme.agent.core.voice.VadDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_DELAY_MS = 30L
private const val LOW_POWER_POLL_MS = 150L
private const val MAX_SEGMENT_DURATION_MS = 4000
private const val SEGMENT_SILENCE_TIMEOUT_MS = 1500
private const val WAKE_COOLDOWN_MS = 1200L

/**
 * 唤醒词
 * 用户说出唤醒词后，后续文本才会被当作指令处理。
 *
 * 注意：ASR 可能输出同音字（如"小蜜"代替"小觅"），
 * 因此维护一组常见变体以提高召回率。
 */
private val WAKE_WORD_VARIANTS = setOf(
    "小觅",  // 标准唤醒词
    "小蜜",  // 同音：xiǎo mì，ASR 最常见误识
    "小秘",  // 同音：xiǎo mì
    "小米",  // 近音：xiǎo mǐ（声调偏差）
    "小咪",  // 近音：xiǎo mī
    "小哔",  // 近音
)

/**
 * 唤醒词引擎
 *
 * 在后台持续监听音频，通过 VAD 检测语音活动，
 * 检测到语音后触发 ASR 识别，检查是否包含唤醒词"小觅"，
 * 仅当包含唤醒词时才将指令文本通过回调返回。
 *
 * 注意：此引擎仅在相机预览可见时运行，页面退出自动停止。
 */
class WakeWordEngine(
    private val asrEngine: AsrEngine,
    private val scope: CoroutineScope,
    context: android.content.Context? = null
) {

    private val tag = "WakeWord"
    private val audioRecorder = AudioRecorder(context)
    // 提高灵敏度配置：
    // thresholdDb: 30f → 25f（更低阈值，捕获更轻声细语）
    // minSpeechMs: 100ms → 80ms（更快触发，减少漏检）
    private val vadDetector = VadDetector(thresholdDb = 25f, minSpeechMs = 80)
    private var isRunning = false
    private var lastWakeTime = 0L

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
                    val now = System.currentTimeMillis()
                    // 冷却期检查：避免短时间内重复触发
                    if (now - lastWakeTime < WAKE_COOLDOWN_MS) {
                        Logger.d(tag, "Speech detected but in cooldown, skipped")
                        vadDetector.reset()
                        delay(LOW_POWER_POLL_MS)
                        continue
                    }

                    Logger.d(tag, "Speech detected, starting ASR")

                    val audioSegment = audioRecorder.readSegment(
                        maxDurationMs = MAX_SEGMENT_DURATION_MS,
                        silenceTimeoutMs = SEGMENT_SILENCE_TIMEOUT_MS
                    )

                    if (audioSegment.isNotEmpty()) {
                        val result = asrEngine.transcribe(audioSegment)
                        result.onSuccess { transcript ->
                            if (transcript.isNotBlank()) {
                                val matchedVariant = findMatchedWakeWord(transcript)
                                if (matchedVariant != null) {
                                    val command = stripWakeWord(transcript, matchedVariant)
                                    Logger.i(tag, "Wake word matched: '$matchedVariant', command: '$command' (raw: '$transcript')")
                                    lastWakeTime = System.currentTimeMillis()
                                    scope.launch(Dispatchers.Main) {
                                        onTranscript(command)
                                    }
                                } else {
                                    Logger.d(tag, "No wake word variant found in: '$transcript', ignored")
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
     *
     * 直接调用 audioRecorder.stop() 确保 read/readSegment 立即退出，
     * 避免与 start() 的竞态条件导致 AudioRecord 被重复释放。
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        audioRecorder.stop()
        Logger.d(tag, "Wake word engine stopping...")
    }

    /**
     * 从转录文本中查找匹配的唤醒词变体
     *
     * @param transcript ASR 原始转录文本
     * @return 匹配到的唤醒词变体，未匹配返回 null
     */
    internal fun findMatchedWakeWord(transcript: String): String? {
        return WAKE_WORD_VARIANTS.firstOrNull { variant ->
            transcript.contains(variant)
        }
    }

    /**
     * 从转录文本中移除唤醒词（含任意变体），提取实际指令
     *
     * 例如：
     * - "小觅拍张照" → "拍张照"
     * - "小蜜拍张照" → "拍张照"（ASR 将"小觅"误识为"小蜜"）
     * - "小米调高美颜" → "调高美颜"（ASR 将"小觅"误识为"小米"）
     * - "拍张照小觅" → "拍张照"
     * - "小觅小觅拍张照" → "拍张照"
     *
     * @param transcript ASR 原始转录文本
     * @param matchedVariant 实际匹配到的唤醒词变体
     * @return 移除唤醒词后的指令文本
     */
    internal fun stripWakeWord(transcript: String, matchedVariant: String = "小觅"): String {
        return transcript.replace(matchedVariant, "").trim()
    }
}
