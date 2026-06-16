package com.mamba.picme.agent.core.runtime.tool

import com.mamba.picme.agent.core.api.ChatLanguageModel
import com.mamba.picme.agent.core.api.ChatRequest
import com.mamba.picme.agent.core.api.ChatResponse
import com.mamba.picme.agent.core.api.ToolExecutionResultMessage
import com.mamba.picme.agent.core.api.ToolProvider
import kotlinx.coroutines.runBlocking

class ToolOrchestrator(
    private val model: ChatLanguageModel,
    private val toolProvider: ToolProvider,
    private val maxIterations: Int = 10
) : ChatLanguageModel {

    override fun chat(request: ChatRequest): ChatResponse {
        var currentRequest = request
        repeat(maxIterations) {
            val response = model.chat(currentRequest)
            val requests = response.aiMessage.toolExecutionRequests
            if (requests.isEmpty()) return response

            val resultMessages = requests.map { req ->
                val result = runBlocking {
                    try {
                        toolProvider.findExecutor(req.name)
                            ?.execute(req)
                            ?: "Error: tool '${req.name}' not found"
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                }
                ToolExecutionResultMessage(req, result)
            }

            currentRequest = ChatRequest(
                messages = currentRequest.messages + response.aiMessage + resultMessages,
                toolSpecifications = currentRequest.toolSpecifications
            )
        }
        throw ToolExecutionException("Tool calling exceeded $maxIterations iterations")
    }
}
