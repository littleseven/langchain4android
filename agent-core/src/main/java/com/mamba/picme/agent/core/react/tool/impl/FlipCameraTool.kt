package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 翻转摄像头工具。
 * 将 flip_camera 工具调用转换为 AgentCommand.FlipCamera，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class FlipCameraTool : BaseUiTool() {

    override fun getName(): String = "flip_camera"

    override fun getDescription(): String = "切换前后摄像头"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        return CameraToolHelper.executeCameraCommand(
            method = "flip_camera",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "flip_camera")
                    put("params", JSONObject())
                }.toString()
            },
            onSuccess = { "Camera flipped" },
            onError = { "Flip camera failed: $it" }
        )
    }
}
