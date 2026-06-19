package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 切换场景模式工具。
 * 将 switch_scene 工具调用转换为 AgentCommand.SwitchScene，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class SwitchSceneTool : BaseUiTool() {

    override fun getName(): String = "switch_scene"

    override fun getDescription(): String = "切换场景模式。可选值：night（夜景）、moon（月亮）、none（普通）"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("scene", "string", "场景模式: night|moon|none", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val scene = params["scene"]?.toString()
            ?: return ToolResult.error("Missing required parameter: scene")

        val validScenes = setOf("night", "moon", "none")
        if (scene.lowercase() !in validScenes) {
            return ToolResult.error("Invalid scene: '$scene'. Must be one of: ${validScenes.joinToString()}")
        }

        return CameraToolHelper.executeCameraCommand(
            method = "switch_scene",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "switch_scene")
                    put("params", JSONObject().apply {
                        put("scene", scene.lowercase())
                    })
                }.toString()
            },
            onSuccess = { "Scene switched to $scene" },
            onError = { "Switch scene failed: $it" }
        )
    }
}
