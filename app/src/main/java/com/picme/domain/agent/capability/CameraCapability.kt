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
 * 应用级单例，通过 delegate 模式与页面解耦：
 * - 在 Application.onCreate() 中注册一次，永不注销
 * - CameraScreen 通过绑定 delegate 提供实际执行逻辑
 * - 页面离开时解绑 delegate，Capability 仍然注册但返回不可用状态
 * - 支持跨页面指令排队：当 Camera 页面再次激活时自动执行待处理命令
 *
 * 仅在 CAMERA 场景可用。
 */
class CameraCapability : BaseCapability() {

    companion object {
        @Volatile
        private var instance: CameraCapability? = null

        fun getInstance(): CameraCapability {
            return instance ?: synchronized(this) {
                instance ?: CameraCapability().also { instance = it }
            }
        }
    }

    private val tag = "PicMe:CameraCapability"

    override val name: String = "camera"
    override val description: String =
        "控制相机拍摄、美颜参数、滤镜、风格、变焦、曝光、画幅比例、场景模式和摄像头翻转"

    /**
     * 相机操作委托接口
     *
     * CameraScreen 实现此接口并绑定到 Capability
     */
    interface Delegate {
        fun onAdjustBeauty(settings: com.picme.beauty.api.BeautySettings)
        fun onSwitchFilter(filterType: FilterType)
        fun onSwitchStyle(styleFilter: StyleFilter)
        fun onSwitchScene(sceneName: String)
        fun onSwitchRatio(ratio: String)
        fun onAdjustExposure(exposure: Int)
        fun onAdjustZoom(zoomRatio: Float)
        fun onFlipCamera()
        fun onCapturePhoto()
        fun onToggleRecording()
        fun onSwitchMode(mode: MediaType)
    }

    /**
     * 当前绑定的委托，null 表示相机页面未激活
     */
    @Volatile
    var delegate: Delegate? = null
        private set

    /**
     * 绑定委托（由 CameraScreen 调用）
     */
    fun bindDelegate(delegate: Delegate) {
        this.delegate = delegate
        Logger.i(tag, "Delegate bound")
    }

    /**
     * 解绑委托（由 CameraScreen onDispose 调用）
     */
    fun unbindDelegate() {
        this.delegate = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean {
        return delegate != null
    }

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
        "switch_mode"
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
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val d = delegate
            ?: return Result.success(
                AgentAction.Error("相机页面未激活，请先切换到相机页面")
            )

        return when (command) {
            is AgentCommand.AdjustBeauty -> {
                d.onAdjustBeauty(command.settings)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchFilter -> {
                d.onSwitchFilter(command.filterType)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchStyle -> {
                d.onSwitchStyle(command.styleFilter)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchScene -> {
                d.onSwitchScene(command.sceneName)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchRatio -> {
                d.onSwitchRatio(command.ratio)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.AdjustExposure -> {
                d.onAdjustExposure(command.exposure)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.AdjustZoom -> {
                d.onAdjustZoom(command.zoomRatio)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.FlipCamera -> {
                d.onFlipCamera()
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.CapturePhoto -> {
                d.onCapturePhoto()
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.ToggleRecording -> {
                d.onToggleRecording()
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.SwitchMode -> {
                d.onSwitchMode(command.mode)
                Result.success(AgentAction.Success(command))
            }

            else -> {
                Logger.w(tag, "Command not supported by CameraCapability: ${command::class.simpleName}")
                Result.success(AgentAction.Error("相机页面不支持此命令"))
            }
        }
    }
}
