package com.picme.domain.agent.capability

import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.core.common.Logger
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.PageContext
import com.picme.domain.agent.model.SceneManager
import com.picme.domain.model.MediaType

/**
 * 相机控制 Capability
 *
 * 将 Agent 命令映射为相机操作回调。
 * 实际执行通过注入的回调函数完成，保持 Domain 层无 Android/UI 依赖。
 * 仅在 CAMERA 场景可用。
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
) : BaseCapability() {

    private val tag = "PicMe:CameraCapability"

    override val name: String = "camera"
    override val description: String =
        "控制相机拍摄、美颜参数、滤镜、风格、变焦、曝光、画幅比例、场景模式和摄像头翻转"

    override fun activeScenes(): List<SceneManager.Scene> {
        return listOf(SceneManager.Scene.CAMERA)
    }

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

    override fun getCommandDescription(command: String): String = when (command) {
        "adjust_beauty" -> "调整美颜参数，参数: smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow"
        "switch_filter" -> "切换滤镜，参数: filter (NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM)"
        "switch_style" -> "切换风格，参数: style (NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH)"
        "switch_scene" -> "切换场景模式，参数: scene (night|moon|none)"
        "switch_ratio" -> "切换画幅比例，参数: ratio (4:3|16:9|full)"
        "adjust_exposure" -> "调整曝光，参数: exposure (-2~2)"
        "adjust_zoom" -> "调整变焦，参数: zoom (0.5~10.0)"
        "flip_camera" -> "翻转前后摄像头"
        "capture" -> "拍照"
        "toggle_recording" -> "开始/停止录像"
        "switch_mode" -> "切换拍摄模式，参数: mode (PHOTO|VIDEO|PORTRAIT|PRO|DOCUMENT)"
        "text_reply" -> "文本回复"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
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

            else -> {
                Logger.w(tag, "Command not supported by CameraCapability: ${command::class.simpleName}")
                Result.success(AgentAction.Error("相机页面不支持此命令"))
            }
        }
    }
}
