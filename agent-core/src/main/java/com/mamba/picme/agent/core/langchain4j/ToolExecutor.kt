package com.mamba.picme.agent.core.langchain4j

interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): String
}
