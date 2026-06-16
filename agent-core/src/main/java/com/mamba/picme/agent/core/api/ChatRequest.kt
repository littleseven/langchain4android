package com.mamba.picme.agent.core.api

/**
 * 聊天请求
 *
 * @property messages 聊天消息列表
 * @property toolSpecifications 工具规格列表（用于 Tool Calling）
 * @property temperature 温度参数，覆盖模型默认值（null 表示使用模型默认值）
 * @property maxTokens 最大输出 Token 数，覆盖模型默认值（null 表示使用模型默认值）
 */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val toolSpecifications: List<ToolSpecification> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null
)
