package com.mamba.picme.agent.core.langchain4j

data class ChatResponse(
    val aiMessage: AiMessage,
    val metadata: ChatResponseMetadata? = null
)
