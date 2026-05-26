package com.picme.domain.agent

import com.picme.domain.model.AiAgentMode
import com.picme.domain.model.AiAgentPrivacyLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrivacyGuard 单元测试
 *
 * 验证隐私级别与 Agent 模式的组合策略。
 */
class PrivacyGuardTest {

    @Test
    fun `strict local mode allows local only`() {
        val guard = PrivacyGuard(
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            agentMode = AiAgentMode.LOCAL
        )
        guard.assertLocalOnly()
        assertFalse(guard.isRemoteAllowed())
    }

    @Test(expected = SecurityException::class)
    fun `strict remote mode throws security exception`() {
        val guard = PrivacyGuard(
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            agentMode = AiAgentMode.REMOTE
        )
        guard.assertLocalOnly()
    }

    @Test
    fun `permissive remote mode allows remote`() {
        val guard = PrivacyGuard(
            privacyLevel = AiAgentPrivacyLevel.PERMISSIVE,
            agentMode = AiAgentMode.REMOTE
        )
        assertTrue(guard.isRemoteAllowed())
    }

    @Test
    fun `permissive local mode disallows remote`() {
        val guard = PrivacyGuard(
            privacyLevel = AiAgentPrivacyLevel.PERMISSIVE,
            agentMode = AiAgentMode.LOCAL
        )
        assertFalse(guard.isRemoteAllowed())
    }

    @Test
    fun `off mode in strict throws on assert`() {
        val guard = PrivacyGuard(
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            agentMode = AiAgentMode.OFF
        )
        assertFalse(guard.isRemoteAllowed())
    }

    @Test
    fun `updateConfig changes behavior`() {
        val guard = PrivacyGuard(
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            agentMode = AiAgentMode.LOCAL
        )
        assertFalse(guard.isRemoteAllowed())

        guard.updateConfig(AiAgentPrivacyLevel.PERMISSIVE, AiAgentMode.REMOTE)
        assertTrue(guard.isRemoteAllowed())
    }
}
