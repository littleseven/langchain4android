package com.mamba.picme.agent.core.platform.llm.remote.kimi

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Kimi Coding API Retrofit 服务接口
 *
 * Base URL: https://api.kimi.com/coding/v1/
 *
 * 通过 Kimi Code 会员权益，提供 Claude API 兼容的 LLM 推理能力。
 *
 * @see <a href="https://platform.moonshot.cn">Kimi 开放平台</a>
 */
interface KimiCodingApiService {

    /**
     * 发送消息请求到 Kimi Coding API
     *
     * @param request 包含 model、messages、system 等字段的请求体
     * @return 非流式响应，包含生成的文本内容
     */
    @POST("messages")
    suspend fun messages(
        @Body request: KimiCodingRequest
    ): Response<KimiCodingResponse>
}
