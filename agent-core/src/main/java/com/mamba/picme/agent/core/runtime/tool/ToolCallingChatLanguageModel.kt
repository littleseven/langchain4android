package com.mamba.picme.agent.core.runtime.tool

import com.mamba.picme.agent.core.langchain4j.ChatLanguageModel
import com.mamba.picme.agent.core.langchain4j.ChatRequest
import com.mamba.picme.agent.core.langchain4j.ChatResponse
import com.mamba.picme.agent.core.langchain4j.SystemMessage

class ToolCallingChatLanguageModel(
    private val delegate: ChatLanguageModel,
    private val config: ToolCallingConfig = ToolCallingConfig()
) : ChatLanguageModel {

    override fun chat(request: ChatRequest): ChatResponse {
        val effectiveRequest = if (request.toolSpecifications.isEmpty()) {
            request
        } else {
            injectToolPrompt(request)
        }

        val response = delegate.chat(effectiveRequest)
        val requests = ToolCallingOutputParser.parse(response.aiMessage.text, config)
        return if (requests.isEmpty()) {
            response
        } else {
            response.copy(
                aiMessage = response.aiMessage.copy(
                    toolExecutionRequests = requests
                )
            )
        }
    }

    private fun injectToolPrompt(request: ChatRequest): ChatRequest {
        val toolSection = ToolPromptBuilder.buildToolSection(request.toolSpecifications, config)
        val updatedMessages = request.messages.map { message ->
            if (message is SystemMessage) {
                SystemMessage(message.text + toolSection)
            } else {
                message
            }
        }
        return request.copy(messages = updatedMessages)
    }
}
