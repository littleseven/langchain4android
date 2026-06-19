package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import org.json.JSONObject

/**
 * 切换滤镜工具。
 * 将 switch_filter 工具调用转换为 AgentCommand.SwitchFilter，
 * 通过 CapabilityRegistry 分发执行。
 * 如果当前不在相机页，会自动导航到相机页后执行。
 */
class SwitchFilterTool : BaseUiTool() {

    override fun getName(): String = "switch_filter"

    override fun getDescription(): String = "切换相机滤镜。可选值：NONE（无）、LEICA_CLASSIC（徕卡经典）、LEICA_VIBRANT（徕卡鲜艳）、LEICA_BW（徕卡黑白）、FILM_GOLD（胶片金）、FILM_FUJI（胶片富士）、VINTAGE（复古）、COOL（冷色）、WARM（暖色）"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("filter", "enum", "滤镜名称: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val filter = params["filter"]?.toString()
            ?: return ToolResult.error("Missing required parameter: filter")

        val validFilters = setOf(
            "NONE", "LEICA_CLASSIC", "LEICA_VIBRANT", "LEICA_BW",
            "FILM_GOLD", "FILM_FUJI", "VINTAGE", "COOL", "WARM"
        )
        if (filter.uppercase() !in validFilters) {
            return ToolResult.error("Invalid filter: '$filter'. Must be one of: ${validFilters.joinToString()}")
        }

        return CameraToolHelper.executeCameraCommand(
            method = "switch_filter",
            params = params,
            buildCommandJson = {
                JSONObject().apply {
                    put("method", "switch_filter")
                    put("params", JSONObject().apply {
                        put("filter", filter.uppercase())
                    })
                }.toString()
            },
            onSuccess = { "Filter switched to $filter" },
            onError = { "Switch filter failed: $it" }
        )
    }
}
