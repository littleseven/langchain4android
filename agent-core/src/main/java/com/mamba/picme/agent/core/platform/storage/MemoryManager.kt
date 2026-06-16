package com.mamba.picme.agent.core.platform.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.agent.core.api.AiMessage
import com.mamba.picme.agent.core.api.ChatMessage
import com.mamba.picme.agent.core.api.SystemMessage
import com.mamba.picme.agent.core.api.ToolExecutionRequest
import com.mamba.picme.agent.core.api.ToolExecutionResultMessage
import com.mamba.picme.agent.core.api.UserMessage
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.agentMemoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_memory")

/**
 * 对话记忆管理器
 *
 * 负责对话历史的持久化、上下文窗口管理和记忆隔离。
 * 按场景分 session 存储，支持跨会话记忆恢复。
 *
 * @param context Application Context
 */
class MemoryManager(private val context: Context) {

    private val tag = "MemoryManager"
    private val dataStore = context.agentMemoryDataStore

    /**
     * 每个 session 的最大消息数
     */
    private val maxMessagesPerSession = 20

    /**
     * 构建 prompt 时保留的最大历史轮数（一轮 = user + assistant）
     */
    private val maxHistoryRounds = 5

    /**
     * 加载指定 session 的对话历史
     *
     * @param sessionId 会话 ID（如 "camera", "gallery"）
     * @return 消息列表
     */
    suspend fun loadHistory(sessionId: String): List<ChatMessage> {
        return try {
            val key = stringPreferencesKey("memory_$sessionId")
            val jsonStr = dataStore.data.map { preferences ->
                preferences[key] ?: "[]"
            }.first()

            if (jsonStr == "[]") {
                emptyList()
            } else {
                parseMessagesFromJson(jsonStr)
            }
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to load history for session $sessionId", exception)
            emptyList()
        }
    }

    /**
     * 保存对话历史到指定 session
     *
     * @param sessionId 会话 ID
     * @param messages 消息列表
     */
    suspend fun saveHistory(sessionId: String, messages: List<ChatMessage>) {
        try {
            val trimmed = trimToMaxSize(messages)
            val key = stringPreferencesKey("memory_$sessionId")
            val jsonStr = encodeMessagesToJson(trimmed)

            dataStore.edit { preferences ->
                preferences[key] = jsonStr
            }
            Logger.d(tag, "Saved ${trimmed.size} messages to session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to save history for session $sessionId", exception)
        }
    }

    /**
     * 追加消息到指定 session 的历史
     *
     * @param sessionId 会话 ID
     * @param message 新消息
     */
    suspend fun appendMessage(sessionId: String, message: ChatMessage) {
        val history = loadHistory(sessionId).toMutableList()
        history.add(message)
        saveHistory(sessionId, history)
    }

    /**
     * 追加用户输入和助手回复（一轮对话）
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param assistantResponse 助手回复
     */
    suspend fun appendConversation(
        sessionId: String,
        userInput: String,
        assistantResponse: String
    ) {
        val history = loadHistory(sessionId).toMutableList()
        history.add(UserMessage(userInput))
        history.add(AiMessage(assistantResponse))
        saveHistory(sessionId, history)
    }

    /**
     * 清空指定 session 的对话历史
     *
     * @param sessionId 会话 ID
     */
    suspend fun clearHistory(sessionId: String) {
        try {
            val key = stringPreferencesKey("memory_$sessionId")
            dataStore.edit { preferences ->
                preferences.remove(key)
            }
            Logger.i(tag, "Cleared history for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to clear history for session $sessionId", exception)
        }
    }

    /**
     * 清空所有 session 的对话历史
     */
    suspend fun clearAllHistory() {
        try {
            dataStore.edit { preferences ->
                preferences.asMap().keys.filter { it.name.startsWith("memory_") }
                    .forEach { key ->
                        preferences.remove(key)
                    }
            }
            Logger.i(tag, "Cleared all conversation history")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to clear all history", exception)
        }
    }

    /**
     * 构建带历史上下文的 prompt
     *
     * 保留最近 [maxHistoryRounds] 轮对话 + system prompt
     *
     * @param sessionId 会话 ID
     * @param systemPrompt 系统提示词
     * @param userInput 当前用户输入
     * @return 完整消息列表（system + history + user）
     */
    suspend fun buildContextMessages(
        sessionId: String,
        systemPrompt: String,
        userInput: String
    ): List<ChatMessage> {
        val history = loadHistory(sessionId)
        val trimmedHistory = trimToRounds(history, maxHistoryRounds)

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage(systemPrompt))
        messages.addAll(trimmedHistory)
        messages.add(UserMessage(userInput))

        return messages
    }

    /**
     * 裁剪消息列表到最大容量
     */
    private fun trimToMaxSize(messages: List<ChatMessage>): List<ChatMessage> {
        return if (messages.size > maxMessagesPerSession) {
            messages.takeLast(maxMessagesPerSession)
        } else {
            messages
        }
    }

    /**
     * 按轮数裁剪历史（保留最近 N 轮）
     */
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
                is ToolExecutionResultMessage -> {
                    // tool results are part of the assistant turn, skip as a separate pair
                }
                else -> { /* ignore system messages in history */ }
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

    /**
     * 将消息列表编码为 JSON 字符串
     */
    private fun encodeMessagesToJson(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val (role, content) = when (message) {
                is SystemMessage -> "system" to message.text
                is UserMessage -> "user" to message.text
                is AiMessage -> "assistant" to message.text
                is ToolExecutionResultMessage -> "tool" to message.text
            }
            val obj = JSONObject().apply {
                put("role", role)
                put("content", content)
            }
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * 从 JSON 字符串解析消息列表
     */
    private fun parseMessagesFromJson(jsonStr: String): List<ChatMessage> {
        return try {
            val array = JSONArray(jsonStr)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val roleName = obj.getString("role")
                val content = obj.getString("content")
                when (roleName) {
                    "system" -> SystemMessage(content)
                    "assistant" -> AiMessage(content)
                    "tool" -> ToolExecutionResultMessage(
                        toolExecutionRequest = ToolExecutionRequest(
                            id = "",
                            name = "",
                            arguments = "{}"
                        ),
                        text = content
                    )
                    else -> UserMessage(content)
                }
            }
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to parse messages JSON", exception)
            emptyList()
        }
    }
}
