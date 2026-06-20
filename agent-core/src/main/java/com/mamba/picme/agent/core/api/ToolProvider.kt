package com.mamba.picme.agent.core.api

import com.mamba.tool.ToolSpecification

interface ToolProvider {
    suspend fun getToolSpecifications(): List<ToolSpecification>
    suspend fun findExecutor(toolName: String): ToolExecutor?
}
