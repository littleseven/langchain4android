package com.mamba.picme.agent.core.platform.llm.remote.claude

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Claude Coding API Retrofit 服务接口
 *
 * Base URL: https://api.anthropic.com/v1/
 *
 * 提供 Claude API 兼容的 LLM 推理能力。
 */
interface ClaudeCodingApiService {

    /**
     * 发送消息请求到 Claude Coding API
     *
     * @param request 包含 model、messages、system 等字段的请求体
     * @return 非流式响应，包含生成的文本内容
     */
    @POST("messages")
    suspend fun messages(
        @Body request: ClaudeCodingRequest
    ): Response<ClaudeCodingResponse>
}
