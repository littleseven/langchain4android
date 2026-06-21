package com.mamba.picme.agent.core.api

/**
 * 流式聊天结果
 *
 * @property fullResponse 完整的响应文本
 * @property metrics 性能指标
 * @property commands 从响应中解析出的命令列表
 */
data class StreamChatResult(
    val fullResponse: String,
    val metrics: StreamMetrics? = null,
    val commands: List<com.mamba.picme.agent.core.api.command.AgentCommand> = emptyList()
)

/**
 * 流式性能指标
 *
 * @property latencyMs 端到端延迟（毫秒）
 * @property promptTokens 输入 Token 数
 * @property completionTokens 输出 Token 数
 */
data class StreamMetrics(
    val latencyMs: Long,
    val promptTokens: Long?,
    val completionTokens: Long?
)
