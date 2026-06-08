package com.picme.domain.agent

import com.picme.agent.core.api.context.ChatMessage
import com.picme.agent.core.api.context.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MemoryManager 裁剪逻辑单元测试
 *
 * 复现 trimToRounds 和 trimToMaxSize 逻辑，验证上下文窗口管理。
 */
class MemoryManagerTrimTest {

    private fun trimToMaxSize(messages: List<ChatMessage>, maxSize: Int = 50): List<ChatMessage> {
        return if (messages.size > maxSize) {
            messages.takeLast(maxSize)
        } else {
            messages
        }
    }

    private fun trimToRounds(messages: List<ChatMessage>, rounds: Int): List<ChatMessage> {
        val userAssistantPairs = mutableListOf<Pair<ChatMessage?, ChatMessage?>>()
        var currentUser: ChatMessage? = null

        messages.forEach { message ->
            when (message.role) {
                ChatRole.USER -> {
                    if (currentUser != null) {
                        userAssistantPairs.add(currentUser to null)
                    }
                    currentUser = message
                }
                ChatRole.ASSISTANT -> {
                    userAssistantPairs.add(currentUser to message)
                    currentUser = null
                }
                else -> { }
            }
        }
        if (currentUser != null) {
            userAssistantPairs.add(currentUser to null)
        }

        val recentPairs = userAssistantPairs.takeLast(rounds)
        return recentPairs.flatMap { pair ->
            listOfNotNull(pair.first, pair.second)
        }
    }

    @Test
    fun `trimToMaxSize keeps last N messages`() {
        val messages = (1..60).map { i ->
            ChatMessage(role = if (i % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, content = "msg$i")
        }
        val trimmed = trimToMaxSize(messages, 50)
        assertEquals(50, trimmed.size)
        assertEquals("msg11", trimmed.first().content)
        assertEquals("msg60", trimmed.last().content)
    }

    @Test
    fun `trimToMaxSize preserves small list`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.USER, content = "hi")
        )
        assertEquals(1, trimToMaxSize(messages).size)
    }

    @Test
    fun `trimToRounds keeps recent rounds`() {
        val messages = mutableListOf<ChatMessage>()
        repeat(15) { i ->
            messages.add(ChatMessage(role = ChatRole.USER, content = "u$i"))
            messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = "a$i"))
        }
        val trimmed = trimToRounds(messages, 5)
        assertEquals(10, trimmed.size)
        assertEquals("u10", trimmed[0].content)
        assertEquals("a14", trimmed.last().content)
    }

    @Test
    fun `trimToRounds handles incomplete last round`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.USER, content = "u1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a1"),
            ChatMessage(role = ChatRole.USER, content = "u2")
        )
        val trimmed = trimToRounds(messages, 1)
        assertEquals(1, trimmed.size)
        assertEquals("u2", trimmed[0].content)
    }

    @Test
    fun `trimToRounds ignores system messages`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = "sys"),
            ChatMessage(role = ChatRole.USER, content = "u1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a1")
        )
        val trimmed = trimToRounds(messages, 1)
        assertEquals(2, trimmed.size)
        assertEquals(ChatRole.USER, trimmed[0].role)
    }

    @Test
    fun `trimToRounds with zero rounds returns empty`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.USER, content = "u1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a1")
        )
        assertEquals(0, trimToRounds(messages, 0).size)
    }
}
