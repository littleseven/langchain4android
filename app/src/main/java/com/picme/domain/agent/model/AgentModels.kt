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

// ─────────────────────────────────────────────────────────────────────────────
// 结构化可观测性事件（Agent First 原则：Structured Observability）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Agent 命令执行链路事件
 *
 * 用于全链路追踪：解析 → 路由 → 分发 → 执行 → 回调
 * 可被 AI 直接消费，实现自我诊断。
 */
data class AgentCommandEvent(
    val stage: CommandStage,
    val commandType: String,
    val commandAction: String? = null,
    val scene: String? = null,
    val success: Boolean,
    val message: String? = null,
    val errorType: String? = null,
    val durationMs: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class CommandStage {
        PARSE,      // 命令解析
        ROUTE,      // 路由决策
        DISPATCH,   //  capability 分发
        EXECUTE,    //  capability 执行
        CALLBACK,   // 回调触发
        FALLBACK    // 降级处理
    }

    companion object {
        fun parseSuccess(commandType: String, action: String? = null): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.PARSE,
                commandType = commandType,
                commandAction = action,
                success = true
            )

        fun parseFailure(rawInput: String, reason: String): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.PARSE,
                commandType = "unknown",
                success = false,
                message = rawInput,
                errorType = reason
            )

        fun dispatchSuccess(
            commandType: String,
            capability: String,
            scene: String
        ): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.DISPATCH,
                commandType = commandType,
                scene = scene,
                success = true,
                message = "dispatched to $capability"
            )

        fun dispatchFailure(
            commandType: String,
            scene: String,
            reason: String
        ): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.DISPATCH,
                commandType = commandType,
                scene = scene,
                success = false,
                errorType = reason
            )

        fun executeSuccess(commandType: String, capability: String): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.EXECUTE,
                commandType = commandType,
                success = true,
                message = "executed by $capability"
            )

        fun executeFailure(
            commandType: String,
            capability: String,
            reason: String
        ): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.EXECUTE,
                commandType = commandType,
                success = false,
                message = "failed in $capability",
                errorType = reason
            )

        fun callbackMissing(commandType: String, callbackName: String): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.CALLBACK,
                commandType = commandType,
                success = false,
                errorType = "CALLBACK_NOT_SET",
                message = "$callbackName callback is null"
            )
    }
}
