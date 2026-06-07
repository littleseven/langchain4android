package com.picme.domain.model

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter

/**
 * AI Agent 解析后的相机控制命令
 *
 * 将自然语言意图映射为结构化的相机/美颜操作。
 */
sealed class AiAgentCommand {

    /**
     * 调整美颜参数
     */
    data class AdjustBeauty(
        val settings: BeautySettings
    ) : AiAgentCommand()

    /**
     * 切换滤镜
     */
    data class SwitchFilter(
        val filterType: FilterType
    ) : AiAgentCommand()

    /**
     * 切换风格特效
     */
    data class SwitchStyle(
        val styleFilter: StyleFilter
    ) : AiAgentCommand()

    /**
     * 切换场景模式
     */
    data class SwitchScene(
        val sceneName: String
    ) : AiAgentCommand()

    /**
     * 切换画幅比例
     */
    data class SwitchRatio(
        val ratio: String
    ) : AiAgentCommand()

    /**
     * 调整曝光
     */
    data class AdjustExposure(
        val exposure: Int
    ) : AiAgentCommand()

    /**
     * 调整变焦
     */
    data class AdjustZoom(
        val zoomRatio: Float
    ) : AiAgentCommand()

    /**
     * 切换摄像头
     */
    object FlipCamera : AiAgentCommand()

    /**
     * 拍摄照片
     */
    object CapturePhoto : AiAgentCommand()

    /**
     * 开始/停止录像
     */
    object ToggleRecording : AiAgentCommand()

    /**
     * 延迟等待（通用原语）
     *
     * 按指定毫秒数等待，可与其他命令组合实现延迟执行效果。
     * 例如：BatchExecute([Delay(3000), CapturePhoto]) 实现 3 秒后拍照。
     *
     * @property delayMs 延迟毫秒数
     */
    data class Delay(
        val delayMs: Long
    ) : AiAgentCommand()

    /**
     * 切换拍摄模式
     */
    data class SwitchMode(
        val mode: MediaType
    ) : AiAgentCommand()

    /**
     * 纯文本回复（无法映射为具体操作时的友好提示）
     */
    data class TextReply(
        val message: String
    ) : AiAgentCommand()

    /**
     * 导航到指定页面
     */
    data class NavigateTo(
        val destination: String
    ) : AiAgentCommand()

    /**
     * 返回上一页
     */
    object GoBack : AiAgentCommand()

    /**
     * 批量执行命令（远程模式 L2）
     *
     * 包含多个独立命令，按顺序依次执行。
     */
    data class BatchExecute(
        val commands: List<AiAgentCommand>
    ) : AiAgentCommand()
}
