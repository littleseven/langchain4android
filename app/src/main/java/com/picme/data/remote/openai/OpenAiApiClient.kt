package com.picme.data.remote.openai

import com.picme.core.common.Logger
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 API 客户端工厂
 *
 * 创建配置好的 Retrofit 实例和 OpenAiApiService。
 * 用于连接 OpenAI 兼容接口（如 tokenhub.tencentmaas.com/v1/）。
 *
 * @param apiKey API Key（以 Bearer 方式认证）
 * @param baseUrl API 基础地址，默认 https://tokenhub.tencentmaas.com/v1/
 * @param enableLogging 是否启用 HTTP 请求日志
 */
class OpenAiApiClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val enableLogging: Boolean = false
) {

    val service: OpenAiApiService by lazy { createService() }

    private fun createService(): OpenAiApiService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }

        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                Logger.d("PicMe:OpenAi", message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            clientBuilder.addInterceptor(loggingInterceptor)
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

        Logger.i("PicMe:OpenAi", "OpenAiApiClient initialized, baseUrl=$baseUrl")
        return retrofit.create(OpenAiApiService::class.java)
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://tokenhub.tencentmaas.com/v1/"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
