package com.mamba.picme.agent.core.react.tool

import android.util.DisplayMetrics
import android.view.WindowManager

abstract class BaseUiTool {

    companion object {
        /** wait_after 参数的最大值（毫秒） */
        private const val MAX_WAIT_AFTER_MS = 10000L

        /**
         * 所有工具共用的 wait_after 参数定义。
         */
        val WAIT_AFTER_PARAM = ToolParameter(
            "wait_after",
            "integer",
            "Optional: milliseconds to wait after this action completes (e.g. 2000 for page load). Default 0 (no wait).",
            false
        )
    }

    abstract fun getName(): String
    abstract fun getDescription(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: Map<String, Any>): ToolResult

    /**
     * 返回工具参数列表 + wait_after 通用参数。
     */
    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        if (getName() !in listOf("finish", "get_screen_info", "wait")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    /**
     * 执行工具并处理 wait_after 等待。
     */
    fun executeWithWaitAfter(params: Map<String, Any>): ToolResult {
        val result = execute(params)
        if (result.isSuccess) {
            val waitMs = optionalLong(params, "wait_after", 0L)
            if (waitMs in 1..MAX_WAIT_AFTER_MS) {
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return result
    }

    // === Parameter helpers ===

    protected fun requireString(params: Map<String, Any>, key: String): String {
        return params[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    protected fun requireInt(params: Map<String, Any>, key: String): Int {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun optionalInt(params: Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun optionalLong(params: Map<String, Any>, key: String, defaultValue: Long): Long {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalString(params: Map<String, Any>, key: String, defaultValue: String): String {
        return params[key]?.toString() ?: defaultValue
    }

    protected fun optionalBoolean(params: Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBoolean()
        }
    }

    /**
     * 通过 WindowManager 获取屏幕尺寸 [width, height]。
     */
    protected fun getScreenSize(wm: WindowManager): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    /**
     * 校验坐标是否在屏幕范围内，超出则返回错误信息，合法返回 null。
     */
    protected fun validateCoordinates(x: Int, y: Int, wm: WindowManager): String? {
        val size = getScreenSize(wm)
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        }
        return null
    }
}
