package com.mamba.picme.agent.core.api

data class ChatResponse(
    val aiMessage: AiMessage,
    val metadata: ChatResponseMetadata? = null
)
