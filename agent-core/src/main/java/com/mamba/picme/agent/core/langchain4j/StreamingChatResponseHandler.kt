package com.mamba.picme.agent.core.langchain4j

interface StreamingChatResponseHandler {
    fun onPartialResponse(partialResponse: String)
    fun onCompleteResponse(completeResponse: ChatResponse)
    fun onError(error: Throwable)
}
