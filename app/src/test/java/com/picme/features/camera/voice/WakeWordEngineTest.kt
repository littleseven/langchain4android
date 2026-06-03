package com.picme.features.camera.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WakeWordEngine 唤醒词过滤逻辑单元测试
 *
 * 测试 [WakeWordEngine.stripWakeWord] 和 [WakeWordEngine.findMatchedWakeWord] 方法。
 */
class WakeWordEngineTest {

    // 通过协程 scope 和 mock asrEngine 构造实例仅用于测试 internal 方法

    // ── findMatchedWakeWord 测试：同音字变体识别 ──────────────────────────

    @Test
    fun `findMatchedWakeWord matches standard wake word`() {
        val engine = createEngine()
        assertEquals("小觅", engine.findMatchedWakeWord("小觅拍张照"))
    }

    @Test
    fun `findMatchedWakeWord matches homophone xiao mi`() {
        val engine = createEngine()
        // 小蜜是 ASR 最常见的误识变体
        assertEquals("小蜜", engine.findMatchedWakeWord("小蜜拍张照"))
    }

    @Test
    fun `findMatchedWakeWord matches homophone xiao mi secretary`() {
        val engine = createEngine()
        assertEquals("小秘", engine.findMatchedWakeWord("小秘调高美颜"))
    }

    @Test
    fun `findMatchedWakeWord matches xiao mi rice`() {
        val engine = createEngine()
        // 小米是极常见的词，ASR 可能输出此变体
        assertEquals("小米", engine.findMatchedWakeWord("小米打开前置"))
    }

    @Test
    fun `findMatchedWakeWord matches xiao mi cat`() {
        val engine = createEngine()
        assertEquals("小咪", engine.findMatchedWakeWord("小咪换个冷调滤镜"))
    }

    @Test
    fun `findMatchedWakeWord returns null for no match`() {
        val engine = createEngine()
        assertNull("无关文本不应匹配", engine.findMatchedWakeWord("今天天气不错"))
    }

    @Test
    fun `findMatchedWakeWord returns null for empty string`() {
        val engine = createEngine()
        assertNull("空字符串不应匹配", engine.findMatchedWakeWord(""))
    }

    // ── stripWakeWord 测试：基本唤醒词移除 ──────────────────────────────

    @Test
    fun `stripWakeWord removes wake word prefix`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅拍张照", "小觅")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord removes wake word suffix`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("拍张照小觅", "小觅")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord removes wake word infix`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("帮我小觅拍张照", "小觅")
        assertEquals("帮我拍张照", result)
    }

    @Test
    fun `stripWakeWord removes multiple wake words`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅小觅拍张照", "小觅")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles wake word only`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅", "小觅")
        assertEquals("", result)
    }

    @Test
    fun `stripWakeWord returns original when no wake word`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("拍张照", "小觅")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles empty string`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("", "小觅")
        assertEquals("", result)
    }

    @Test
    fun `stripWakeWord handles transcript with whitespace around wake word`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅 拍张照", "小觅")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles complex command`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅调高美颜磨皮80", "小觅")
        assertEquals("调高美颜磨皮80", result)
    }

    @Test
    fun `stripWakeWord handles wake word at both ends`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅拍张照小觅", "小觅")
        assertEquals("拍张照", result)
    }

    // ── stripWakeWord 测试：同音字变体移除 ──────────────────────────────

    @Test
    fun `stripWakeWord removes homophone xiao mi variant`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小蜜拍张照", "小蜜")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord removes homophone xiao mi rice variant`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小米调高美颜", "小米")
        assertEquals("调高美颜", result)
    }

    @Test
    fun `stripWakeWord uses default variant when not specified`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小觅拍张照")
        assertEquals("拍张照", result)
    }

    /**
     * 创建测试用 WakeWordEngine 实例
     * 需要传入非空 scope，但 stripWakeWord/findMatchedWakeWord 不依赖 scope
     */
    private fun createEngine(): WakeWordEngine {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val asrEngine = object : AsrEngine {
            override suspend fun transcribe(audioData: ByteArray): Result<String> = Result.success("")
            override fun isAvailable(): Boolean = true
        }
        return WakeWordEngine(asrEngine, scope)
    }
}
