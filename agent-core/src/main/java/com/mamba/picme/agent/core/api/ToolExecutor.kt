package com.mamba.picme.agent.core.api

import dev.langchain4j.agent.tool.ToolExecutionRequest

interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): String
}
