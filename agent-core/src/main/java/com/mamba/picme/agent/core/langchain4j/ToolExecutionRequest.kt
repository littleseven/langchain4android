package com.mamba.picme.agent.core.langchain4j

data class ToolExecutionRequest(
    val id: String,
    val name: String,
    val arguments: String
)
