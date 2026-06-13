package com.mamba.picme.agent.core.runtime.tool

data class ToolCallingConfig(
    val mode: ToolCallingMode = ToolCallingMode.OPENAI_TOOLS
)
