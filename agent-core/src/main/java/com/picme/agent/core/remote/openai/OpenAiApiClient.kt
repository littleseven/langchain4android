package com.picme.agent.core.remote.openai

import com.picme.agent.core.Logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
    private val apiKey: String = "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val enableLogging: Boolean = false,
    private val gatewayToken: String? = null
) {

    companion object {
        private const val TAG = "OpenAi"
        private const val DEFAULT_BASE_URL = "https://tokenhub.tencentmaas.com/v1/"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }

    val service: OpenAiApiService by lazy { createService() }

    private fun createService(): OpenAiApiService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")

                // 用户自定义 API Key（如直接调用 OpenAI/Kimi 等）
                if (apiKey.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                // 腾讯云 SCF / Cloudflare AI Gateway 认证头
                gatewayToken?.takeIf { it.isNotBlank() }?.let { token ->
                    requestBuilder.addHeader("X-App-Token", token)
                }

                chain.proceed(requestBuilder.build())
            }

        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                Logger.d(TAG, message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            clientBuilder.addInterceptor(loggingInterceptor)
        }

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        Logger.i(TAG, "OpenAiApiClient initialized, baseUrl=$baseUrl")
        return retrofit.create(OpenAiApiService::class.java)
    }


}
