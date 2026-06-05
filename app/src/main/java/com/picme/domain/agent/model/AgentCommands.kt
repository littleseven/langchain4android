package com.picme.domain.agent.model

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.model.MediaType
import com.picme.domain.agent.remote.ExecutionPlan

/**
 * Agent 命令 V2 —— 精简 JSON 风格
 *
 * 每个命令携带唯一 commandId（32位自增整型），支持请求-响应关联。
 * 扩展版本，支持：
 * - 相机控制（原有）
 * - Gallery 操作（新增）
 * - 设置控制（新增）
 * - 页面导航（新增）
 * - 照片编辑（新增）
 */
sealed class AgentCommand {

    /**
     * 命令唯一标识（32位自增整型）
     * 用于请求-响应关联和全链路追踪。
     */
    abstract val commandId: Int

    // ==================== 相机命令 ====================

    /**
     * 调整美颜参数
     */
    data class AdjustBeauty(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val settings: BeautySettings
    ) : AgentCommand()

    /**
     * 切换滤镜
     */
    data class SwitchFilter(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val filterType: FilterType
    ) : AgentCommand()

    /**
     * 切换风格特效
     */
    data class SwitchStyle(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val styleFilter: StyleFilter
    ) : AgentCommand()

    /**
     * 切换场景模式
     */
    data class SwitchScene(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sceneName: String
    ) : AgentCommand()

    /**
     * 切换画幅比例
     */
    data class SwitchRatio(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val ratio: String
    ) : AgentCommand()

    /**
     * 调整曝光
     */
    data class AdjustExposure(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val exposure: Int
    ) : AgentCommand()

    /**
     * 调整变焦
     */
    data class AdjustZoom(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val zoomRatio: Float
    ) : AgentCommand()

    /**
     * 翻转摄像头
     */
    data class FlipCamera(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    /**
     * 拍摄照片
     */
    data class CapturePhoto(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    /**
     * 开始/停止录像
     */
    data class ToggleRecording(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    /**
     * 切换拍摄模式
     */
    data class SwitchMode(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mode: MediaType
    ) : AgentCommand()

    // ==================== Gallery 命令 ====================

    /**
     * 查看指定媒体
     */
    data class ViewMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaId: String? = null
    ) : AgentCommand()

    /**
     * 删除媒体（可指定 ID 列表，空列表表示删除当前选中）
     */
    data class DeleteMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaIds: List<String> = emptyList()
    ) : AgentCommand()

    /**
     * 分享媒体
     */
    data class ShareMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaIds: List<String> = emptyList()
    ) : AgentCommand()

    /**
     * 选择/取消选择媒体
     */
    data class SelectMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaId: String,
        val selected: Boolean
    ) : AgentCommand()

    /**
     * 搜索媒体
     */
    data class SearchMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val query: String
    ) : AgentCommand()

    /**
     * 切换视图模式
     */
    data class SwitchViewMode(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mode: String
    ) : AgentCommand()

    /**
     * 收藏/取消收藏
     */
    data class FavoriteMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaId: String,
        val favorite: Boolean
    ) : AgentCommand()

    // ==================== 设置命令 ====================

    /**
     * 切换主题
     */
    data class ChangeTheme(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val theme: String
    ) : AgentCommand()

    /**
     * 切换语言
     */
    data class ChangeLanguage(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val language: String
    ) : AgentCommand()

    /**
     * 下载模型
     */
    data class DownloadModel(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val modelId: String
    ) : AgentCommand()

    /**
     * 切换人脸检测引擎
     */
    data class SwitchFaceEngine(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val engine: String
    ) : AgentCommand()

    /**
     * 切换开关设置
     */
    data class ToggleSetting(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val settingKey: String,
        val enabled: Boolean
    ) : AgentCommand()

    // ==================== 导航命令 ====================

    /**
     * 导航到指定页面
     */
    data class NavigateTo(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val destination: String
    ) : AgentCommand()

    /**
     * 返回上一页
     */
    data class GoBack(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    // ==================== 编辑命令 ====================

    // ==================== 远程模式专用命令 ====================

    /**
     * 批量执行命令（L2 Batch Function Calling）
     *
     * 数组中的命令按顺序执行，每个子命令独立返回响应，最终汇总为响应数组。
     *
     * @property commands 子命令列表
     * @property atomic 是否原子模式（true 时任一失败触发全部回滚）
     */
    data class BatchExecute(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val commands: List<AgentCommand>,
        val atomic: Boolean = false
    ) : AgentCommand()

    /**
     * 执行计划（L3 Plan-and-Execute）
     *
     * 仅远程模式支持，包含条件判断和多步骤编排。
     */
    data class ExecutePlan(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val plan: ExecutionPlan
    ) : AgentCommand()

    // ==================== 通用命令 ====================

    /**
     * 文本回复（聊天模式）
     */
    data class TextReply(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val message: String
    ) : AgentCommand()

    /**
     * 未知命令（LLM 输出无法解析时）
     */
    data class Unknown(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val raw: String
    ) : AgentCommand()

    /**
     * 执行错误
     */
    data class Error(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val reason: String
    ) : AgentCommand()

    companion object {
        /**
         * 获取命令的 action 名称（用于 JSON 序列化）
         */
        fun getActionName(command: AgentCommand): String = when (command) {
            is AdjustBeauty -> "adjust_beauty"
            is SwitchFilter -> "switch_filter"
            is SwitchStyle -> "switch_style"
            is SwitchScene -> "switch_scene"
            is SwitchRatio -> "switch_ratio"
            is AdjustExposure -> "adjust_exposure"
            is AdjustZoom -> "adjust_zoom"
            is FlipCamera -> "flip_camera"
            is CapturePhoto -> "capture"
            is ToggleRecording -> "toggle_recording"
            is SwitchMode -> "switch_mode"
            is ViewMedia -> "view_media"
            is DeleteMedia -> "delete_media"
            is ShareMedia -> "share_media"
            is SelectMedia -> "select_media"
            is SearchMedia -> "search_media"
            is SwitchViewMode -> "switch_view_mode"
            is FavoriteMedia -> "favorite_media"
            is ChangeTheme -> "change_theme"
            is ChangeLanguage -> "change_language"
            is DownloadModel -> "download_model"
            is SwitchFaceEngine -> "switch_face_engine"
            is ToggleSetting -> "toggle_setting"
            is NavigateTo -> "navigate_to"
            is GoBack -> "go_back"
            is BatchExecute -> "batch_execute"
            is ExecutePlan -> "execute_plan"
            is TextReply -> "text_reply"
            is Unknown -> "unknown"
            is Error -> "error"
        }

        /**
         * 获取命令的 commandId
         */
        fun getCommandId(command: AgentCommand): Int = command.commandId
    }
}
