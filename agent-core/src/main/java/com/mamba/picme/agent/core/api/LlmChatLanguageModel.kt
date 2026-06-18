package com.mamba.picme.agent.core.api

/**
 * LLM 聊天语言模型接口
 *
 * 统一本地/远程模型的同步调用接口。
 */
interface LlmChatLanguageModel {
    fun chat(request: LlmChatRequest): LlmChatResponse
}
