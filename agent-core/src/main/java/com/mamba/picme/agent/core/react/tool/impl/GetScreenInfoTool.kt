package com.mamba.picme.agent.core.react.tool.impl

import android.view.WindowManager
import com.mamba.picme.agent.core.react.perception.ViewHierarchyExtractor
import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult

/**
 * 获取当前屏幕的 UI 层级树。
 * 遍历当前 Activity 的 decorView rootView，输出精简的 XML/JSON 树。
 * 输出体积 2–20 KB，包含 class/id/text/bounds/clickable 等属性。
 */
class GetScreenInfoTool(
    private val windowManager: WindowManager
) : BaseUiTool() {

    companion object {
        const val SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__"

        /**
         * 应用内持有的当前 Activity rootView 引用。
         * 需要在 Activity.onCreate/onResume 时设置。
         */
        @JvmStatic
        var currentRootView: android.view.View? = null

        /** 屏幕尺寸缓存 */
        private var screenWidth = 0
        private var screenHeight = 0
    }

    override fun getName(): String = "get_screen_info"

    override fun getDescription(): String = "获取当前屏幕的 UI 层级树信息，包含所有可见元素的坐标、文本、可点击状态等"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val rootView = currentRootView
        if (rootView == null) {
            return ToolResult.error("No activity root view available. Ensure currentRootView is set.")
        }

        // 获取屏幕尺寸
        if (screenWidth <= 0 || screenHeight <= 0) {
            val size = getScreenSize(windowManager)
            screenWidth = size[0]
            screenHeight = size[1]
        }

        return try {
            val tree = ViewHierarchyExtractor.extract(rootView, screenWidth, screenHeight)
            ToolResult.success(tree)
        } catch (e: Exception) {
            ToolResult.error("Failed to extract screen info: ${e.message}")
        }
    }
}
