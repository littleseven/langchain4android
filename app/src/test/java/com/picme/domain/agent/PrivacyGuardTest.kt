package com.picme.domain.agent

import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrivacyGuard 单元测试
 *
 * 验证隐私分级（classify）和运行时策略检查。
 * 生产代码已重构：无参构造函数 + classify() 方法进行输入隐私分级。
 */
class PrivacyGuardTest {

    // ------------------------------------------------------------------
    // 1. 隐私分级 classify()
    // ------------------------------------------------------------------

    @Test
    fun `classify coordinate pattern returns RESTRICTED`() {
        val guard = PrivacyGuard()
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("100,200"))
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("0,0"))
    }

    @Test
    fun `classify restricted keywords returns RESTRICTED`() {
        val guard = PrivacyGuard()
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("人脸关键点坐标"))
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("bounding_box数据"))
    }

    @Test
    fun `classify sensitive keywords returns SENSITIVE`() {
        val guard = PrivacyGuard()
        assertEquals(PrivacyLevel.SENSITIVE, guard.classify("我的照片在哪里"))
        assertEquals(PrivacyLevel.SENSITIVE, guard.classify("OCR结果是什么"))
    }

    @Test
    fun `classify normal input returns PUBLIC`() {
        val guard = PrivacyGuard()
        assertEquals(PrivacyLevel.PUBLIC, guard.classify("拍张照"))
        assertEquals(PrivacyLevel.PUBLIC, guard.classify("切换滤镜"))
    }

    // ------------------------------------------------------------------
    // 2. 运行时策略检查
    // ------------------------------------------------------------------

    @Test
    fun `default config disallows remote`() {
        val guard = PrivacyGuard()
        assertFalse(guard.isRemoteAllowed())
    }

    @Test(expected = SecurityException::class)
    fun `strict mode with remote agent mode throws on assert`() {
        val guard = PrivacyGuard()
        guard.updateConfig(AiAgentPrivacyLevel.STRICT, AiAgentMode.REMOTE)
        guard.assertLocalOnly()
    }

    @Test
    fun `strict local mode assert passes`() {
        val guard = PrivacyGuard()
        guard.updateConfig(AiAgentPrivacyLevel.STRICT, AiAgentMode.LOCAL)
        guard.assertLocalOnly() // should not throw
        assertFalse(guard.isRemoteAllowed())
    }

    @Test
    fun `permissive remote mode allows remote`() {
        val guard = PrivacyGuard()
        guard.updateConfig(AiAgentPrivacyLevel.PERMISSIVE, AiAgentMode.REMOTE)
        assertTrue(guard.isRemoteAllowed())
    }

    @Test
    fun `permissive local mode disallows remote`() {
        val guard = PrivacyGuard()
        guard.updateConfig(AiAgentPrivacyLevel.PERMISSIVE, AiAgentMode.LOCAL)
        assertFalse(guard.isRemoteAllowed())
    }

    @Test
    fun `updateConfig changes behavior`() {
        val guard = PrivacyGuard()
        assertFalse(guard.isRemoteAllowed())

        guard.updateConfig(AiAgentPrivacyLevel.PERMISSIVE, AiAgentMode.REMOTE)
        assertTrue(guard.isRemoteAllowed())
    }
}
