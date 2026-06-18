package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 拍照工具。
 * 将 capture 工具调用转换为 AgentCommand.CapturePhoto，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class CapturePhotoTool : BaseUiTool() {

    override fun getName(): String = "capture"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        return CameraToolHelper.executeCameraCommand(
            method = "capture",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "capture")
                    put("params", JSONObject())
                }.toString()
            },
            onSuccess = { "Photo captured" },
            onError = { "Capture failed: $it" }
        )
    }
}
