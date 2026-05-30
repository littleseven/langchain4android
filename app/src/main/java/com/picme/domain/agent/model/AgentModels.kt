package com.picme.domain.agent.model

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.model.MediaType

/**
 * Agent 运行上下文
 *
 * 描述当前用户所处的场景和设备状态，用于构建 system prompt 和路由命令。
 *
 * @property scene 当前场景
 * @property beautySettings 当前美颜设置
 * @property filterType 当前滤镜
 * @property styleFilter 当前风格
 * @property zoomRatio 当前变焦
 * @property exposureCompensation 当前曝光
 * @property captureMode 当前拍摄模式
 * @property isRecording 是否正在录制
 * @property memorySessionId 记忆会话 ID（用于隔离不同场景的对话历史）
 */
data class AgentContext(
    val scene: AgentScene,
    val beautySettings: BeautySettings = BeautySettings(),
    val filterType: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val zoomRatio: Float = 1f,
    val exposureCompensation: Int = 0,
    val captureMode: MediaType = MediaType.PHOTO,
    val isRecording: Boolean = false,
    val memorySessionId: String = scene.name.lowercase()
)

/**
 * Agent 场景枚举
 */
enum class AgentScene {
    CAMERA,
    GALLERY,
    PHOTO_EDIT,
    SETTINGS
}

/**
 * 对话消息
 *
 * @property role 消息角色
 * @property content 消息内容
 * @property timestamp 时间戳（毫秒）
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT
}

/**
 * Agent 执行结果（Sealed Class）
 */
sealed class AgentAction {

    /**
     * 命令执行成功
     */
    data class Success(val command: AgentCommand) : AgentAction()

    /**
     * 文本回复（聊天模式）
     */
    data class TextReply(val message: String) : AgentAction()

    /**
     * 执行失败
     */
    data class Error(val message: String) : AgentAction()
}
