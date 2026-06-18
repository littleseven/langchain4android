package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult

/**
 * 完成任务工具。
 * Agent 调用此工具表示任务已达成。
 */
class FinishTool : BaseUiTool() {

    override fun getName(): String = "finish"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("summary", "string", "Summary of what was accomplished", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val summary = params["summary"]?.toString()
            ?: return ToolResult.error("Missing required parameter: summary")
        return ToolResult.success(summary)
    }
}
