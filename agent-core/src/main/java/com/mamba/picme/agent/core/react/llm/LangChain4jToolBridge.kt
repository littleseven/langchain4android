package com.mamba.picme.agent.core.react.llm

import com.mamba.picme.agent.core.react.tool.ToolRegistry
import com.mamba.agent.agent.tool.ToolExecutionRequest
import com.mamba.agent.agent.tool.ToolSpecification
import org.json.JSONObject

/**
 * 桥接 PicMe 的 ToolRegistry 工具到 LangChain4j 的工具系统。
 *
 * 从 [ToolRegistry] 统一生成 ToolSpecification，避免手动重复定义。
 * ToolRegistry 是 Tool 的单一事实来源（Single Source of Truth）。
 */
object LangChain4jToolBridge {

    /**
     * 从 ToolRegistry 生成 ToolSpecification 列表。
     */
    fun buildToolSpecifications(): List<ToolSpecification> {
        return ToolRegistry.buildToolSpecifications()
    }

    /**
     * 执行 LangChain4j ToolExecutionRequest。
     * 委托给 ToolRegistry 执行工具。
     */
    fun executeToolRequest(request: ToolExecutionRequest): String {
        val toolName = request.name()
        val argsJson = request.arguments()

        return try {
            val args = parseArguments(argsJson)
            val result = ToolRegistry.executeTool(toolName, args)
            if (result.isSuccess) {
                successJson(result.data ?: "OK")
            } else {
                errorJson(result.error ?: "Unknown error")
            }
        } catch (e: Exception) {
            errorJson("Tool execution failed: ${e.cause?.message ?: e.message}")
        }
    }

    private fun parseArguments(argsJson: String?): Map<String, Any> {
        if (argsJson.isNullOrEmpty() || argsJson == "{}") return emptyMap()
        return try {
            val json = JSONObject(argsJson)
            val map = mutableMapOf<String, Any>()
            for (key in json.keys()) {
                val value = json.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    map[key] = value
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
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
