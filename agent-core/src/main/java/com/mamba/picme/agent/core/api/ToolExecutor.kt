package com.mamba.picme.agent.core.api

import com.mamba.agent.agent.tool.ToolExecutionRequest

interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): String
}
