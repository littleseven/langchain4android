package com.mamba.picme.agent.core.react.llm

import com.mamba.picme.agent.core.react.tool.BaseUiTool
import com.mamba.picme.agent.core.react.tool.ToolParameter
import com.mamba.picme.agent.core.react.tool.ToolRegistry
import com.mamba.picme.agent.core.react.tool.ToolResult
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import org.json.JSONObject

/**
 * 桥接 PicMe 的自定义工具到 LangChain4j 的工具系统。
 * 将 BaseUiTool 实例转换为 LangChain4j ToolSpecification，并处理工具执行。
 */
object LangChain4jToolBridge {

    /**
     * 从所有已注册的工具构建 LangChain4j ToolSpecification 列表。
     */
    fun buildToolSpecifications(): List<ToolSpecification> {
        return ToolRegistry.getInstance().getAllTools().map { tool -> toSpecification(tool) }
    }

    /**
     * 将 BaseUiTool 转换为 LangChain4j ToolSpecification。
     */
    private fun toSpecification(tool: BaseUiTool): ToolSpecification {
        val params = tool.getParametersWithWaitAfter()

        if (params.isEmpty()) {
            return ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getName())
                .build()
        }

        val properties = LinkedHashMap<String, JsonSchemaElement>()
        val required = mutableListOf<String>()

        for (param in params) {
            val schema: JsonSchemaElement = when (param.type) {
                "integer" -> JsonIntegerSchema.builder()
                    .description(param.description)
                    .build()
                "number" -> JsonNumberSchema.builder()
                    .description(param.description)
                    .build()
                "boolean" -> JsonBooleanSchema.builder()
                    .description(param.description)
                    .build()
                else -> JsonStringSchema.builder()
                    .description(param.description)
                    .build()
            }
            properties[param.name] = schema
            if (param.isRequired) {
                required.add(param.name)
            }
        }

        val parametersSchema = JsonObjectSchema.builder()
            .addProperties(properties)
            .required(required)
            .build()

        return ToolSpecification.builder()
            .name(tool.getName())
            .description(tool.getName())
            .parameters(parametersSchema)
            .build()
    }

    /**
     * 执行 LangChain4j ToolExecutionRequest。
     */
    fun executeToolRequest(request: ToolExecutionRequest): String {
        val toolName = request.name()
        val argsJson = request.arguments()

        val params = try {
            val map = mutableMapOf<String, Any>()
            if (!argsJson.isNullOrEmpty() && argsJson != "{}") {
                val json = JSONObject(argsJson)
                for (key in json.keys()) {
                    map[key] = json.get(key)
                }
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }

        val result = ToolRegistry.getInstance().executeTool(toolName, params)
        return JSONObject().apply {
            put("isSuccess", result.isSuccess)
            put("data", result.data)
            put("error", result.error)
        }.toString()
    }
}
