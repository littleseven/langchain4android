package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 切换画幅比例工具。
 * 将 switch_ratio 工具调用转换为 AgentCommand.SwitchRatio，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class SwitchRatioTool : BaseUiTool() {

    override fun getName(): String = "switch_ratio"

    override fun getDescription(): String = "切换画面比例。可选值：4:3、16:9、full（全屏）"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("ratio", "string", "画幅比例: 4:3|16:9|full", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ratio = params["ratio"]?.toString()
            ?: return ToolResult.error("Missing required parameter: ratio")

        val validRatios = setOf("4:3", "16:9", "full")
        if (ratio !in validRatios) {
            return ToolResult.error("Invalid ratio: '$ratio'. Must be one of: ${validRatios.joinToString()}")
        }

        return CameraToolHelper.executeCameraCommand(
            method = "switch_ratio",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "switch_ratio")
                    put("params", JSONObject().apply {
                        put("ratio", ratio)
                    })
                }.toString()
            },
            onSuccess = { "Ratio switched to $ratio" },
            onError = { "Switch ratio failed: $it" }
        )
    }
}
