package com.mamba.picme.agent.core.react.tool

import android.view.WindowManager
import com.mamba.picme.agent.core.react.tool.impl.AdjustBeautyTool
import com.mamba.picme.agent.core.react.tool.impl.AdjustExposureTool
import com.mamba.picme.agent.core.react.tool.impl.AdjustZoomTool
import com.mamba.picme.agent.core.react.tool.impl.BackTool
import com.mamba.picme.agent.core.react.tool.impl.CapturePhotoTool
import com.mamba.picme.agent.core.react.tool.impl.ClickTool
import com.mamba.picme.agent.core.react.tool.impl.FinishTool
import com.mamba.picme.agent.core.react.tool.impl.FlipCameraTool
import com.mamba.picme.agent.core.react.tool.impl.GetScreenInfoTool
import com.mamba.picme.agent.core.react.tool.impl.InputTextTool
import com.mamba.picme.agent.core.react.tool.impl.NavigateToTool
import com.mamba.picme.agent.core.react.tool.impl.ScrollTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchFilterTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchRatioTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchSceneTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchStyleTool
import com.mamba.picme.agent.core.react.tool.impl.ToggleRecordingTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchModeTool

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
        register(NavigateToTool())
        register(BackTool())
        register(FinishTool())
        // 相机控制工具（已注册，ReAct Agent 可直接调用）
        register(CapturePhotoTool())
        register(FlipCameraTool())
        register(ToggleRecordingTool())
        register(SwitchModeTool())
        register(AdjustBeautyTool())
        register(AdjustExposureTool())
        register(AdjustZoomTool())
        register(SwitchFilterTool())
        register(SwitchStyleTool())
        register(SwitchSceneTool())
        register(SwitchRatioTool())
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
