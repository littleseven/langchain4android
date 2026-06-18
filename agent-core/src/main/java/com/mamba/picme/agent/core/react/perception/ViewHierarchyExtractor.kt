package com.mamba.picme.agent.core.react.perception

import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * View 系统 UI 层级树提取器。
 * 从 Activity 的 decorView 递归遍历，输出精简 JSON 树。
 * 移除默认属性、截断长文本，输出体积 2–20 KB。
 */
object ViewHierarchyExtractor {

    private const val MAX_TEXT_LENGTH = 80
    private const val MAX_CHILDREN = 200

    /**
     * 从根 View 提取 UI 树 JSON。
     * @param rootView 根 View（通常是 activity.window.decorView.rootView）
     * @param screenWidth 屏幕宽度像素
     * @param screenHeight 屏幕高度像素
     * @return UI 树 JSON 字符串
     */
    fun extract(rootView: View, screenWidth: Int, screenHeight: Int): String {
        val root = JSONObject()
        try {
            visitNode(rootView, root, screenWidth, screenHeight, depth = 0)
        } catch (e: Exception) {
            root.put("error", "View tree extraction failed: ${e.message}")
        }
        return root.toString(2)
    }

    private fun visitNode(
        view: View,
        out: JSONObject,
        screenWidth: Int,
        screenHeight: Int,
        depth: Int
    ) {
        if (depth > 30) {
            out.put("_truncated", true)
            return
        }

        // class 名
        out.put("class", view.javaClass.simpleName)

        // id
        val id = view.id
        if (id != View.NO_ID) {
            try {
                val resourceName = view.resources.getResourceEntryName(id)
                out.put("id", resourceName)
            } catch (_: Exception) {
                out.put("id", "0x${Integer.toHexString(id)}")
            }
        }

        // visibility
        if (view.visibility != View.VISIBLE) {
            out.put("visibility", when (view.visibility) {
                View.INVISIBLE -> "invisible"
                View.GONE -> "gone"
                else -> "visible"
            })
        }

        // bounds (relative to screen, percent)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + view.width
        val bottom = top + view.height
        val bounds = JSONObject()
        bounds.put("x", left)
        bounds.put("y", top)
        bounds.put("w", view.width)
        bounds.put("h", view.height)

        // 相对坐标（百分比）
        if (screenWidth > 0 && screenHeight > 0) {
            bounds.put("x_pct", String.format("%.1f", left * 100.0 / screenWidth))
            bounds.put("y_pct", String.format("%.1f", top * 100.0 / screenHeight))
        }
        out.put("bounds", bounds)

        // text
        when (view) {
            is TextView -> {
                val text = view.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    out.put("text", if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "…" else text)
                }
                if (view.contentDescription?.isNotEmpty() == true) {
                    out.put("content_desc", view.contentDescription)
                }
            }
            else -> {
                if (view.contentDescription?.isNotEmpty() == true) {
                    out.put("content_desc", view.contentDescription)
                }
            }
        }

        // input type
        if (view is EditText) {
            out.put("input_type", "text")
            val hint = view.hint?.toString()?.trim()
            if (!hint.isNullOrEmpty()) {
                out.put("hint", hint)
            }
        }

        // clickable / enabled
        if (view.isClickable) {
            out.put("clickable", true)
        }
        if (!view.isEnabled) {
            out.put("enabled", false)
        }
        if (view.isFocusable) {
            out.put("focusable", true)
        }
        if (view.isSelected) {
            out.put("selected", true)
        }
        if (view is Checkable && view.isChecked) {
            out.put("checked", true)
        }

        // scrollable (ViewGroup)
        if (view is ViewGroup) {
            val canScroll = view.canScrollVertically(1) || view.canScrollVertically(-1)
            if (canScroll) {
                out.put("scrollable", true)
            }
        }

        // children
        if (view is ViewGroup && view.childCount > 0) {
            val children = JSONArray()
            val childCount = minOf(view.childCount, MAX_CHILDREN)
            for (i in 0 until childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.GONE) continue
                val childObj = JSONObject()
                visitNode(child, childObj, screenWidth, screenHeight, depth + 1)
                children.put(childObj)
            }
            if (children.length() > 0) {
                out.put("children", children)
            }
        }
    }
}
