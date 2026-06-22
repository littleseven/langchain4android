package com.mamba.picme.agent.core.local.llm

interface StreamingChatResponseHandler {
    fun onPartialResponse(partialResponse: String)
    fun onCompleteResponse(completeResponse: LlmChatResponse)
    fun onError(error: Throwable)
}
