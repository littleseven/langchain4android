package com.mamba.picme.agent.core.inference.local.react.perception

import android.view.View
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compose 语义树提取器。
 * 通过 findViewTreeCompositionContext() 获取 Compose 语义节点。
 * 输出与 ViewHierarchyExtractor 一致的 JSON 格式。
 */
object ComposeSemanticsExtractor {

    private const val MAX_TEXT_LENGTH = 80
    private const val MAX_CHILDREN = 200

    /**
     * 从 Compose 根语义节点提取 UI 树 JSON。
     * 注：findViewTreeCompositionContext() 非公开 API，回退到 View 树。
     */
    fun extractFromComposeView(composeView: View): String? {
        return null
    }

    /**
     * 从 SemanticNode 提取 JSON。
     */
    fun extractNode(node: SemanticsNode, screenWidth: Int, screenHeight: Int): String {
        val root = JSONObject()
        try {
            visitNode(node, root, screenWidth, screenHeight, depth = 0)
        } catch (e: Exception) {
            root.put("error", "Compose semantics extraction failed: ${e.message}")
        }
        return root.toString(2)
    }

    private fun visitNode(
        node: SemanticsNode,
        out: JSONObject,
        screenWidth: Int,
        screenHeight: Int,
        depth: Int
    ) {
        if (depth > 30) {
            out.put("_truncated", true)
            return
        }

        // role / class
        out.put("class", "ComposeNode")

        // bounds
        val boundsInRoot = node.boundsInRoot
        val left = boundsInRoot.left.toInt()
        val top = boundsInRoot.top.toInt()
        val width = boundsInRoot.width.toInt()
        val height = boundsInRoot.height.toInt()

        val bounds = JSONObject()
        bounds.put("x", left)
        bounds.put("y", top)
        bounds.put("w", width)
        bounds.put("h", height)

        if (screenWidth > 0 && screenHeight > 0) {
            bounds.put("x_pct", String.format("%.1f", left * 100.0 / screenWidth))
            bounds.put("y_pct", String.format("%.1f", top * 100.0 / screenHeight))
        }
        out.put("bounds", bounds)

        // text
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        if (!text.isNullOrEmpty()) {
            out.put("text", if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "…" else text)
        }

        // content description
        val contentDesc = node.config.getOrNull(SemanticsProperties.ContentDescription)
        if (!contentDesc.isNullOrEmpty()) {
            out.put("content_desc", contentDesc.joinToString(" "))
        }

        // role
        val role = node.config.getOrNull(SemanticsProperties.Role)
        if (role != null) {
            out.put("role", role.toString())
        }

        // clickable
        val onClick = node.config.getOrNull(SemanticsActions.OnClick)
        if (onClick != null) {
            out.put("clickable", true)
        }

        // scrollable
        val horizontalScroll = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val verticalScroll = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if (horizontalScroll != null || verticalScroll != null) {
            out.put("scrollable", true)
        }

        // enabled
        val isEnabled = node.config.getOrNull(SemanticsProperties.Disabled) == null
        if (!isEnabled) {
            out.put("enabled", false)
        }

        // focused
        val isFocused = node.config.getOrNull(SemanticsProperties.Focused) != null
        if (isFocused) {
            out.put("focused", true)
        }

        // selected
        val isSelected = node.config.getOrNull(SemanticsProperties.Selected) != null
        if (isSelected) {
            out.put("selected", true)
        }

        // children
        val children = node.children
        if (children.isNotEmpty()) {
            val childrenArray = JSONArray()
            val childCount = minOf(children.size, MAX_CHILDREN)
            for (i in 0 until childCount) {
                val child = children[i]
                val childObj = JSONObject()
                visitNode(child, childObj, screenWidth, screenHeight, depth + 1)
                childrenArray.put(childObj)
            }
            if (childrenArray.length() > 0) {
                out.put("children", childrenArray)
            }
        }
    }
}
