package com.mamba.picme.agent.core.langchain4j

interface ToolProvider {
    suspend fun getToolSpecifications(): List<ToolSpecification>
    suspend fun findExecutor(toolName: String): ToolExecutor?
}
