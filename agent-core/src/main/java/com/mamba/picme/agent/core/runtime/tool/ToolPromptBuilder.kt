package com.mamba.picme.agent.core.runtime.tool

import com.mamba.picme.agent.core.langchain4j.ToolSpecification

object ToolPromptBuilder {

    fun buildToolSection(
        toolSpecifications: List<ToolSpecification>,
        config: ToolCallingConfig = ToolCallingConfig()
    ): String {
        if (toolSpecifications.isEmpty()) return ""
        return when (config.mode) {
            ToolCallingMode.OPENAI_TOOLS -> buildOpenAiToolsSection(toolSpecifications)
            ToolCallingMode.REACT -> buildReActSection(toolSpecifications)
        }
    }

    private fun buildOpenAiToolsSection(toolSpecifications: List<ToolSpecification>): String {
        return buildString {
            appendLine()
            appendLine("【可用工具】")
            appendLine("关键规则：每次最多只能调用一个工具，只选最相关的。若无需工具则输出中文回复。")
            appendLine("当需要调用工具时，请严格输出如下 JSON（不要添加任何额外说明）：")
            appendLine(
                """
                {"tool_calls":[{"id":"call_1","type":"function","function":{"name":"工具名","arguments":{}}}]}
                """.trimIndent()
            )
            appendLine("其中 arguments 是 JSON 对象，直接包含具体参数。")
            appendLine("如果无需工具，直接输出中文回复。禁止输出多个工具调用。")
            appendLine()
            toolSpecifications.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
                if (tool.parameters.properties.isNotEmpty()) {
                    appendLine("  参数:")
                    tool.parameters.properties.forEach { (name, prop) ->
                        val required = if (tool.parameters.required.contains(name)) "(必填)" else "(可选)"
                        appendLine("    • $name: ${prop.type} $required ${prop.description ?: ""}")
                    }
                }
            }
        }
    }

    private fun buildReActSection(toolSpecifications: List<ToolSpecification>): String {
        return buildString {
            appendLine()
            appendLine("【可用工具 - ReAct 模式】")
            appendLine("你可以按以下格式一步步思考并调用工具：")
            appendLine("Thought: 你的思考过程")
            appendLine("Action: {\"name\":\"工具名\",\"arguments\":{...}}")
            appendLine("Observation: 工具返回结果（由系统填充）")
            appendLine("...")
            appendLine("当得到足够信息后，输出 Thought: ... 和最终答案，不再调用工具。")
            appendLine()
            toolSpecifications.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
                if (tool.parameters.properties.isNotEmpty()) {
                    appendLine("  参数:")
                    tool.parameters.properties.forEach { (name, prop) ->
                        val required = if (tool.parameters.required.contains(name)) "(必填)" else "(可选)"
                        appendLine("    • $name: ${prop.type} $required ${prop.description ?: ""}")
                    }
                }
            }
        }
    }
}
