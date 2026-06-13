package com.mamba.picme.agent.core.langchain4j

interface ChatLanguageModel {
    fun chat(request: ChatRequest): ChatResponse
}
