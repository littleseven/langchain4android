package com.mamba.picme.agent.core.react.tool.impl

import android.widget.EditText
import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolResult

/**
 * 输入文本工具。
 * 在当前焦点 EditText 中输入文字。
 * 使用 editText.setText() + editText.setSelection() 实现，零权限。
 */
class InputTextTool : BaseUiTool() {

    override fun getName(): String = "input_text"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("text", "string", "The text to input", true),
        ToolParameter("clear_first", "boolean", "Whether to clear existing text first (default: true)", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = params["text"]?.toString()
            ?: return ToolResult.error("Missing required parameter: text")

        val clearFirst = optionalBoolean(params, "clear_first", true)

        val rootView = GetScreenInfoTool.currentRootView
            ?: return ToolResult.error("No activity root view available")

        // 查找当前有焦点的 EditText
        val focusedEditText = findFocusedEditText(rootView)

        if (focusedEditText != null) {
            focusedEditText.post {
                if (clearFirst) {
                    focusedEditText.setText("")
                }
                focusedEditText.append(text)
                focusedEditText.setSelection(focusedEditText.text?.length ?: 0)
            }
            return ToolResult.success("Input text '$text' into focused EditText")
        }

        // 如果没有焦点 EditText，查找第一个可见的 EditText
        val firstEditText = findFirstEditText(rootView)
        if (firstEditText != null) {
            firstEditText.post {
                firstEditText.requestFocus()
                if (clearFirst) {
                    firstEditText.setText("")
                }
                firstEditText.append(text)
                firstEditText.setSelection(firstEditText.text?.length ?: 0)
            }
            return ToolResult.success("Input text '$text' into first available EditText")
        }

        return ToolResult.error("No EditText found on current screen")
    }

    private fun findFocusedEditText(root: android.view.View): EditText? {
        if (root is EditText && root.isFocused) {
            return root
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFocusedEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFirstEditText(root: android.view.View): EditText? {
        if (root is EditText && root.visibility == android.view.View.VISIBLE) {
            return root
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
