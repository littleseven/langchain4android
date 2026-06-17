package com.mamba.picme.data.remote.claude

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

    @POST("messages")
    suspend fun messages(
        @Body request: ClaudeCodingRequest
    ): Response<ClaudeCodingResponse>
}
