package com.mamba.picme.agent.core.react.llm

import com.mamba.picme.agent.core.react.tool.InAppToolSet
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * 桥接 PicMe 的 @Tool 注解工具到 LangChain4j 的工具系统。
 *
 * 手动构建 ToolSpecification 列表，避免使用 ToolSpecifications.toolSpecificationsFrom()
 * 因为后者在 Android 上会触发 Jackson 的 PolymorphicTypes.isSealed() 调用（Java 17 API，Android 不支持）。
 */
object LangChain4jToolBridge {

    private var toolSetInstance: InAppToolSet? = null

    /**
     * 手动构建 ToolSpecification 列表。
     */
    fun buildToolSpecifications(toolSet: InAppToolSet): List<ToolSpecification> {
        toolSetInstance = toolSet
        return listOf(
            // ==================== 相机核心命令 ====================
            ToolSpecification.builder()
                .name("capture")
                .description("拍照并保存到相册")
                .build(),

            ToolSpecification.builder()
                .name("toggle_recording")
                .description("切换录像状态（开始或停止录像）")
                .build(),

            ToolSpecification.builder()
                .name("flip_camera")
                .description("切换前后摄像头")
                .build(),

            // ==================== 美颜调节 ====================
            ToolSpecification.builder()
                .name("adjust_beauty")
                .description("调整美颜参数。支持磨皮、美白、瘦脸、大眼、唇色、腮红、眉毛。只传入需要调整的参数，未传入的参数保持不变。")
                .parameters(
                    JsonObjectSchema.builder()
                        .addIntegerProperty("smoothing", "磨皮强度，范围 0-100，默认不调整")
                        .addIntegerProperty("whitening", "美白强度，范围 0-100，默认不调整")
                        .addIntegerProperty("slim_face", "瘦脸强度，范围 -50 到 50，默认不调整")
                        .addIntegerProperty("big_eyes", "大眼强度，范围 0-100，默认不调整")
                        .addIntegerProperty("lip_color", "唇色强度，范围 0-100，默认不调整")
                        .addIntegerProperty("blush", "腮红强度，范围 0-100，默认不调整")
                        .addIntegerProperty("eyebrow", "眉毛强度，范围 0-100，默认不调整")
                        .build()
                )
                .build(),

            // ==================== 滤镜/风格/场景 ====================
            ToolSpecification.builder()
                .name("switch_filter")
                .description("切换相机滤镜。可选值：NONE（无）、LEICA_CLASSIC（徕卡经典）、LEICA_VIBRANT（徕卡鲜艳）、LEICA_BW（徕卡黑白）、FILM_GOLD（胶片金）、FILM_FUJI（胶片富士）、VINTAGE（复古）、COOL（冷色）、WARM（暖色）")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("filter", listOf("NONE", "LEICA_CLASSIC", "LEICA_VIBRANT", "LEICA_BW", "FILM_GOLD", "FILM_FUJI", "VINTAGE", "COOL", "WARM"), "滤镜名称")
                        .required("filter")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("switch_style")
                .description("切换艺术风格。可选值：NONE（无）、TOON（漫画）、SKETCH（素描）、POSTERIZE（海报）、EMBOSS（浮雕）、CROSSHATCH（交叉线）")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("style", listOf("NONE", "TOON", "SKETCH", "POSTERIZE", "EMBOSS", "CROSSHATCH"), "风格名称")
                        .required("style")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("switch_scene")
                .description("切换场景模式。可选值：night（夜景）、moon（月亮）、none（普通）")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("scene", listOf("night", "moon", "none"), "场景名称")
                        .required("scene")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("switch_ratio")
                .description("切换画面比例。可选值：4:3、16:9、full（全屏）")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("ratio", listOf("4:3", "16:9", "full"), "画面比例")
                        .required("ratio")
                        .build()
                )
                .build(),

            // ==================== 曝光/变焦/模式 ====================
            ToolSpecification.builder()
                .name("adjust_exposure")
                .description("调整曝光补偿，范围 -2 到 2")
                .parameters(
                    JsonObjectSchema.builder()
                        .addIntegerProperty("exposure", "曝光补偿值，范围 -2 到 2，0 为默认曝光")
                        .required("exposure")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("adjust_zoom")
                .description("调整变焦倍数，最小 0.5x")
                .parameters(
                    JsonObjectSchema.builder()
                        .addNumberProperty("zoom", "变焦倍数，范围 0.5-10，1.0 为默认无变焦")
                        .required("zoom")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("switch_mode")
                .description("切换拍摄模式。可选值：PHOTO（拍照）、VIDEO（录像）、PRO（专业模式）、DOCUMENT（文档模式）")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("mode", listOf("PHOTO", "VIDEO", "PRO", "DOCUMENT"), "拍摄模式")
                        .required("mode")
                        .build()
                )
                .build(),

            // ==================== UI 操作工具 ====================
            ToolSpecification.builder()
                .name("get_screen_info")
                .description("获取当前屏幕的 UI 层级树信息，包含所有可见元素的坐标、文本、可点击状态等")
                .build(),

            ToolSpecification.builder()
                .name("click")
                .description("点击屏幕上的元素。支持通过坐标(x,y)或文本(text)定位目标。")
                .parameters(
                    JsonObjectSchema.builder()
                        .addIntegerProperty("x", "X 坐标（与 text 互斥）")
                        .addIntegerProperty("y", "Y 坐标（与 text 互斥）")
                        .addStringProperty("text", "通过可见文本查找元素（与 x/y 互斥）")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("input_text")
                .description("在输入框中输入文本。如果当前有焦点 EditText 则输入到该框，否则输入到第一个可见 EditText。")
                .parameters(
                    JsonObjectSchema.builder()
                        .addStringProperty("text", "要输入的文本内容")
                        .addEnumProperty("clear_first", listOf("true", "false"), "是否先清空现有文本，默认 true")
                        .required("text")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("scroll")
                .description("在屏幕上滑动滚动。支持按方向（up/down/left/right）或坐标滑动。")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("direction", listOf("up", "down", "left", "right"), "滚动方向：up 或 down")
                        .addEnumProperty("distance", listOf("page", "small"), "滚动距离：page（默认）或 small")
                        .required("direction")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("navigate_to")
                .description("导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）")
                .parameters(
                    JsonObjectSchema.builder()
                        .addEnumProperty("destination", listOf("camera", "gallery", "settings", "debug"), "目标页面")
                        .required("destination")
                        .build()
                )
                .build(),

            ToolSpecification.builder()
                .name("go_back")
                .description("返回上一页")
                .build(),

            ToolSpecification.builder()
                .name("finish")
                .description("当任务完成时调用此工具，提供任务完成摘要")
                .parameters(
                    JsonObjectSchema.builder()
                        .addStringProperty("summary", "任务完成摘要")
                        .required("summary")
                        .build()
                )
                .build()
        )
    }

    /**
     * 执行 LangChain4j ToolExecutionRequest。
     * 通过反射调用 InAppToolSet 实例的 @Tool 方法。
     */
    fun executeToolRequest(request: ToolExecutionRequest): String {
        val toolName = request.name()
        val argsJson = request.arguments()
        val instance = toolSetInstance
            ?: return errorJson("ToolSet not initialized")

        return try {
            val method = findToolMethod(instance, toolName)
                ?: return errorJson("Unknown tool: $toolName")

            val args = parseArguments(argsJson, method)
            val result = method.invoke(instance, *args)
            result?.toString() ?: successJson("OK")
        } catch (e: Exception) {
            errorJson("Tool execution failed: ${e.cause?.message ?: e.message}")
        }
    }

    private fun findToolMethod(instance: InAppToolSet, toolName: String): Method? {
        return instance.javaClass.methods.find { method ->
            val annotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
            annotation?.name == toolName
        }
    }

    private fun parseArguments(argsJson: String?, method: Method): Array<Any?> {
        val params = method.parameters
        if (params.isEmpty()) return emptyArray()

        val json = try {
            if (!argsJson.isNullOrEmpty() && argsJson != "{}") JSONObject(argsJson) else JSONObject()
        } catch (_: Exception) { JSONObject() }

        return params.map { param ->
            val pAnnotation = param.getAnnotation(dev.langchain4j.agent.tool.P::class.java)
            val key = pAnnotation?.name ?: param.name
            ?: return@map null

            val value = json.opt(key)
            if (value == null || value == JSONObject.NULL) {
                // 可空类型返回 null，非空类型尝试默认值
                if (param.type.isPrimitive) {
                    when (param.type) {
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Double::class.javaPrimitiveType -> 0.0
                        Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                } else null
            } else {
                convertValue(value, param.type)
            }
        }.toTypedArray()
    }

    private fun convertValue(value: Any, targetType: Class<*>): Any? {
        return when {
            targetType == String::class.java -> value.toString()
            targetType == Int::class.java || targetType == Int::class.javaPrimitiveType -> {
                when (value) {
                    is Number -> value.toInt()
                    else -> value.toString().toIntOrNull() ?: 0
                }
            }
            targetType == Long::class.java || targetType == Long::class.javaPrimitiveType -> {
                when (value) {
                    is Number -> value.toLong()
                    else -> value.toString().toLongOrNull() ?: 0L
                }
            }
            targetType == Double::class.java || targetType == Double::class.javaPrimitiveType -> {
                when (value) {
                    is Number -> value.toDouble()
                    else -> value.toString().toDoubleOrNull() ?: 0.0
                }
            }
            targetType == Boolean::class.java || targetType == Boolean::class.javaPrimitiveType -> {
                when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> value.toString().toBoolean()
                }
            }
            else -> value
        }
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
