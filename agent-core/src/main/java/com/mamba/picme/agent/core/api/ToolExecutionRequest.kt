package com.mamba.picme.agent.core.api

data class ToolExecutionRequest(
    val id: String,
    val name: String,
    val arguments: String
)
