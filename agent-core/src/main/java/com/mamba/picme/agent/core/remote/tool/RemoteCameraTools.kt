package com.mamba.picme.agent.core.remote.tool

import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.api.context.MediaType
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.react.tool.CameraToolHelper
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool

/**
 * 远程推理工具集（@Tool 注解方式）
 *
 * 使用 langchain4j 的 @Tool 注解声明工具，框架自动提取 ToolSpecification。
 * 与本地 ReAct 工具的 BaseUiTool 体系完全隔离。
 *
 * 工具方法签名设计原则（langchain4j 1.12.2 最佳实践）：
 * - 方法名即工具名（或用 @Tool name 显式覆盖）
 * - 参数用 @P 注解标注描述，描述中包含合法取值范围/枚举值
 * - 必填参数用非空类型（Int/String），可选参数用可空类型（Int?/String?）
 * - 返回 String 直接回传给 LLM
 * - 复杂逻辑委托给 CameraToolHelper / CapabilityRegistry
 */
class RemoteCameraTools {

    private val tag = "RemoteCameraTools"

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

    // ==================== 导航/延迟 ====================

    @Tool(
        name = "delay",
        value = ["延迟指定毫秒数后执行后续操作"]
    )
    fun delay(
        @P(name = "delay_ms", value = "延迟毫秒数，范围 1-300000（5分钟）") delayMs: Long
    ): String {
        val actualDelay = delayMs.coerceIn(1, 300000)
        Thread.sleep(actualDelay)
        return "Delayed ${actualDelay}ms"
    }

    @Tool(
        name = "navigate_to",
        value = ["导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）"]
    )
    fun navigateTo(
        @P(name = "destination", value = "目标页面，可选值：camera|gallery|settings|debug") destination: String
    ): String {
        val params = mapOf("destination" to destination)
        return executeCameraCommand("navigate_to", params, "Navigated to $destination", "Navigation failed")
    }

    @Tool(name = "go_back", value = ["返回上一页"])
    fun goBack(): String {
        return executeCameraCommand("go_back", emptyMap(), "Went back", "Go back failed")
    }

    @Tool(
        name = "text_reply",
        value = ["当用户输入是闲聊、问答或无法使用现有工具执行时，使用此工具回复用户"]
    )
    fun textReply(
        @P(name = "message", value = "回复给用户的文本内容") message: String
    ): String {
        return message
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
            buildCommandJson = { "" }, // 已废弃，不再使用
            onSuccess = { successMsg },
            onError = { "$errorPrefix: $it" }
        )
        return if (result.isSuccess) {
            result.data ?: successMsg
        } else {
            "$errorPrefix: ${result.error ?: "Unknown error"}"
        }
    }
}
