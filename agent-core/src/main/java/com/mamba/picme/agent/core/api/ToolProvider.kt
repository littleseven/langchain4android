package com.mamba.picme.agent.core.api

import com.mamba.agent.agent.tool.ToolSpecification

interface ToolProvider {
    suspend fun getToolSpecifications(): List<ToolSpecification>
    suspend fun findExecutor(toolName: String): ToolExecutor?
}
