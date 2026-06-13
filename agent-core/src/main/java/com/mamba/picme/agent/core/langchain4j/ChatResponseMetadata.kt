package com.mamba.picme.agent.core.langchain4j

data class ChatResponseMetadata(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val prefillTimeMs: Long = 0,
    val decodeTimeMs: Long = 0,
    val prefillSpeed: Float = 0f,
    val decodeSpeed: Float = 0f
)
