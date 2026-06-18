package com.mamba.picme.agent.core.react.tool.impl

import android.view.WindowManager
import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult

/**
 * 点击工具。
 * 支持绝对坐标点击 (x, y) 和按文本查找点击 (text)。
 * 使用 view.performClick() 实现，零权限。
 */
class ClickTool(
    private val windowManager: WindowManager
) : BaseUiTool() {

    override fun getName(): String = "click"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("x", "integer", "X coordinate to tap (use with y, mutually exclusive with text)", false),
        ToolParameter("y", "integer", "Y coordinate to tap (use with x, mutually exclusive with text)", false),
        ToolParameter("text", "string", "Click element by visible text (mutually exclusive with x/y)", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = params["text"]?.toString()

        if (text != null) {
            // 按文本查找并点击
            val rootView = GetScreenInfoTool.currentRootView ?:
                return ToolResult.error("No activity root view available")
            return clickByText(rootView, text)
        }

        val x = params["x"]?.toString()?.toIntOrNull()
        val y = params["y"]?.toString()?.toIntOrNull()

        if (x == null || y == null) {
            return ToolResult.error("Either provide (x, y) coordinates or text parameter")
        }

        // 坐标校验
        val validationError = validateCoordinates(x, y, windowManager)
        if (validationError != null) {
            return ToolResult.error(validationError)
        }

        // 通过已保存的 rootView 查找对应坐标的 View 并执行 performClick
        val rootView = GetScreenInfoTool.currentRootView ?:
            return ToolResult.error("No activity root view available")

        return try {
            val targetView = findViewAtPosition(rootView, x, y)
            if (targetView != null && targetView.isClickable) {
                targetView.performClick()
                ToolResult.success("Clicked at ($x, $y) on ${targetView.javaClass.simpleName}")
            } else if (targetView != null) {
                // 非 clickable 的 view，尝试找到可点击的父 view
                var parent = targetView.parent
                while (parent is android.view.View) {
                    if (parent.isClickable) {
                        parent.performClick()
                        return ToolResult.success("Clicked parent at ($x, $y) on ${parent.javaClass.simpleName}")
                    }
                    parent = parent.parent
                }
                // fallback: 直接 dispatch touch event
                dispatchTap(targetView, x, y)
                ToolResult.success("Dispatched tap at ($x, $y) on ${targetView.javaClass.simpleName}")
            } else {
                ToolResult.error("No clickable view found at ($x, $y)")
            }
        } catch (e: Exception) {
            ToolResult.error("Click failed: ${e.message}")
        }
    }

    private fun clickByText(rootView: android.view.View, text: String): ToolResult {
        val found = findViewByText(rootView, text)
        if (found != null) {
            if (found.isClickable) {
                found.performClick()
                return ToolResult.success("Clicked element with text: '$text'")
            }
            // 尝试 parent
            var parent = found.parent
            while (parent is android.view.View) {
                if (parent.isClickable) {
                    parent.performClick()
                    return ToolResult.success("Clicked parent of text '$text'")
                }
                parent = parent.parent
            }
            // fallback: 坐标点击
            val location = IntArray(2)
            found.getLocationOnScreen(location)
            dispatchTap(found, location[0] + found.width / 2, location[1] + found.height / 2)
            return ToolResult.success("Dispatched tap on text '$text' at center coordinate")
        }
        return ToolResult.error("No view found with text containing: '$text'")
    }

    private fun findViewAtPosition(root: android.view.View, x: Int, y: Int): android.view.View? {
        if (root is android.view.ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val location = IntArray(2)
                child.getLocationOnScreen(location)
                if (x >= location[0] && x <= location[0] + child.width &&
                    y >= location[1] && y <= location[1] + child.height) {
                    val found = findViewAtPosition(child, x, y)
                    if (found != null) return found
                    return child
                }
            }
        }
        return if (root.visibility == android.view.View.VISIBLE) root else null
    }

    private fun findViewByText(root: android.view.View, text: String): android.view.View? {
        if (root is android.widget.TextView) {
            val viewText = root.text?.toString() ?: ""
            if (viewText.contains(text, ignoreCase = true)) {
                return root
            }
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByText(root.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun dispatchTap(view: android.view.View, x: Int, y: Int) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val event = android.view.MotionEvent.obtain(
            downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()

        val upTime = android.os.SystemClock.uptimeMillis()
        val upEvent = android.view.MotionEvent.obtain(
            downTime, upTime, android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        view.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }
}
