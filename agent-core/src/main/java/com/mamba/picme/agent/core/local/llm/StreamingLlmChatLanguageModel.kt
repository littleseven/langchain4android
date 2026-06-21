package com.mamba.picme.agent.core.local.llm

/**
 * 流式 LLM 聊天语言模型接口
 *
 * 统一本地/远程模型的流式调用接口。
 */
interface StreamingLlmChatLanguageModel {
    fun chat(request: LlmChatRequest, handler: StreamingChatResponseHandler)
}
