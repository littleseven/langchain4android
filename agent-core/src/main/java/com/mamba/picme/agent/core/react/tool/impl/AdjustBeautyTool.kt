package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 调整美颜参数工具。
 * 将 adjust_beauty 工具调用转换为 AgentCommand.AdjustBeauty，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class AdjustBeautyTool : BaseUiTool() {

    override fun getName(): String = "adjust_beauty"

    override fun getDescription(): String = "调整美颜参数。支持磨皮、美白、瘦脸、大眼、唇色、腮红、眉毛。只传入需要调整的参数，未传入的参数保持不变。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("smoothing", "number", "磨皮程度 0~100", false),
        ToolParameter("whitening", "number", "美白程度 0~100", false),
        ToolParameter("slim_face", "number", "瘦脸 -50~50", false),
        ToolParameter("big_eyes", "number", "大眼 0~100", false),
        ToolParameter("lip_color", "number", "唇色 0~100", false),
        ToolParameter("blush", "number", "腮红 0~100", false),
        ToolParameter("eyebrow", "number", "眉毛 0~100", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val smoothing = (params["smoothing"] as? Number)?.toFloat() ?: 0f
        val whitening = (params["whitening"] as? Number)?.toFloat() ?: 0f
        val slimFace = (params["slim_face"] as? Number)?.toFloat() ?: 0f
        val bigEyes = (params["big_eyes"] as? Number)?.toFloat() ?: 0f
        val lipColor = (params["lip_color"] as? Number)?.toFloat() ?: 0f
        val blush = (params["blush"] as? Number)?.toFloat() ?: 0f
        val eyebrow = (params["eyebrow"] as? Number)?.toFloat() ?: 0f

        return CameraToolHelper.executeCameraCommand(
            method = "adjust_beauty",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "adjust_beauty")
                    put("params", JSONObject().apply {
                        put("smoothing", smoothing)
                        put("whitening", whitening)
                        put("slim_face", slimFace)
                        put("big_eyes", bigEyes)
                        put("lip_color", lipColor)
                        put("blush", blush)
                        put("eyebrow", eyebrow)
                    })
                }.toString()
            },
            onSuccess = { "Beauty adjusted" },
            onError = { "Adjust beauty failed: $it" }
        )
    }
}
