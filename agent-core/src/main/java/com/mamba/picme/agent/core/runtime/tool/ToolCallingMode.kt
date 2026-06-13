package com.mamba.picme.agent.core.runtime.tool

enum class ToolCallingMode {
    /**
     * OpenAI tools/tool_calls 格式（默认）。
     * 模型输出 JSON：`{"tool_calls":[{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}]}`。
     * 适合配合 GBNF  grammar 约束使用。
     */
    OPENAI_TOOLS,

    /**
     * ReAct 模式。
     * 模型按 `Thought: ...\nAction: ...` 输出，便于开发期观察思考过程。
     */
    REACT
}
