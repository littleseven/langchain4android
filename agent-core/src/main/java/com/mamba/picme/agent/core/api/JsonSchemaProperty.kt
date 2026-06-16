package com.mamba.picme.agent.core.api

data class JsonSchemaProperty(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null,
    val items: JsonSchemaProperty? = null
)
