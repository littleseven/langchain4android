package com.mamba.picme.agent.core.langchain4j

data class ToolSpecification(
    val name: String,
    val description: String,
    val parameters: ToolParameters = ToolParameters()
)
