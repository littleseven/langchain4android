package com.mamba.picme.agent.core.langchain4j

data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, JsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList()
)
