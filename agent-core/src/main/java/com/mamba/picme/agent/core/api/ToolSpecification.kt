package com.mamba.picme.agent.core.api

data class ToolSpecification(
    val name: String,
    val description: String,
    val parameters: ToolParameters = ToolParameters()
)
