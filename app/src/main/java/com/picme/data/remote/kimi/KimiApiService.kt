package com.picme.data.remote.kimi

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Kimi API Retrofit 服务接口
 *
 * Base URL: https://api.moonshot.cn/v1/
 */
interface KimiApiService {

    @POST("chat/completions")
    suspend fun chatCompletions(
        @Body request: KimiChatRequest
    ): Response<KimiChatResponse>
}
