package com.mamba.picme.agent.core.react.tool

import android.view.WindowManager
import com.mamba.picme.agent.core.react.tool.impl.BackTool
import com.mamba.picme.agent.core.react.tool.impl.ClickTool
import com.mamba.picme.agent.core.react.tool.impl.FinishTool
import com.mamba.picme.agent.core.react.tool.impl.GetScreenInfoTool
import com.mamba.picme.agent.core.react.tool.impl.InputTextTool
import com.mamba.picme.agent.core.react.tool.impl.ScrollTool

/**
 * 应用内 UI Agent Tool 注册中心。
 * 所有工具在此注册，供 LangChain4jToolBridge 和 InAppAgentService 使用。
 */
object ToolRegistry {

    private val tools = LinkedHashMap<String, BaseUiTool>()

    fun getInstance(): ToolRegistry = this

    fun registerAllTools(windowManager: WindowManager) {
        tools.clear()
        register(GetScreenInfoTool(windowManager))
        register(ClickTool(windowManager))
        register(InputTextTool())
        register(ScrollTool())
        register(BackTool())
        register(FinishTool())
    }

    fun register(tool: BaseUiTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseUiTool? = tools[name]

    fun getAllTools(): List<BaseUiTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }
}
