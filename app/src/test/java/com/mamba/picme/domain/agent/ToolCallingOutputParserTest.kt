package com.mamba.picme.domain.agent

import com.mamba.picme.agent.core.runtime.tool.ToolCallingConfig
import com.mamba.picme.agent.core.runtime.tool.ToolCallingMode
import com.mamba.picme.agent.core.runtime.tool.ToolCallingOutputParser
import org.junit.Assert.assertEquals
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
}
