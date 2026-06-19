package com.mamba.picme.agent.core.api

import dev.langchain4j.agent.tool.ToolSpecification

interface ToolProvider {
    suspend fun getToolSpecifications(): List<ToolSpecification>
    suspend fun findExecutor(toolName: String): ToolExecutor?
}
