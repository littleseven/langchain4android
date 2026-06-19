package com.mamba.picme.agent.core.react.tool

import android.view.WindowManager
import com.mamba.picme.agent.core.react.tool.impl.AdjustBeautyTool
import com.mamba.picme.agent.core.react.tool.impl.AdjustExposureTool
import com.mamba.picme.agent.core.react.tool.impl.AdjustZoomTool
import com.mamba.picme.agent.core.react.tool.impl.BackTool
import com.mamba.picme.agent.core.react.tool.impl.CapturePhotoTool
import com.mamba.picme.agent.core.react.tool.impl.ClickTool
import com.mamba.picme.agent.core.react.tool.impl.FinishTool
import com.mamba.picme.agent.core.react.tool.impl.FlipCameraTool
import com.mamba.picme.agent.core.react.tool.impl.GetScreenInfoTool
import com.mamba.picme.agent.core.react.tool.impl.InputTextTool
import com.mamba.picme.agent.core.react.tool.impl.NavigateToTool
import com.mamba.picme.agent.core.react.tool.impl.ScrollTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchFilterTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchRatioTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchSceneTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchStyleTool
import com.mamba.picme.agent.core.react.tool.impl.ToggleRecordingTool
import com.mamba.picme.agent.core.react.tool.impl.SwitchModeTool
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema

/**
 * 应用内 UI Agent Tool 注册中心。
 *
 * 所有工具在此注册，是 Tool 的**单一事实来源**（Single Source of Truth）。
 * 同时提供：
 * - 工具执行（供 ReAct Agent 使用）
 * - LangChain4j ToolSpecification 生成（供 L2 Batch / ReAct 共用）
 */
object ToolRegistry {

    private val tools = LinkedHashMap<String, BaseUiTool>()

    fun getInstance(): ToolRegistry = this

    fun registerAllTools(windowManager: WindowManager) {
        tools.clear()
        register(GetScreenInfoTool(windowManager))
        register(ClickTool(windowManager))
        register(InputTextTool())
        register(ScrollTool())
        register(NavigateToTool())
        register(BackTool())
        register(FinishTool())
        // 相机控制工具（已注册，ReAct Agent 可直接调用）
        register(CapturePhotoTool())
        register(FlipCameraTool())
        register(ToggleRecordingTool())
        register(SwitchModeTool())
        register(AdjustBeautyTool())
        register(AdjustExposureTool())
        register(AdjustZoomTool())
        register(SwitchFilterTool())
        register(SwitchStyleTool())
        register(SwitchSceneTool())
        register(SwitchRatioTool())
    }

    fun register(tool: BaseUiTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseUiTool? = tools[name]

    fun getAllTools(): List<BaseUiTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    // ── 统一 ToolSpecification 生成 ───────────────────────────────

    /**
     * 为所有已注册工具生成 LangChain4j ToolSpecification 列表。
     *
     * 这是 L2 Batch 和 ReAct 的**唯一 ToolSpec 来源**，消除手动重复定义。
     */
    fun buildToolSpecifications(): List<ToolSpecification> {
        return tools.values.map { tool ->
            val builder = ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription())

            val params = tool.getParametersWithWaitAfter()
            if (params.isNotEmpty()) {
                val schemaBuilder = JsonObjectSchema.builder()
                val required = mutableListOf<String>()

                for (param in params) {
                    when (param.type.lowercase()) {
                        "string" -> {
                            schemaBuilder.addStringProperty(param.name, param.description)
                        }
                        "integer" -> {
                            schemaBuilder.addIntegerProperty(param.name, param.description)
                        }
                        "number" -> {
                            schemaBuilder.addNumberProperty(param.name, param.description)
                        }
                        "boolean" -> {
                            schemaBuilder.addBooleanProperty(param.name, param.description)
                        }
                        "enum" -> {
                            // enum 类型的 description 需包含合法值，如 "滤镜名称: NONE|WARM|COOL"
                            val enumValues = extractEnumValues(param.description)
                            if (enumValues.isNotEmpty()) {
                                schemaBuilder.addEnumProperty(param.name, enumValues, param.description)
                            } else {
                                schemaBuilder.addStringProperty(param.name, param.description)
                            }
                        }
                        else -> {
                            schemaBuilder.addStringProperty(param.name, param.description)
                        }
                    }
                    if (param.isRequired) {
                        required.add(param.name)
                    }
                }

                if (required.isNotEmpty()) {
                    schemaBuilder.required(required)
                }
                builder.parameters(schemaBuilder.build())
            }

            builder.build()
        }
    }

    /**
     * 从 description 中提取枚举值。
     * 格式约定："描述文字: 值1|值2|值3"
     */
    private fun extractEnumValues(description: String): List<String> {
        val colonIndex = description.indexOf(':')
        if (colonIndex == -1) return emptyList()
        val afterColon = description.substring(colonIndex + 1).trim()
        // 支持 "A|B|C" 或 "A, B, C" 格式
        return if (afterColon.contains('|')) {
            afterColon.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        } else if (afterColon.contains(',')) {
            afterColon.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    }
}
