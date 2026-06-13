package com.mamba.picme.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogModuleConfigTest {

    @Test
    fun chatTagResolvedToChatModule() {
        assertEquals(LogModule.CHAT, LogModule.fromTag("ChatViewModel"))
        assertEquals(LogModule.CHAT, LogModule.fromTag("ChatScreen"))
        assertEquals(LogModule.CHAT, LogModule.fromTag("AgentCommandParser"))
    }

    @Test
    fun defaultConfigEnablesChat() {
        val config = LogModuleConfig.default()
        assertTrue(config.isEnabled(LogModule.CHAT))
        assertTrue(config.isTagEnabled("ChatViewModel"))
    }

    @Test
    fun toggleDisablesChatTag() {
        val config = LogModuleConfig.default()
            .toggle(LogModule.CHAT, false)
        assertTrue(!config.isTagEnabled("ChatViewModel"))
    }
}
