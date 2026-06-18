package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 开始/停止录像工具。
 * 将 toggle_recording 工具调用转换为 AgentCommand.ToggleRecording，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class ToggleRecordingTool : BaseUiTool() {

    override fun getName(): String = "toggle_recording"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        return CameraToolHelper.executeCameraCommand(
            method = "toggle_recording",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "toggle_recording")
                    put("params", JSONObject())
                }.toString()
            },
            onSuccess = { "Recording toggled" },
            onError = { "Toggle recording failed: $it" }
        )
    }
}
