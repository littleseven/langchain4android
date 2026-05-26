package com.picme.domain.agent.capability

import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.model.MediaType

/**
 * 相机控制 Capability
 *
 * 将 Agent 命令映射为相机操作回调。
 * 实际执行通过注入的回调函数完成，保持 Domain 层无 Android/UI 依赖。
 */
class CameraCapability(
    private val onAdjustBeauty: ((com.picme.beauty.api.BeautySettings) -> Unit)? = null,
    private val onSwitchFilter: ((FilterType) -> Unit)? = null,
    private val onSwitchStyle: ((StyleFilter) -> Unit)? = null,
    private val onSwitchScene: ((String) -> Unit)? = null,
    private val onSwitchRatio: ((String) -> Unit)? = null,
    private val onAdjustExposure: ((Int) -> Unit)? = null,
    private val onAdjustZoom: ((Float) -> Unit)? = null,
    private val onFlipCamera: (() -> Unit)? = null,
    private val onCapturePhoto: (() -> Unit)? = null,
    private val onToggleRecording: (() -> Unit)? = null,
    private val onSwitchMode: ((MediaType) -> Unit)? = null
) : Capability {

    private val tag = "PicMe:CameraCapability"

    override val name: String = "camera"
    override val description: String =
        "控制相机拍摄、美颜参数、滤镜、风格、变焦、曝光、画幅比例、场景模式和摄像头翻转"

    override fun supportedCommands(): List<String> = listOf(
        "adjust_beauty",
        "switch_filter",
        "switch_style",
        "switch_scene",
        "switch_ratio",
        "adjust_exposure",
        "adjust_zoom",
        "flip_camera",
        "capture",
        "toggle_recording",
        "switch_mode",
        "text_reply"
    )

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.AdjustBeauty -> {
                onAdjustBeauty?.invoke(command.settings)
                    ?: Logger.w(tag, "onAdjustBeauty callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchFilter -> {
                onSwitchFilter?.invoke(command.filterType)
                    ?: Logger.w(tag, "onSwitchFilter callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchStyle -> {
                onSwitchStyle?.invoke(command.styleFilter)
                    ?: Logger.w(tag, "onSwitchStyle callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchScene -> {
                onSwitchScene?.invoke(command.sceneName)
                    ?: Logger.w(tag, "onSwitchScene callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchRatio -> {
                onSwitchRatio?.invoke(command.ratio)
                    ?: Logger.w(tag, "onSwitchRatio callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.AdjustExposure -> {
                onAdjustExposure?.invoke(command.exposure)
                    ?: Logger.w(tag, "onAdjustExposure callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.AdjustZoom -> {
                onAdjustZoom?.invoke(command.zoomRatio)
                    ?: Logger.w(tag, "onAdjustZoom callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.FlipCamera -> {
                onFlipCamera?.invoke()
                    ?: Logger.w(tag, "onFlipCamera callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.CapturePhoto -> {
                onCapturePhoto?.invoke()
                    ?: Logger.w(tag, "onCapturePhoto callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.ToggleRecording -> {
                onToggleRecording?.invoke()
                    ?: Logger.w(tag, "onToggleRecording callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchMode -> {
                onSwitchMode?.invoke(command.mode)
                    ?: Logger.w(tag, "onSwitchMode callback not set")
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.TextReply -> {
                Result.success(AgentAction.TextReply(command.message))
            }

            is AgentCommand.Unknown -> {
                Result.success(AgentAction.TextReply("收到你的消息了，但没理解具体意图，请再描述一下~"))
            }

            is AgentCommand.Error -> {
                Result.success(AgentAction.Error(command.reason))
            }
        }
    }
}
