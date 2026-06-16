package com.mamba.picme.agent.core.api

data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, JsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList()
)
