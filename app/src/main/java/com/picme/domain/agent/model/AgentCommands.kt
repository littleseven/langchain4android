package com.picme.domain.agent.model

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.model.MediaType
import com.picme.domain.agent.remote.ExecutionPlan

/**
 * Agent 命令 V2
 *
 * 扩展版本，支持：
 * - 相机控制（原有）
 * - Gallery 操作（新增）
 * - 设置控制（新增）
 * - 页面导航（新增）
 * - 照片编辑（新增）
 */
sealed class AgentCommand {

    // ==================== 相机命令 ====================

    /**
     * 调整美颜参数
     */
    data class AdjustBeauty(val settings: BeautySettings) : AgentCommand()

    /**
     * 切换滤镜
     */
    data class SwitchFilter(val filterType: FilterType) : AgentCommand()

    /**
     * 切换风格特效
     */
    data class SwitchStyle(val styleFilter: StyleFilter) : AgentCommand()

    /**
     * 切换场景模式
     */
    data class SwitchScene(val sceneName: String) : AgentCommand()

    /**
     * 切换画幅比例
     */
    data class SwitchRatio(val ratio: String) : AgentCommand()

    /**
     * 调整曝光
     */
    data class AdjustExposure(val exposure: Int) : AgentCommand()

    /**
     * 调整变焦
     */
    data class AdjustZoom(val zoomRatio: Float) : AgentCommand()

    /**
     * 翻转摄像头
     */
    data object FlipCamera : AgentCommand()

    /**
     * 拍摄照片
     */
    data object CapturePhoto : AgentCommand()

    /**
     * 开始/停止录像
     */
    data object ToggleRecording : AgentCommand()

    /**
     * 切换拍摄模式
     */
    data class SwitchMode(val mode: MediaType) : AgentCommand()

    // ==================== Gallery 命令 ====================

    /**
     * 查看指定媒体
     */
    data class ViewMedia(val mediaId: String? = null) : AgentCommand()

    /**
     * 删除媒体（可指定 ID 列表，空列表表示删除当前选中）
     */
    data class DeleteMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()

    /**
     * 分享媒体
     */
    data class ShareMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()

    /**
     * 选择/取消选择媒体
     */
    data class SelectMedia(val mediaId: String, val selected: Boolean) : AgentCommand()

    /**
     * 搜索媒体
     */
    data class SearchMedia(val query: String) : AgentCommand()

    /**
     * 切换视图模式
     */
    data class SwitchViewMode(val mode: String) : AgentCommand()

    /**
     * 收藏/取消收藏
     */
    data class FavoriteMedia(val mediaId: String, val favorite: Boolean) : AgentCommand()

    // ==================== 设置命令 ====================

    /**
     * 切换主题
     */
    data class ChangeTheme(val theme: String) : AgentCommand()

    /**
     * 切换语言
     */
    data class ChangeLanguage(val language: String) : AgentCommand()

    /**
     * 下载模型
     */
    data class DownloadModel(val modelId: String) : AgentCommand()

    /**
     * 切换人脸检测引擎
     */
    data class SwitchFaceEngine(val engine: String) : AgentCommand()

    /**
     * 切换开关设置
     */
    data class ToggleSetting(val settingKey: String, val enabled: Boolean) : AgentCommand()

    // ==================== 导航命令 ====================

    /**
     * 导航到指定页面
     */
    data class NavigateTo(val destination: String) : AgentCommand()

    /**
     * 返回上一页
     */
    data object GoBack : AgentCommand()

    // ==================== 编辑命令 ====================

    // ==================== 远程模式专用命令 ====================

    /**
     * 批量执行命令（L2 Batch Function Calling）
     *
     * 仅远程模式支持，将多个命令打包为一个批量执行单元。
     */
    data class BatchExecute(val commands: List<AgentCommand>) : AgentCommand()

    /**
     * 执行计划（L3 Plan-and-Execute）
     *
     * 仅远程模式支持，包含条件判断和多步骤编排。
     */
    data class ExecutePlan(val plan: ExecutionPlan) : AgentCommand()

    // ==================== 通用命令 ====================

    /**
     * 文本回复（聊天模式）
     */
    data class TextReply(val message: String) : AgentCommand()

    /**
     * 未知命令（LLM 输出无法解析时）
     */
    data class Unknown(val raw: String) : AgentCommand()

    /**
     * 执行错误
     */
    data class Error(val reason: String) : AgentCommand()

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
    }
}
