package com.mamba.picme.agent.core.api

interface StreamingChatResponseHandler {
    fun onPartialResponse(partialResponse: String)
    fun onCompleteResponse(completeResponse: LlmChatResponse)
    fun onError(error: Throwable)
}
