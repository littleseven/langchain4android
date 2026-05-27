package com.picme.features.camera.voice

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.model.AiAgentCommand
import com.picme.domain.model.MediaType
import com.picme.domain.model.VoiceCommandMode
import com.picme.domain.usecase.AiAgentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音命令协调器
 *
 * 统一管理两种语音交互模式（Push-to-Talk / WakeWord）、
 * ASR 引擎调用、LLM 意图解析和命令分发。
 *
 * @param context Application Context
 * @param asrEngine ASR 引擎（优先本地 MNN，回退系统）
 * @param aiAgentUseCase AI Agent 用例，负责 LLM 推理和命令解析
 * @param onCommand 识别到有效命令后的回调
 * @param scope CoroutineScope，用于异步任务
 */
class VoiceCommandCoordinator(
    private val asrEngine: AsrEngine,
    private val aiAgentUseCase: AiAgentUseCase,
    private val onCommand: (AiAgentCommand) -> Unit,
    private val scope: CoroutineScope
) {

    private val tag = "PicMe:VoiceCommand"

    private val pushToTalkEngine = PushToTalkEngine(asrEngine, scope)
    private val wakeWordEngine = WakeWordEngine(asrEngine, scope)

    /**
     * 当前语音命令模式
     */
    var mode: VoiceCommandMode = VoiceCommandMode.PUSH_TO_TALK
        set(value) {
            if (field == value) return
            // 模式切换时停止当前监听
            if (field == VoiceCommandMode.WAKE_WORD) {
                stopWakeWordListening()
            }
            field = value
            Logger.i(tag, "Voice command mode changed to: $value")
        }

    /**
     * 当前相机状态快照，用于构建 LLM 上下文
     */
    var currentCameraState: CameraStateSnapshot = CameraStateSnapshot()

    /**
     * 开始唤醒词监听
     *
     * 仅在 WAKE_WORD 模式下有效。
     */
    fun startWakeWordListening() {
        if (mode != VoiceCommandMode.WAKE_WORD) {
            Logger.d(tag, "Not in wake word mode, skipping")
            return
        }
        if (!asrEngine.isAvailable()) {
            Logger.w(tag, "ASR engine not available")
            return
        }
        wakeWordEngine.start { transcript ->
            processTranscript(transcript)
        }
    }

    /**
     * 停止唤醒词监听
     */
    fun stopWakeWordListening() {
        wakeWordEngine.stop()
    }

    /**
     * 开始按住说话录音
     *
     * @param onResult 识别结果回调（在主线程）
     */
    fun startPushToTalk(onResult: (String) -> Unit) {
        if (!asrEngine.isAvailable()) {
            Logger.w(tag, "ASR engine not available")
            onResult("")
            return
        }
        pushToTalkEngine.start { transcript ->
            onResult(transcript)
            if (transcript.isNotBlank()) {
                processTranscript(transcript)
            }
        }
    }

    /**
     * 停止按住说话录音
     */
    fun stopPushToTalk() {
        pushToTalkEngine.stop()
    }

    /**
     * 处理识别到的文本
     *
     * 1. 送入 AiAgentUseCase 进行 LLM 推理
     * 2. 如果解析为有效命令（非 TextReply），通过 onCommand 回调执行
     */
    private fun processTranscript(transcript: String) {
        if (transcript.isBlank()) {
            Logger.d(tag, "Empty transcript, skipping")
            return
        }

        Logger.i(tag, "Processing transcript: $transcript")

        scope.launch {
            val result = aiAgentUseCase.processInput(transcript, currentCameraState.toAiAgentSnapshot())
            result.onSuccess { command ->
                if (command is AiAgentCommand.TextReply) {
                    Logger.d(tag, "Text reply, no action: ${command.message}")
                } else {
                    Logger.i(tag, "Command detected: ${command.javaClass.simpleName}")
                    withContext(Dispatchers.Main) {
                        onCommand(command)
                    }
                }
            }.onFailure { error ->
                Logger.e(tag, "Failed to process voice command", error)
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopWakeWordListening()
        stopPushToTalk()
        Logger.d(tag, "VoiceCommandCoordinator released")
    }

    /**
     * 内部相机状态快照
     */
    data class CameraStateSnapshot(
        val beautySettings: BeautySettings = BeautySettings(),
        val filterType: FilterType = FilterType.NONE,
        val styleFilter: StyleFilter = StyleFilter.NONE,
        val zoomRatio: Float = 1f,
        val exposureCompensation: Int = 0,
        val captureMode: MediaType = MediaType.PHOTO,
        val isRecording: Boolean = false
    ) {
        fun toAiAgentSnapshot(): AiAgentUseCase.CameraStateSnapshot {
            return AiAgentUseCase.CameraStateSnapshot(
                beautySettings = beautySettings,
                filterType = filterType,
                styleFilter = styleFilter,
                zoomRatio = zoomRatio,
                exposureCompensation = exposureCompensation,
                captureMode = captureMode,
                isRecording = isRecording
            )
        }
    }
}
