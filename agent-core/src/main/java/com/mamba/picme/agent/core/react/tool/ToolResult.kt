package com.mamba.picme.agent.core.react.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?
) {
    companion object {
        fun success(data: String): ToolResult = ToolResult(true, data, null)
        fun error(error: String): ToolResult = ToolResult(false, null, error)
    }

    override fun toString(): String = if (isSuccess) {
        "ToolResult{success=true, data='$data'}"
    } else {
        "ToolResult{success=false, error='$error'}"
    }
}
