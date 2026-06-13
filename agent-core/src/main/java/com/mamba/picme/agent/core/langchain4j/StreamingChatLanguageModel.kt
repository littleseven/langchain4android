package com.mamba.picme.agent.core.langchain4j

interface StreamingChatLanguageModel {
    fun chat(request: ChatRequest, handler: StreamingChatResponseHandler)
}
