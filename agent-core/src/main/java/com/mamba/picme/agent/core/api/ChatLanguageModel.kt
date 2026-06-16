package com.mamba.picme.agent.core.api

interface ChatLanguageModel {
    fun chat(request: ChatRequest): ChatResponse
}
