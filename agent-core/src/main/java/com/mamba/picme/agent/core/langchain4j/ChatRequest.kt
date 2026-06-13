package com.mamba.picme.agent.core.langchain4j

data class ChatRequest(
    val messages: List<ChatMessage>,
    val toolSpecifications: List<ToolSpecification> = emptyList()
)
