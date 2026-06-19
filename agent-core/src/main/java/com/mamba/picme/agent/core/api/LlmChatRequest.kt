package com.mamba.picme.agent.core.api

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.request.ToolChoice

/**
 * LLM 聊天请求
 *
 * @property messages 聊天消息列表（使用 langchain4j 标准消息类型）
 * @property toolSpecifications 工具规格列表（用于 Tool Calling）
 * @property temperature 温度参数，覆盖模型默认值（null 表示使用模型默认值）
 * @property maxTokens 最大输出 Token 数，覆盖模型默认值（null 表示使用模型默认值）
 * @property toolChoice 工具选择策略（null 表示默认 required）
 */
data class LlmChatRequest(
    val messages: List<ChatMessage>,
    val toolSpecifications: List<ToolSpecification> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val toolChoice: ToolChoice? = null
)
