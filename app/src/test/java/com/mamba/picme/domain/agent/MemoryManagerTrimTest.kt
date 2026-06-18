package com.mamba.picme.domain.agent

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            when (message) {
                is UserMessage -> {
                    if (currentUser != null) {
                        userAssistantPairs.add(currentUser to null)
                    }
                    currentUser = message
                }
                is AiMessage -> {
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
            if (i % 2 == 1) UserMessage.from("msg$i") else AiMessage.from("msg$i")
        }
        val trimmed = trimToMaxSize(messages, 50)
        assertEquals(50, trimmed.size)
        assertEquals("msg11", (trimmed.first() as UserMessage).text())
        assertEquals("msg60", (trimmed.last() as AiMessage).text())
    }

    @Test
    fun `trimToMaxSize preserves small list`() {
        val messages = listOf(
            UserMessage.from("hi")
        )
        assertEquals(1, trimToMaxSize(messages).size)
    }

    @Test
    fun `trimToRounds keeps recent rounds`() {
        val messages = mutableListOf<ChatMessage>()
        repeat(15) { i ->
            messages.add(UserMessage.from("u$i"))
            messages.add(AiMessage.from("a$i"))
        }
        val trimmed = trimToRounds(messages, 5)
        assertEquals(10, trimmed.size)
        assertEquals("u10", (trimmed[0] as UserMessage).text())
        assertEquals("a14", (trimmed.last() as AiMessage).text())
    }

    @Test
    fun `trimToRounds handles incomplete last round`() {
        val messages = listOf(
            UserMessage.from("u1"),
            AiMessage.from("a1"),
            UserMessage.from("u2")
        )
        val trimmed = trimToRounds(messages, 1)
        assertEquals(1, trimmed.size)
        assertEquals("u2", (trimmed[0] as UserMessage).text())
    }

    @Test
    fun `trimToRounds ignores system messages`() {
        val messages = listOf(
            SystemMessage.from("sys"),
            UserMessage.from("u1"),
            AiMessage.from("a1")
        )
        val trimmed = trimToRounds(messages, 1)
        assertEquals(2, trimmed.size)
        assertTrue(trimmed[0] is UserMessage)
    }

    @Test
    fun `trimToRounds with zero rounds returns empty`() {
        val messages = listOf(
            UserMessage.from("u1"),
            AiMessage.from("a1")
        )
        assertEquals(0, trimToRounds(messages, 0).size)
    }
}