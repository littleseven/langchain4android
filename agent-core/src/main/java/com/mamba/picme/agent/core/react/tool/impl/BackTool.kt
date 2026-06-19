package com.mamba.picme.agent.core.react.tool.impl

import android.app.Activity
import androidx.activity.ComponentActivity
import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult

/**
 * 返回/后退工具。
 * 模拟系统返回键效果。
 * 通过 Activity.onBackPressed() 实现，零权限。
 */
class BackTool : BaseUiTool() {

    companion object {
        /** 当前 Activity 引用，由外部设置 */
        @JvmStatic
        var currentActivity: Activity? = null
    }

    override fun getName(): String = "go_back"

    override fun getDescription(): String = "返回上一页"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val activity = currentActivity
            ?: return ToolResult.error("No current activity reference available")

        return try {
            if (activity is ComponentActivity) {
                activity.runOnUiThread {
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            } else {
                activity.runOnUiThread {
                    @Suppress("DEPRECATION")
                    activity.onBackPressed()
                }
            }
            ToolResult.success("Navigated back")
        } catch (e: Exception) {
            ToolResult.error("Back navigation failed: ${e.message}")
        }
    }
}
