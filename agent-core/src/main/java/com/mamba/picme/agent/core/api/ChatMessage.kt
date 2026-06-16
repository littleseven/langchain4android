package com.mamba.picme.agent.core.api

sealed interface ChatMessage

data class SystemMessage(val text: String) : ChatMessage
data class UserMessage(val text: String) : ChatMessage
data class AiMessage(
    val text: String,
    val toolExecutionRequests: List<ToolExecutionRequest> = emptyList()
) : ChatMessage

data class ToolExecutionResultMessage(
    val toolExecutionRequest: ToolExecutionRequest,
    val text: String
) : ChatMessage
