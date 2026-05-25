package com.picme.data.remote.kimi

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Kimi API 请求/响应数据模型
 *
 * 基于 Moonshot AI OpenAI-compatible API 设计。
 * Endpoint: https://api.moonshot.cn/v1/chat/completions
 */

@JsonClass(generateAdapter = true)
data class KimiChatRequest(
    val model: String,
    val messages: List<KimiMessage>,
    val temperature: Double = 0.3,
    @Json(name = "max_tokens")
    val maxTokens: Int = 1024,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class KimiMessage(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class KimiChatResponse(
    val id: String,
    @Json(name = "object")
    val objectType: String,
    val created: Long,
    val model: String,
    val choices: List<KimiChoice>,
    val usage: KimiUsage?
)

@JsonClass(generateAdapter = true)
data class KimiChoice(
    val index: Int,
    val message: KimiMessage,
    @Json(name = "finish_reason")
    val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class KimiUsage(
    @Json(name = "prompt_tokens")
    val promptTokens: Int,
    @Json(name = "completion_tokens")
    val completionTokens: Int,
    @Json(name = "total_tokens")
    val totalTokens: Int
)

@JsonClass(generateAdapter = true)
data class KimiErrorResponse(
    val error: KimiErrorDetail?
)

@JsonClass(generateAdapter = true)
data class KimiErrorDetail(
    val message: String,
    val type: String?
)
