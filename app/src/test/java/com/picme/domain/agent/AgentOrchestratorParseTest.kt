package com.picme.domain.agent

import com.picme.beauty.api.BeautySettings
import com.picme.beauty.api.FilterType
import com.picme.beauty.api.StyleFilter
import com.picme.domain.agent.model.AgentAction
import com.picme.domain.agent.model.AgentCommand
import com.picme.domain.agent.model.AgentContext
import com.picme.domain.agent.model.AgentScene
import com.picme.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentCommandParser 响应解析逻辑单元测试
 *
 * 直接测试生产方法 [AgentCommandParser.parseLlmResponse] 和
 * [AgentCommandParser.parseCommandByAction]，确保测试能够真实反映生产代码行为。
 *
 * 提取为独立 object 的原因：避免实例化 [AgentOrchestrator] 时触发
 * [LocalLlmEngine] -> [MnnLlmClient] 的 JNI 加载，导致纯 JVM 单元测试失败。
 */
class AgentOrchestratorParseTest {

    private val defaultContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = BeautySettings(),
        filterType = FilterType.NONE,
        styleFilter = StyleFilter.NONE,
        zoomRatio = 1f,
        exposureCompensation = 0,
        captureMode = MediaType.PHOTO,
        isRecording = false
    )

    // ------------------------------------------------------------------
    // 直接测试生产方法 parseLlmResponse
    // ------------------------------------------------------------------

    @Test
    fun `parseLlmResponse removes closed think tags`() {
        val input = "<thinking>思考中</thinking>\n{\"action\":\"capture\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse removes qwen3 think tags`() {
        val input = "<think>用户想拍照，触发capture</think>\n{\"action\":\"capture\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse removes nested think and thinking tags`() {
        val input = "<think>思考1</think>\n<thinking>思考2</thinking>\n{\"action\":\"capture\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse removes multiple think tags`() {
        val input = "<thinking>1</thinking>\n<thinking>2</thinking>\n{\"action\":\"capture\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse truncates orphan think tag`() {
        val input = "你好。<thinking>未结束"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.TextReply)
        assertEquals("你好。", (command as AgentCommand.TextReply).message)
    }

    @Test
    fun `parseLlmResponse removes markdown code block`() {
        val input = "```json\n{\"action\":\"capture\"}\n```"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse handles free chat without action`() {
        val input = "你好！我是小觅，有什么可以帮你的吗？"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.TextReply)
    }

    @Test
    fun `parseLlmResponse full pipeline with think tags and markdown`() {
        val input = "<thinking>用户想拍照</thinking>\n```json\n{\"action\":\"capture\"}\n```"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse full pipeline with qwen3 think tags and markdown`() {
        val input = "<think>用户说拍照，需要触发相机快门</think>\n```json\n{\"action\":\"capture\"}\n```"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse qwen3 unclosed think tag extracts json after`() {
        val input = "<think>用户想拍照\n{\"action\":\"capture\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        // 修复后：从未闭合 think 标签后提取 JSON
        assertTrue(
            "未闭合 think 标签后包含 JSON 时应正确解析",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse orphan think tag with json after is cleaned`() {
        val input = "<think>思考过程...\n{\"action\":\"capture\"}\n</think>\n{\"action\":\"capture\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse real qwen3 response with unclosed think and json`() {
        val input = "<think>\n好的，用户发来的\"拍照\"指令。根据规则，当用户想要控制相机时，直接输出JSON。因此，生成对应的拍照指令。\n\n{\"action\":\"capture\"}\n```"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        // 修复后：从未闭合 think 标签后提取 JSON，并清理 markdown
        assertTrue(
            "真实 Qwen3 响应应正确提取 capture 指令",
            command is AgentCommand.CapturePhoto
        )
    }

    // ------------------------------------------------------------------
    // 关键回归测试：photo 别名映射到 CapturePhoto
    // 这些测试如果之前存在，就能发现 LLM 输出 action=photo 时无法拍照的 bug
    // ------------------------------------------------------------------

    @Test
    fun `parseLlmResponse photo alias maps to CapturePhoto`() {
        val input = "{\"action\":\"photo\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "LLM 输出 action=photo 时必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse photo alias with think tags`() {
        val input = "<think>用户说拍照</think>\n{\"action\":\"photo\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "photo 别名在 think 标签清理后必须正确解析",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse photo alias with markdown block`() {
        val input = "```json\n{\"action\":\"photo\"}\n```"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "markdown 包裹的 photo action 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    // ------------------------------------------------------------------
    // 直接测试生产方法 parseCommandByAction
    // ------------------------------------------------------------------

    @Test
    fun `parseCommandByAction capture returns CapturePhoto`() {
        val command = AgentCommandParser.parseCommandByAction(
            action = "capture",
            json = "{\"action\":\"capture\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseCommandByAction photo returns CapturePhoto`() {
        val command = AgentCommandParser.parseCommandByAction(
            action = "photo",
            json = "{\"action\":\"photo\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(
            "action=photo 必须映射为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseCommandByAction flip_camera returns FlipCamera`() {
        val command = AgentCommandParser.parseCommandByAction(
            action = "flip_camera",
            json = "{\"action\":\"flip_camera\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.FlipCamera)
    }

    @Test
    fun `parseCommandByAction adjust_beauty returns AdjustBeauty`() {
        val command = AgentCommandParser.parseCommandByAction(
            action = "adjust_beauty",
            json = "{\"action\":\"adjust_beauty\",\"smoothing\":0.8}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.AdjustBeauty)
        val beautyCommand = command as AgentCommand.AdjustBeauty
        assertEquals(0.8f, beautyCommand.settings.smoothing, 0.001f)
    }

    @Test
    fun `parseCommandByAction switch_filter returns SwitchFilter`() {
        val command = AgentCommandParser.parseCommandByAction(
            action = "switch_filter",
            json = "{\"action\":\"switch_filter\",\"filter\":\"VINTAGE\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.SwitchFilter)
        assertEquals(FilterType.VINTAGE, (command as AgentCommand.SwitchFilter).filterType)
    }

    @Test
    fun `parseCommandByAction unknown action returns TextReply`() {
        val command = AgentCommandParser.parseCommandByAction(
            action = "unknown_action",
            json = "{\"action\":\"unknown_action\"}",
            context = defaultContext,
            fallbackText = "fallback message"
        )
        assertTrue(command is AgentCommand.TextReply)
        assertEquals("fallback message", (command as AgentCommand.TextReply).message)
    }

    // ------------------------------------------------------------------
    // CapabilityRegistry 测试：未注册 Capability 时返回 Success
    // 这个测试如果之前存在，就能发现 CameraCapability 未注册时命令被丢弃的 bug
    // ------------------------------------------------------------------

    @Test
    fun `capabilityRegistry dispatch CapturePhoto without registered capability returns Success`() = runBlocking {
        val registry = CapabilityRegistry()
        val command = AgentCommand.CapturePhoto
        val result = registry.dispatch(command, defaultContext)
        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
        assertEquals(command, (action as AgentAction.Success).command)
    }

    @Test
    fun `capabilityRegistry dispatch TextReply returns TextReply action`() = runBlocking {
        val registry = CapabilityRegistry()
        val command = AgentCommand.TextReply("你好")
        val result = registry.dispatch(command, defaultContext)
        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertEquals("你好", (action as AgentAction.TextReply).message)
    }

    @Test
    fun `capabilityRegistry dispatch FlipCamera without registered capability returns Success`() = runBlocking {
        val registry = CapabilityRegistry()
        val command = AgentCommand.FlipCamera
        val result = registry.dispatch(command, defaultContext)
        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.Success)
    }

    // ------------------------------------------------------------------
    // 端到端场景测试：模拟真实 LLM 输出
    // ------------------------------------------------------------------

    @Test
    fun `end to end qwen3 real response with photo action`() {
        val input = "<think>\n用户说\"拍照\"，需要触发相机拍照。\n\n{\"action\":\"photo\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        // 修复后：从未闭合 think 标签后提取 JSON
        assertTrue(
            "未闭合 think + photo action 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `end to end qwen3 closed think with photo action`() {
        val input = "<think>用户说拍照</think>\n{\"action\":\"photo\"}"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "闭合 think 标签 + photo action 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `end to end markdown wrapped photo action`() {
        val input = "```json\n{\"action\":\"photo\"}\n```"
        val command = AgentCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "markdown 包裹的 photo action 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

}
