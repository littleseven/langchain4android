package com.mamba.picme.agent.core.api

import com.mamba.tool.ToolExecutionRequest

interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): String
}
