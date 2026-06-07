package com.picme.features.camera.voice

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.model.AiAgentCommand
import com.picme.agent.core.model.MediaType
import com.picme.domain.model.VoiceCommandMode
import com.picme.domain.usecase.AiAgentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音命令协调器
 *
 * 统一管理两种语音交互模式（Push-to-Talk / WakeWord）、
 * ASR 引擎调用、LLM 意图解析和命令分发。
 *
 * 参考 MnnLlmChat VoiceChatPresenter 的 Channel 串行任务处理：
 * - 使用 Channel 保证任务按序执行，避免并发问题
 * - 识别 → LLM 推理 → 命令执行 形成串行流水线
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
    private val scope: CoroutineScope,
    private val onTranscript: ((String) -> Unit)? = null,
    private val onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null,
    context: android.content.Context? = null
) {

    private val tag = "VoiceCommand"

    private val pushToTalkEngine = PushToTalkEngine(asrEngine, scope, context)
    private val wakeWordEngine = WakeWordEngine(asrEngine, scope, context)

    /**
     * 当前音频输入设备类型
     * 供 UI 层查询以显示耳机状态标记
     */
    val currentInputDevice: InputAudioDevice
        get() = pushToTalkEngine.currentInputDevice

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

    // 参考 VoiceChatPresenter: 使用 Channel 实现串行任务处理
    private val taskChannel = Channel<VoiceTask>(Channel.UNLIMITED)

    init {
        // 启动串行处理器，确保识别 → LLM → 命令执行按序进行
        scope.launch {
            taskChannel.consumeEach { task ->
                processTask(task)
            }
        }
    }

    /**
     * 语音任务密封类（参考 VoiceChatPresenter.SerialTask）
     */
    private sealed class VoiceTask {
        data class ProcessTranscript(val transcript: String) : VoiceTask()
    }

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
            enqueueTranscript(transcript)
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
     * @param processAsCommand 是否将识别结果送入内部 LLM 解析为命令。
     *                         默认为 true（相机页面语音命令场景）。
     *                         Chat 面板语音输入时应设为 false，由调用方自行处理 LLM。
     */
    fun startPushToTalk(
        onResult: (String) -> Unit,
        processAsCommand: Boolean = true
    ) {
        if (!asrEngine.isAvailable()) {
            Logger.w(tag, "ASR engine not available")
            onResult("")
            return
        }
        pushToTalkEngine.start { transcript ->
            onResult(transcript)
            if (processAsCommand && transcript.isNotBlank()) {
                enqueueTranscript(transcript)
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
     * 将识别文本加入串行处理队列
     * 参考 VoiceChatPresenter: 通过 Channel 保证按序处理
     */
    private fun enqueueTranscript(transcript: String) {
        if (transcript.isBlank()) {
            Logger.d(tag, "Empty transcript, skipping")
            return
        }
        Logger.i(tag, "Enqueuing transcript: $transcript")
        scope.launch {
            taskChannel.send(VoiceTask.ProcessTranscript(transcript))
        }
    }

    /**
     * 串行处理语音任务
     * 参考 VoiceChatPresenter.processTask()
     */
    private suspend fun processTask(task: VoiceTask) {
        when (task) {
            is VoiceTask.ProcessTranscript -> {
                processTranscriptInternal(task.transcript)
            }
        }
    }

    /**
     * 处理识别到的文本（内部实现，在串行处理器中调用）
     *
     * 1. 送入 AiAgentUseCase 进行 LLM 推理
     * 2. 如果解析为有效命令（非 TextReply），通过 onCommand 回调执行
     */
    private suspend fun processTranscriptInternal(transcript: String) {
        Logger.i(tag, "Processing transcript: $transcript")

        // 通知上层语音文本已识别，可用于添加到对话历史
        onTranscript?.invoke(transcript)

        val result = aiAgentUseCase.processInput(transcript, currentCameraState.toAiAgentSnapshot())

        // 通知上层 Agent 响应结果，可用于添加到对话历史
        onAgentResponse?.invoke(result)

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

    /**
     * 释放资源
     *
     * 关键修复：正确调用 SherpaMnnAsrEngine 的 release()，
     * 通过 ResourceManager 协调释放，避免 MNN 全局状态冲突。
     */
    fun release() {
        stopWakeWordListening()
        stopPushToTalk()
        taskChannel.close()

        // 修复：如果 ASR 是 SherpaMnnAsrEngine，调用其 release() 进行协调释放
        (asrEngine as? SherpaMnnAsrEngine)?.release()

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
