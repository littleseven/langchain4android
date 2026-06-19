package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 切换风格特效工具。
 * 将 switch_style 工具调用转换为 AgentCommand.SwitchStyle，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class SwitchStyleTool : BaseUiTool() {

    override fun getName(): String = "switch_style"

    override fun getDescription(): String = "切换艺术风格。可选值：NONE（无）、TOON（漫画）、SKETCH（素描）、POSTERIZE（海报）、EMBOSS（浮雕）、CROSSHATCH（交叉线）"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("style", "enum", "风格特效名称: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val style = params["style"]?.toString()
            ?: return ToolResult.error("Missing required parameter: style")

        val validStyles = setOf("NONE", "TOON", "SKETCH", "POSTERIZE", "EMBOSS", "CROSSHATCH")
        if (style.uppercase() !in validStyles) {
            return ToolResult.error("Invalid style: '$style'. Must be one of: ${validStyles.joinToString()}")
        }

        return CameraToolHelper.executeCameraCommand(
            method = "switch_style",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "switch_style")
                    put("params", JSONObject().apply {
                        put("style", style.uppercase())
                    })
                }.toString()
            },
            onSuccess = { "Style switched to $style" },
            onError = { "Switch style failed: $it" }
        )
    }
}
