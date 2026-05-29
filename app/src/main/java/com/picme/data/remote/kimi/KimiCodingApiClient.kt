package com.picme.data.remote.kimi

import com.picme.core.common.Logger
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Kimi Coding API 客户端工厂
 *
 * 创建配置好的 Retrofit 实例和 KimiCodingApiService。
 * 用于连接 Kimi Coding API（Claude 格式），作为云端 LLM 备选方案。
 *
 * @param apiKey Kimi Coding API Key（以 sk-kimi- 或 sk- 开头）
 * @param baseUrl API 基础地址，默认 https://api.kimi.com/coding/v1/
 * @param enableLogging 是否启用 HTTP 请求日志（Debug 模式建议开启）
 */
class KimiCodingApiClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val enableLogging: Boolean = false
) {

    val service: KimiCodingApiService by lazy { createService() }

    private fun createService(): KimiCodingApiService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }

        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                Logger.d("PicMe:KimiCoding", message)
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

        Logger.i("PicMe:KimiCoding", "KimiCodingApiClient initialized, baseUrl=$baseUrl")
        return retrofit.create(KimiCodingApiService::class.java)
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.kimi.com/coding/v1/"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
