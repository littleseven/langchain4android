package com.mamba.picme.agent.core.api

interface StreamingChatLanguageModel {
    fun chat(request: ChatRequest, handler: StreamingChatResponseHandler)
}
