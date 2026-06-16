package com.mamba.picme.agent.core.api

interface ToolProvider {
    suspend fun getToolSpecifications(): List<ToolSpecification>
    suspend fun findExecutor(toolName: String): ToolExecutor?
}
