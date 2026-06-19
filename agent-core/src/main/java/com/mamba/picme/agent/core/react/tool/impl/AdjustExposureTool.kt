package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 调整曝光工具。
 * 将 adjust_exposure 工具调用转换为 AgentCommand.AdjustExposure，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class AdjustExposureTool : BaseUiTool() {

    override fun getName(): String = "adjust_exposure"

    override fun getDescription(): String = "调整曝光补偿，范围 -2 到 2"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("exposure", "integer", "曝光补偿 -2~2", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val exposure = (params["exposure"] as? Number)?.toInt()
            ?: return ToolResult.error("Missing required parameter: exposure")

        val clampedExposure = exposure.coerceIn(-2, 2)

        return CameraToolHelper.executeCameraCommand(
            method = "adjust_exposure",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "adjust_exposure")
                    put("params", JSONObject().apply {
                        put("exposure", clampedExposure)
                    })
                }.toString()
            },
            onSuccess = { "Exposure adjusted to $clampedExposure" },
            onError = { "Adjust exposure failed: $it" }
        )
    }
}
