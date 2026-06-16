package com.mamba.picme.agent.core.api

interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): String
}
