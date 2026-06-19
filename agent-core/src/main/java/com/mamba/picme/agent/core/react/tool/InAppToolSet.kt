package com.mamba.picme.agent.core.react.tool

import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.react.perception.ViewHierarchyExtractor
import com.mamba.picme.agent.core.react.tool.impl.BackTool
import com.mamba.picme.agent.core.react.tool.impl.GetScreenInfoTool
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 应用内 Agent 工具集（@Tool 注解方式）
 *
 * 使用 langchain4j 的 @Tool 注解声明所有工具，框架自动提取 ToolSpecification。
 * 替代原有的 BaseUiTool 手动注册体系。
 *
 * 工具执行逻辑复用原有实现，通过全局状态（currentRootView/currentActivity）
 * 和 CameraToolHelper/CapabilityRegistry 完成实际操作。
 */
class InAppToolSet(
    private val windowManager: WindowManager
) {

    private val tag = "InAppToolSet"

    // ==================== 相机核心命令 ====================

    @Tool(name = "capture", value = ["拍照并保存到相册"])
    fun capture(): String {
        return executeCameraCommand("capture", emptyMap(), "Photo captured", "Capture failed")
    }

    @Tool(name = "toggle_recording", value = ["切换录像状态（开始或停止录像）"])
    fun toggleRecording(): String {
        return executeCameraCommand("toggle_recording", emptyMap(), "Recording toggled", "Toggle recording failed")
    }

    @Tool(name = "flip_camera", value = ["切换前后摄像头"])
    fun flipCamera(): String {
        return executeCameraCommand("flip_camera", emptyMap(), "Camera flipped", "Flip camera failed")
    }

    // ==================== 美颜调节 ====================

    @Tool(
        name = "adjust_beauty",
        value = ["调整美颜参数。支持磨皮、美白、瘦脸、大眼、唇色、腮红、眉毛。只传入需要调整的参数，未传入的参数保持不变。"]
    )
    fun adjustBeauty(
        @P(name = "smoothing", value = "磨皮强度，范围 0-100，默认不调整") smoothing: Int? = null,
        @P(name = "whitening", value = "美白强度，范围 0-100，默认不调整") whitening: Int? = null,
        @P(name = "slim_face", value = "瘦脸强度，范围 -50 到 50，默认不调整") slimFace: Int? = null,
        @P(name = "big_eyes", value = "大眼强度，范围 0-100，默认不调整") bigEyes: Int? = null,
        @P(name = "lip_color", value = "唇色强度，范围 0-100，默认不调整") lipColor: Int? = null,
        @P(name = "blush", value = "腮红强度，范围 0-100，默认不调整") blush: Int? = null,
        @P(name = "eyebrow", value = "眉毛强度，范围 0-100，默认不调整") eyebrow: Int? = null
    ): String {
        val params = mutableMapOf<String, Any>()
        smoothing?.let { params["smoothing"] = it }
        whitening?.let { params["whitening"] = it }
        slimFace?.let { params["slim_face"] = it }
        bigEyes?.let { params["big_eyes"] = it }
        lipColor?.let { params["lip_color"] = it }
        blush?.let { params["blush"] = it }
        eyebrow?.let { params["eyebrow"] = it }
        return executeCameraCommand("adjust_beauty", params, "Beauty adjusted", "Adjust beauty failed")
    }

    // ==================== 滤镜/风格/场景 ====================

    @Tool(
        name = "switch_filter",
        value = ["切换相机滤镜。可选值：NONE（无）、LEICA_CLASSIC（徕卡经典）、LEICA_VIBRANT（徕卡鲜艳）、LEICA_BW（徕卡黑白）、FILM_GOLD（胶片金）、FILM_FUJI（胶片富士）、VINTAGE（复古）、COOL（冷色）、WARM（暖色）"]
    )
    fun switchFilter(
        @P(name = "filter", value = "滤镜名称，可选值：NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM") filter: String
    ): String {
        val params = mapOf("filter" to filter)
        return executeCameraCommand("switch_filter", params, "Filter switched to $filter", "Switch filter failed")
    }

    @Tool(
        name = "switch_style",
        value = ["切换艺术风格。可选值：NONE（无）、TOON（漫画）、SKETCH（素描）、POSTERIZE（海报）、EMBOSS（浮雕）、CROSSHATCH（交叉线）"]
    )
    fun switchStyle(
        @P(name = "style", value = "风格名称，可选值：NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH") style: String
    ): String {
        val params = mapOf("style" to style)
        return executeCameraCommand("switch_style", params, "Style switched to $style", "Switch style failed")
    }

    @Tool(
        name = "switch_scene",
        value = ["切换场景模式。可选值：night（夜景）、moon（月亮）、none（普通）"]
    )
    fun switchScene(
        @P(name = "scene", value = "场景名称，可选值：night|moon|none") scene: String
    ): String {
        val params = mapOf("scene" to scene)
        return executeCameraCommand("switch_scene", params, "Scene switched to $scene", "Switch scene failed")
    }

    @Tool(
        name = "switch_ratio",
        value = ["切换画面比例。可选值：4:3、16:9、full（全屏）"]
    )
    fun switchRatio(
        @P(name = "ratio", value = "画面比例，可选值：4:3|16:9|full") ratio: String
    ): String {
        val params = mapOf("ratio" to ratio)
        return executeCameraCommand("switch_ratio", params, "Ratio switched to $ratio", "Switch ratio failed")
    }

    // ==================== 曝光/变焦/模式 ====================

    @Tool(
        name = "adjust_exposure",
        value = ["调整曝光补偿，范围 -2 到 2"]
    )
    fun adjustExposure(
        @P(name = "exposure", value = "曝光补偿值，范围 -2 到 2，0 为默认曝光") exposure: Int
    ): String {
        val params = mapOf("exposure" to exposure.coerceIn(-2, 2))
        return executeCameraCommand("adjust_exposure", params, "Exposure adjusted to $exposure", "Adjust exposure failed")
    }

    @Tool(
        name = "adjust_zoom",
        value = ["调整变焦倍数，最小 0.5x"]
    )
    fun adjustZoom(
        @P(name = "zoom", value = "变焦倍数，范围 0.5-10，1.0 为默认无变焦") zoom: Double
    ): String {
        val params = mapOf("zoom" to zoom.coerceAtLeast(0.5))
        return executeCameraCommand("adjust_zoom", params, "Zoom adjusted to $zoom", "Adjust zoom failed")
    }

    @Tool(
        name = "switch_mode",
        value = ["切换拍摄模式。可选值：PHOTO（拍照）、VIDEO（录像）、PRO（专业模式）、DOCUMENT（文档模式）"]
    )
    fun switchMode(
        @P(name = "mode", value = "拍摄模式，可选值：PHOTO|VIDEO|PRO|DOCUMENT") mode: String
    ): String {
        val params = mapOf("mode" to mode)
        return executeCameraCommand("switch_mode", params, "Mode switched to $mode", "Switch mode failed")
    }

    // ==================== UI 操作工具 ====================

    @Tool(
        name = "get_screen_info",
        value = ["获取当前屏幕的 UI 层级树信息，包含所有可见元素的坐标、文本、可点击状态等"]
    )
    fun getScreenInfo(): String {
        val rootView = GetScreenInfoTool.currentRootView
            ?: return errorJson("No activity root view available. Ensure currentRootView is set.")

        val size = getScreenSize()
        return try {
            val tree = ViewHierarchyExtractor.extract(rootView, size[0], size[1])
            successJson(tree)
        } catch (e: Exception) {
            errorJson("Failed to extract screen info: ${e.message}")
        }
    }

    @Tool(
        name = "click",
        value = ["点击屏幕上的元素。支持通过坐标(x,y)或文本(text)定位目标。"]
    )
    fun click(
        @P(name = "x", value = "X 坐标（与 text 互斥）") x: Int? = null,
        @P(name = "y", value = "Y 坐标（与 text 互斥）") y: Int? = null,
        @P(name = "text", value = "通过可见文本查找元素（与 x/y 互斥）") text: String? = null
    ): String {
        if (text != null) {
            return clickByText(text)
        }

        if (x == null || y == null) {
            return errorJson("Either provide (x, y) coordinates or text parameter")
        }

        val validationError = validateCoordinates(x, y)
        if (validationError != null) {
            return errorJson(validationError)
        }

        val rootView = GetScreenInfoTool.currentRootView
            ?: return errorJson("No activity root view available")

        return try {
            val targetView = findViewAtPosition(rootView, x, y)
            if (targetView != null && targetView.isClickable) {
                targetView.performClick()
                successJson("Clicked at ($x, $y) on ${targetView.javaClass.simpleName}")
            } else if (targetView != null) {
                var parent = targetView.parent
                while (parent is android.view.View) {
                    if (parent.isClickable) {
                        parent.performClick()
                        return successJson("Clicked parent at ($x, $y) on ${parent.javaClass.simpleName}")
                    }
                    parent = parent.parent
                }
                dispatchTap(targetView, x, y)
                successJson("Dispatched tap at ($x, $y) on ${targetView.javaClass.simpleName}")
            } else {
                errorJson("No clickable view found at ($x, $y)")
            }
        } catch (e: Exception) {
            errorJson("Click failed: ${e.message}")
        }
    }

    @Tool(
        name = "input_text",
        value = ["在输入框中输入文本。如果当前有焦点 EditText 则输入到该框，否则输入到第一个可见 EditText。"]
    )
    fun inputText(
        @P(name = "text", value = "要输入的文本内容") text: String,
        @P(name = "clear_first", value = "是否先清空现有文本，默认 true") clearFirst: Boolean = true
    ): String {
        val rootView = GetScreenInfoTool.currentRootView
            ?: return errorJson("No activity root view available")

        val focusedEditText = findFocusedEditText(rootView)
        if (focusedEditText != null) {
            focusedEditText.post {
                if (clearFirst) focusedEditText.setText("")
                focusedEditText.append(text)
                focusedEditText.setSelection(focusedEditText.text?.length ?: 0)
            }
            return successJson("Input text '$text' into focused EditText")
        }

        val firstEditText = findFirstEditText(rootView)
        if (firstEditText != null) {
            firstEditText.post {
                firstEditText.requestFocus()
                if (clearFirst) firstEditText.setText("")
                firstEditText.append(text)
                firstEditText.setSelection(firstEditText.text?.length ?: 0)
            }
            return successJson("Input text '$text' into first available EditText")
        }

        return errorJson("No EditText found on current screen")
    }

    @Tool(
        name = "scroll",
        value = ["在屏幕上滑动滚动。支持按方向（up/down/left/right）或坐标滑动。"]
    )
    fun scroll(
        @P(name = "direction", value = "滚动方向：up 或 down") direction: String,
        @P(name = "distance", value = "滚动距离：page（默认）或 small") distance: String = "page"
    ): String {
        val dir = direction.lowercase()
        if (dir !in listOf("up", "down")) {
            return errorJson("Invalid direction: '$direction'. Must be 'up' or 'down'")
        }

        val isPage = distance != "small"
        val rootView = GetScreenInfoTool.currentRootView
            ?: return errorJson("No activity root view available")

        val recyclerView = findRecyclerView(rootView)
        if (recyclerView != null) {
            val dist = if (isPage) recyclerView.height else recyclerView.height / 3
            recyclerView.post {
                recyclerView.smoothScrollBy(0, if (dir == "down") dist else -dist)
            }
            return successJson("Scrolled $direction in RecyclerView")
        }

        val scrollView = findScrollView(rootView)
        if (scrollView != null) {
            val dist = if (isPage) scrollView.height else scrollView.height / 3
            scrollView.post {
                scrollView.smoothScrollBy(0, if (dir == "down") dist else -dist)
            }
            return successJson("Scrolled $direction in ScrollView")
        }

        return errorJson("No scrollable container found on current screen")
    }

    @Tool(
        name = "navigate_to",
        value = ["导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）"]
    )
    fun navigateTo(
        @P(name = "destination", value = "目标页面，可选值：camera|gallery|settings|debug") destination: String
    ): String {
        val validDestinations = setOf("camera", "gallery", "settings", "debug")
        if (destination !in validDestinations) {
            return errorJson("Invalid destination: '$destination'. Must be one of: ${validDestinations.joinToString()}")
        }

        return try {
            val registry = CapabilityRegistry.getInstance()
            val commandJson = JSONObject().apply {
                put("method", "navigate_to")
                put("params", JSONObject().apply { put("destination", destination) })
            }.toString()

            val context = AgentContext(scene = AgentScene.CHAT)
            val command = LocalCommandParser.parseCommandByMethod(
                method = "navigate_to",
                json = commandJson,
                context = context,
                fallbackText = ""
            )

            @OptIn(DelicateCoroutinesApi::class)
            val deferred = GlobalScope.future { registry.dispatch(command, context, null) }
            val result = deferred.get(5, TimeUnit.SECONDS)

            result.fold(
                onSuccess = { successJson("Navigated to $destination") },
                onFailure = { errorJson("Navigation failed: ${it.message}") }
            )
        } catch (e: Exception) {
            errorJson("Navigation error: ${e.message}")
        }
    }

    @Tool(name = "go_back", value = ["返回上一页"])
    fun goBack(): String {
        val activity = BackTool.currentActivity
            ?: return errorJson("No current activity reference available")

        return try {
            if (activity is ComponentActivity) {
                activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                activity.runOnUiThread {
                    @Suppress("DEPRECATION")
                    activity.onBackPressed()
                }
            }
            successJson("Navigated back")
        } catch (e: Exception) {
            errorJson("Back navigation failed: ${e.message}")
        }
    }

    @Tool(name = "finish", value = ["当任务完成时调用此工具，提供任务完成摘要"])
    fun finish(
        @P(name = "summary", value = "任务完成摘要") summary: String
    ): String {
        return successJson(summary)
    }

    // ==================== 私有辅助方法 ====================

    private fun executeCameraCommand(
        method: String,
        params: Map<String, Any>,
        successMsg: String,
        errorPrefix: String
    ): String {
        val result = CameraToolHelper.executeCameraCommand(
            method = method,
            params = params,
            buildCommandJson = { "" },
            onSuccess = { successMsg },
            onError = { "$errorPrefix: $it" }
        )
        return if (result.isSuccess) {
            result.data ?: successMsg
        } else {
            "$errorPrefix: ${result.error ?: "Unknown error"}"
        }
    }

    private fun getScreenSize(): IntArray {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    private fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        return if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        } else null
    }

    private fun findViewAtPosition(root: android.view.View, x: Int, y: Int): android.view.View? {
        if (root is ViewGroup) {
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

    private fun clickByText(text: String): String {
        val rootView = GetScreenInfoTool.currentRootView
            ?: return errorJson("No activity root view available")
        val found = findViewByText(rootView, text)
        return if (found != null) {
            if (found.isClickable) {
                found.performClick()
                successJson("Clicked element with text: '$text'")
            } else {
                var parent = found.parent
                while (parent is android.view.View) {
                    if (parent.isClickable) {
                        parent.performClick()
                        return successJson("Clicked parent of text '$text'")
                    }
                    parent = parent.parent
                }
                val location = IntArray(2)
                found.getLocationOnScreen(location)
                dispatchTap(found, location[0] + found.width / 2, location[1] + found.height / 2)
                successJson("Dispatched tap on text '$text' at center coordinate")
            }
        } else {
            errorJson("No view found with text containing: '$text'")
        }
    }

    private fun findViewByText(root: android.view.View, text: String): android.view.View? {
        if (root is android.widget.TextView) {
            val viewText = root.text?.toString() ?: ""
            if (viewText.contains(text, ignoreCase = true)) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByText(root.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun dispatchTap(view: android.view.View, x: Int, y: Int) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        view.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val upTime = android.os.SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        view.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    private fun findFocusedEditText(root: android.view.View): EditText? {
        if (root is EditText && root.isFocused) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFocusedEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFirstEditText(root: android.view.View): EditText? {
        if (root is EditText && root.visibility == android.view.View.VISIBLE) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
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

    private fun findScrollView(root: android.view.View): ScrollView? {
        if (root is ScrollView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findScrollView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun successJson(data: String): String = JSONObject().apply {
        put("isSuccess", true)
        put("data", data)
    }.toString()

    private fun errorJson(error: String): String = JSONObject().apply {
        put("isSuccess", false)
        put("error", error)
    }.toString()
}
