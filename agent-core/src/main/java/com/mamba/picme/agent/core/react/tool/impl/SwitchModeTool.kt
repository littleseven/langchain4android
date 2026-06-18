package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 切换拍摄模式工具。
 * 将 switch_mode 工具调用转换为 AgentCommand.SwitchMode，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class SwitchModeTool : BaseUiTool() {

    override fun getName(): String = "switch_mode"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("mode", "string", "拍摄模式: PHOTO|VIDEO|PRO|DOCUMENT", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mode = params["mode"]?.toString()
            ?: return ToolResult.error("Missing required parameter: mode")

        val validModes = setOf("PHOTO", "VIDEO", "PRO", "DOCUMENT")
        if (mode.uppercase() !in validModes) {
            return ToolResult.error("Invalid mode: '$mode'. Must be one of: ${validModes.joinToString()}")
        }

        return CameraToolHelper.executeCameraCommand(
            method = "switch_mode",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "switch_mode")
                    put("params", JSONObject().apply {
                        put("mode", mode.uppercase())
                    })
                }.toString()
            },
            onSuccess = { "Mode switched to $mode" },
            onError = { "Switch mode failed: $it" }
        )
    }
}
