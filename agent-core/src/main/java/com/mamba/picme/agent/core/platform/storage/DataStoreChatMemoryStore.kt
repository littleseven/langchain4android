package com.mamba.picme.agent.core.platform.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessage
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.ToolExecutionResultMessage
import com.mamba.data.message.UserMessage
import com.mamba.chat.ChatMemoryStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.chatMemoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_memory")

/**
 * LangChain4j ChatMemoryStore 的 DataStore 持久化实现
 *
 * 将对话历史以 JSON 格式持久化到 Android DataStore。
 * 充当 [com.mamba.chat.MessageWindowChatMemory] 的后端存储层。
 *
 * **线程模型**：使用 runBlocking 桥接同步 → 异步，内部由 DataStore 保证原子性。
 */
class DataStoreChatMemoryStore(private val context: Context) : ChatMemoryStore {

    private val tag = "DataStoreChatMemoryStore"
    private val dataStore = context.chatMemoryDataStore

    override fun getMessages(memoryId: Any): MutableList<ChatMessage> {
        val key = stringPreferencesKey("memory_$memoryId")
        return try {
            val jsonStr = runBlocking {
                dataStore.data.map { prefs -> prefs[key] ?: "[]" }.first()
            }
            if (jsonStr == "[]") {
                mutableListOf()
            } else {
                parseMessages(jsonStr)
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to load messages for memoryId=$memoryId", e)
            mutableListOf()
        }
    }

    override fun updateMessages(memoryId: Any, messages: MutableList<ChatMessage>) {
        val key = stringPreferencesKey("memory_$memoryId")
        try {
            val jsonStr = encodeMessages(messages)
            runBlocking {
                dataStore.edit { prefs -> prefs[key] = jsonStr }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to save messages for memoryId=$memoryId", e)
        }
    }

    override fun deleteMessages(memoryId: Any) {
        val key = stringPreferencesKey("memory_$memoryId")
        try {
            runBlocking {
                dataStore.edit { prefs -> prefs.remove(key) }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to delete messages for memoryId=$memoryId", e)
        }
    }

    // ── JSON 序列化 ─────────────────────────────────────────

    private fun encodeMessages(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val obj = JSONObject()
            when (message) {
                is UserMessage -> {
                    obj.put("type", "user")
                    obj.put("content", message.singleText())
                }
                is AiMessage -> {
                    obj.put("type", "assistant")
                    obj.put("content", message.text() ?: "")
                }
                is SystemMessage -> {
                    obj.put("type", "system")
                    obj.put("content", message.text())
                }
                is ToolExecutionResultMessage -> {
                    obj.put("type", "tool")
                    obj.put("content", message.text())
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseMessages(jsonStr: String): MutableList<ChatMessage> {
        return try {
            val array = JSONArray(jsonStr)
            val messages = mutableListOf<ChatMessage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.optString("type", "")
                val content = obj.optString("content", "")
                when (type) {
                    "user" -> messages.add(UserMessage.from(content))
                    "assistant" -> messages.add(AiMessage.from(content))
                    "system" -> messages.add(SystemMessage.from(content))
                    "tool" -> messages.add(
                        ToolExecutionResultMessage.from(
                            com.mamba.tool.ToolExecutionRequest.builder()
                                .id("")
                                .name("")
                                .arguments("{}")
                                .build(),
                            content
                        )
                    )
                }
            }
            messages
        } catch (e: Exception) {
            Logger.w(tag, "Failed to parse messages JSON", e)
            mutableListOf()
        }
    }
}
