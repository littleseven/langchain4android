package com.mamba.picme.agent.core.react.tool.impl

import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 页面导航工具。
 * 将 navigate_to 工具调用转换为 AgentCommand.NavigateTo，
 * 通过 CapabilityRegistry 分发执行，实现零权限的页面跳转。
 */
class NavigateToTool : BaseUiTool() {

    override fun getName(): String = "navigate_to"

    override fun getDescription(): String = "导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("destination", "enum", "目标页面: camera|gallery|settings|debug", true)
    )

    @OptIn(DelicateCoroutinesApi::class)
    override fun execute(params: Map<String, Any>): ToolResult {
        val destination = params["destination"]?.toString()
            ?: return ToolResult.error("Missing required parameter: destination")

        val validDestinations = setOf("camera", "gallery", "settings", "debug")
        if (destination !in validDestinations) {
            return ToolResult.error("Invalid destination: '$destination'. Must be one of: ${validDestinations.joinToString()}")
        }

        return try {
            val registry = CapabilityRegistry.getInstance()
            val commandJson = JSONObject().apply {
                put("method", "navigate_to")
                put("params", JSONObject().apply {
                    put("destination", destination)
                })
            }.toString()

            val context = AgentContext(scene = AgentScene.CHAT)
            val command = LocalCommandParser.parseCommandByMethod(
                method = "navigate_to",
                json = commandJson,
                context = context,
                fallbackText = ""
            )

            // 使用 GlobalScope.future + get() 在同步 execute() 中调用挂起函数
            val deferred = GlobalScope.future {
                registry.dispatch(command, context, null)
            }
            val result = deferred.get(5, TimeUnit.SECONDS)

            result.fold(
                onSuccess = {
                    ToolResult.success("Navigated to $destination")
                },
                onFailure = { error ->
                    ToolResult.error("Navigation failed: ${error.message}")
                }
            )
        } catch (e: Exception) {
            ToolResult.error("Navigation error: ${e.message}")
        }
    }
}
