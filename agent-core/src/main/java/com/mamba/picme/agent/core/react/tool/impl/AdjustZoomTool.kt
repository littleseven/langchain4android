package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 调整变焦工具。
 * 将 adjust_zoom 工具调用转换为 AgentCommand.AdjustZoom，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class AdjustZoomTool : BaseUiTool() {

    override fun getName(): String = "adjust_zoom"

    override fun getDescription(): String = "调整变焦倍数，最小 0.5x"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("zoom", "number", "变焦比例 0.5~10.0", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val zoom = (params["zoom"] as? Number)?.toFloat()
            ?: return ToolResult.error("Missing required parameter: zoom")

        val clampedZoom = zoom.coerceIn(0.5f, 10.0f)

        return CameraToolHelper.executeCameraCommand(
            method = "adjust_zoom",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "adjust_zoom")
                    put("params", JSONObject().apply {
                        put("zoom", clampedZoom)
                    })
                }.toString()
            },
            onSuccess = { "Zoom adjusted to $clampedZoom" },
            onError = { "Adjust zoom failed: $it" }
        )
    }
}
