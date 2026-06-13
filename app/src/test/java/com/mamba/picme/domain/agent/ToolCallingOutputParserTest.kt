package com.mamba.picme.domain.agent

import com.mamba.picme.agent.core.api.command.AgentCommand
import com.mamba.picme.agent.core.api.context.AgentContext
import com.mamba.picme.agent.core.api.context.AgentScene
import com.mamba.picme.agent.core.runtime.parsing.AgentCommandParser
import com.mamba.picme.agent.core.runtime.tool.ToolCallingConfig
import com.mamba.picme.agent.core.runtime.tool.ToolCallingMode
import com.mamba.picme.agent.core.runtime.tool.ToolCallingOutputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallingOutputParserTest {

    @Test
    fun `parse single tool call`() {
        val text = """{"name":"capture","arguments":{}}"""
        val requests = ToolCallingOutputParser.parse(text)
        assertEquals(1, requests.size)
        assertEquals("capture", requests[0].name)
    }

    @Test
    fun `parse tool call array`() {
        val text = """[{"name":"adjust_beauty","arguments":{"smoothing":50}}]"""
        val requests = ToolCallingOutputParser.parse(text)
        assertEquals(1, requests.size)
        assertEquals("adjust_beauty", requests[0].name)
    }

    @Test
    fun `parse tool call tags`() {
        val text = "<tool_call>{\"name\":\"flip_camera\",\"arguments\":{}}</tool_call>"
        val requests = ToolCallingOutputParser.parse(text)
        assertEquals(1, requests.size)
        assertEquals("flip_camera", requests[0].name)
    }

    @Test
    fun `parse plain text returns empty`() {
        val requests = ToolCallingOutputParser.parse("你好，今天天气不错")
        assertEquals(0, requests.size)
    }

    @Test
    fun `parse openai tool_calls format`() {
        val text = """{"tool_calls":[{"id":"call_1","type":"function","function":{"name":"capture","arguments":"{}"}}]}"""
        val requests = ToolCallingOutputParser.parse(text, ToolCallingConfig(ToolCallingMode.OPENAI_TOOLS))
        assertEquals(1, requests.size)
        assertEquals("call_1", requests[0].id)
        assertEquals("capture", requests[0].name)
    }

    @Test
    fun `parse react action format`() {
        val text = """
            Thought: 用户想拍照，我需要调用相机工具。
            Action: {"name":"capture","arguments":{}}
        """.trimIndent()
        val requests = ToolCallingOutputParser.parse(text, ToolCallingConfig(ToolCallingMode.REACT))
        assertEquals(1, requests.size)
        assertEquals("capture", requests[0].name)
    }

    @Test
    fun `parse openai tool_calls with arguments as object`() {
        // 某些模型/接口会把 arguments 输出为 JSON 对象而非字符串
        val text = "{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"navigate_to\",\"arguments\":{\"destination\":\"gallery\"}}}]}"

        val requests = ToolCallingOutputParser.parse(text, ToolCallingConfig(ToolCallingMode.OPENAI_TOOLS))
        assertEquals(1, requests.size)
        assertEquals("navigate_to", requests[0].name)
        assertEquals("{\"destination\":\"gallery\"}", requests[0].arguments)
    }

    @Test
    fun `openai tool_calls for navigate to gallery converts to AgentCommand`() {
        // 模拟 chat 页面输入"打开相册"时远程模型返回的 OpenAI tool_calls 格式
        val text = "{\"tool_calls\":[{\"id\":\"call_gallery\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"navigate_to\",\"arguments\":\"{\\\"destination\\\":\\\"gallery\\\"}\"}}]}"

        val requests = ToolCallingOutputParser.parse(text, ToolCallingConfig(ToolCallingMode.OPENAI_TOOLS))
        assertEquals(1, requests.size)
        assertEquals("navigate_to", requests[0].name)

        // 验证 tool call 可被转换为内部 AgentCommand（与 InferenceRouter 行为一致）
        val commandJson = "{\"method\":\"navigate_to\",\"params\":{\"destination\":\"gallery\"}}"
        val command = AgentCommandParser.parseCommandByMethod(
            method = requests[0].name,
            json = commandJson,
            context = AgentContext(scene = AgentScene.CHAT),
            fallbackText = requests[0].arguments
        )

        assertTrue(command is AgentCommand.NavigateTo)
        assertEquals("gallery", (command as AgentCommand.NavigateTo).destination)
    }
}
