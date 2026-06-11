package com.mamba.picme.data.remote.kimi

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Kimi Coding API 请求/响应数据模型
 *
 * 基于 Claude API 格式设计，兼容 Kimi Coding 服务。
 * Endpoint: https://api.kimi.com/coding/v1/messages
 *
 * @see <a href="https://platform.moonshot.cn">Kimi 开放平台</a>
 */

/**
 * Kimi Coding 消息请求
 *
 * @property model 模型 ID，如 "kimi-for-coding"
 * @property messages 消息列表（必须包含至少一条 user 消息）
 * @property system 系统提示词（可选，Claude 格式支持顶层 system 字段）
 * @property maxTokens 最大生成 token 数
 * @property temperature 控制随机性，0-1 之间
 * @property stream 是否开启流式响应
 */
@JsonClass(generateAdapter = true)
data class KimiCodingRequest(
    val model: String,
    val messages: List<KimiCodingMessage>,
    val system: String? = null,
    @Json(name = "max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Double = 0.3,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class KimiCodingMessage(
    val role: String,
    val content: String
)

/**
 * Kimi Coding 消息响应（非流式）
 *
 * 与 Claude API 格式一致。
 */
@JsonClass(generateAdapter = true)
data class KimiCodingResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<KimiCodingContentBlock>,
    val model: String,
    @Json(name = "stop_reason")
    val stopReason: String?,
    val usage: KimiCodingUsage?
)

@JsonClass(generateAdapter = true)
data class KimiCodingContentBlock(
    val type: String,
    val text: String
)

@JsonClass(generateAdapter = true)
data class KimiCodingUsage(
    @Json(name = "input_tokens")
    val inputTokens: Int,
    @Json(name = "output_tokens")
    val outputTokens: Int,
    @Json(name = "total_tokens")
    val totalTokens: Int
)

/**
 * Kimi Coding 错误响应
 */
@JsonClass(generateAdapter = true)
data class KimiCodingErrorResponse(
    val error: KimiCodingErrorDetail?
)

@JsonClass(generateAdapter = true)
data class KimiCodingErrorDetail(
    val message: String,
    val type: String?
)
