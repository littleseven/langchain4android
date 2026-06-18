package com.mamba.picme.domain.agent

import com.mamba.picme.agent.core.api.LlmChatLanguageModel
import com.mamba.picme.agent.core.api.LlmChatRequest
import com.mamba.picme.agent.core.api.LlmChatResponse
import com.mamba.picme.agent.core.api.ToolExecutionRequest
import com.mamba.picme.agent.core.api.ToolExecutor
import com.mamba.picme.agent.core.api.ToolProvider
import com.mamba.picme.agent.core.api.ToolSpecification
import com.mamba.picme.agent.core.runtime.tool.ToolOrchestrator
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.UserMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOrchestratorTest {

    @Test
    fun `tool loop executes tool and returns final response`() = runBlocking {
        val fakeTool = object : ToolExecutor {
            override suspend fun execute(request: ToolExecutionRequest): String = "done"
        }
        val fakeProvider = object : ToolProvider {
            override suspend fun getToolSpecifications(): List<ToolSpecification> {
                return listOf(ToolSpecification("testTool", "desc"))
            }

            override suspend fun findExecutor(toolName: String): ToolExecutor? {
                return if (toolName == "testTool") fakeTool else null
            }
        }

        val fakeModel = object : LlmChatLanguageModel {
            var callCount = 0
            override fun chat(request: LlmChatRequest): LlmChatResponse {
                callCount++
                return if (callCount == 1) {
                    LlmChatResponse(
                        aiMessage = AiMessage(
                            text = "",
                            toolExecutionRequests = listOf(
                                ToolExecutionRequest(id = "1", name = "testTool", arguments = "{}")
                            )
                        )
                    )
                } else {
                    LlmChatResponse(aiMessage = AiMessage(text = "final answer"))
                }
            }
        }

        val orchestrator = ToolOrchestrator(fakeModel, fakeProvider)
        val response = orchestrator.chat(LlmChatRequest(messages = listOf(UserMessage.from("hi"))))
        assertEquals("final answer", response.aiMessage.text())
    }
}