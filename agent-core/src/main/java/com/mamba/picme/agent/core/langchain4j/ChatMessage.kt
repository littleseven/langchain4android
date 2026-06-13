package com.mamba.picme.agent.core.langchain4j

sealed interface ChatMessage

data class SystemMessage(val text: String) : ChatMessage
data class UserMessage(val text: String) : ChatMessage
data class AiMessage(val text: String) : ChatMessage
