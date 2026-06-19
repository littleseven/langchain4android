package com.mamba.picme.agent.core.react.tool.impl

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult

/**
 * 滚动工具。
 * 在当前屏幕上向下/向上滚动。
 * 自动查找 RecyclerView 或 ScrollView 进行滚动。
 */
class ScrollTool : BaseUiTool() {

    override fun getName(): String = "scroll"

    override fun getDescription(): String = "在屏幕上滑动滚动。支持按方向（up/down/left/right）或坐标滑动"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("direction", "string", "Scroll direction: 'down' or 'up'", true),
        ToolParameter("distance", "string", "Scroll distance: 'page' (default) or 'small'", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val direction = params["direction"]?.toString()?.lowercase()
            ?: return ToolResult.error("Missing required parameter: direction (up/down)")

        if (direction !in listOf("up", "down")) {
            return ToolResult.error("Invalid direction: '$direction'. Must be 'up' or 'down'")
        }

        val isPage = params["distance"]?.toString() != "small"

        val rootView = GetScreenInfoTool.currentRootView
            ?: return ToolResult.error("No activity root view available")

        // 查找 RecyclerView 优先
        val recyclerView = findRecyclerView(rootView)
        if (recyclerView != null) {
            val distance = if (isPage) recyclerView.height else recyclerView.height / 3
            recyclerView.post {
                recyclerView.smoothScrollBy(0, if (direction == "down") distance else -distance)
            }
            return ToolResult.success("Scrolled $direction in RecyclerView")
        }

        // 查找嵌套滚动容器
        val scrollView = findScrollView(rootView)
        if (scrollView != null) {
            scrollView.post {
                val distance = if (isPage) scrollView.height else scrollView.height / 3
                scrollView.smoothScrollBy(0, if (direction == "down") distance else -distance)
            }
            return ToolResult.success("Scrolled $direction in ScrollView")
        }

        return ToolResult.error("No scrollable container found on current screen")
    }

    private fun findRecyclerView(root: android.view.View): RecyclerView? {
        if (root is RecyclerView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findRecyclerView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findScrollView(root: android.view.View): android.widget.ScrollView? {
        if (root is android.widget.ScrollView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findScrollView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
