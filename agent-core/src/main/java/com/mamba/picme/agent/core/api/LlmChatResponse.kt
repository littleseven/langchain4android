package com.mamba.picme.agent.core.api

import com.mamba.agent.data.message.AiMessage

/**
 * LLM 聊天响应
 *
 * @property aiMessage AI 回复消息（使用 langchain4j 标准消息类型）
 * @property metadata 响应元数据（包含性能指标等 PicMe 特有信息）
 */
data class LlmChatResponse(
    val aiMessage: AiMessage,
    val metadata: ChatResponseMetadata? = null
)
