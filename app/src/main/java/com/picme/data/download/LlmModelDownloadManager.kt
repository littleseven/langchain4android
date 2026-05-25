package com.picme.data.download

import android.content.Context
import android.util.Log
import com.picme.R
import com.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM 模型下载管理器
 *
 * 从 MNN 官方模型市场获取模型列表，支持从 ModelScope（魔搭）下载 MNN-LLM 模型。
 * 参考 MNN Chat 官方实现：使用 https://meta.alicdn.com/data/mnn/apis/model_market.json 获取模型配置。
 */
class LlmModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadDir: File
        get() = File(context.filesDir, "llm_models").also { it.mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates = _downloadStates.asStateFlow()

    private val activeDownloads = mutableMapOf<String, okhttp3.Call>()

    /**
     * MNN-LLM 模型固定文件列表
     */
    private val LLM_MODEL_FILES = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "tokenizer.txt"
    )

    /**
     * 加载可用模型配置
     *
     * 优先从 MNN 官方模型市场获取，失败时回退到本地配置。
     * 注意：此函数包含网络请求，必须在 IO 线程调用。
     */
    suspend fun loadAvailableModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        loadMarketData().models
    }

    /**
     * 加载模型市场数据（包含标签翻译）
     */
    suspend fun loadMarketData(): ModelMarketData = withContext(Dispatchers.IO) {
        // 1. 尝试从网络获取 MNN 官方模型市场
        val remoteData = fetchMarketData()
        if (remoteData.models.isNotEmpty()) {
            return@withContext remoteData
        }

        // 2. 回退到本地配置
        return@withContext ModelMarketData(loadLocalModels(), DEFAULT_TAG_TRANSLATIONS)
    }

    /**
     * 从 MNN 官方模型市场获取模型列表和标签翻译
     */
    private fun fetchMarketData(): ModelMarketData {
        return try {
            val request = Request.Builder()
                .url(MODEL_MARKET_URL)
                .header("User-Agent", "PicMe-Android/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.w("PicMe:Download", "Model market fetch failed: HTTP ${response.code}")
                    return ModelMarketData(emptyList(), DEFAULT_TAG_TRANSLATIONS)
                }

                val body = response.body?.string() ?: return ModelMarketData(emptyList(), DEFAULT_TAG_TRANSLATIONS)
                val json = JSONObject(body)
                val modelsArray = json.getJSONArray("models")

                // 解析标签翻译
                val tagTranslations = mutableMapOf<String, String>()
                val tagTranslationsObj = json.optJSONObject("tagTranslations")
                if (tagTranslationsObj != null) {
                    tagTranslationsObj.keys().forEach { key ->
                        tagTranslations[key] = tagTranslationsObj.getString(key)
                    }
                }

                val result = mutableListOf<ModelConfig>()
                for (i in 0 until modelsArray.length()) {
                    val obj = modelsArray.getJSONObject(i)
                    val modelName = obj.getString("modelName")

                    // 只加载 MNN 格式模型
                    if (!modelName.endsWith("-MNN")) continue

                    val sourcesObj = obj.getJSONObject("sources")
                    val sources = mutableMapOf<String, String>()
                    sourcesObj.keys().forEach { key ->
                        sources[key] = sourcesObj.getString(key)
                    }

                    // 解析标签
                    val tagsArray = obj.optJSONArray("tags")
                    val tags = if (tagsArray != null) {
                        (0 until tagsArray.length()).map { tagsArray.getString(it) }
                    } else emptyList()

                    result.add(
                        ModelConfig(
                            id = modelName.lowercase().replace("-mnn", "").replace(".", "-"),
                            name = modelName,
                            description = buildDescription(obj),
                            size = obj.optLong("file_size", 0L),
                            sources = sources,
                            files = LLM_MODEL_FILES,
                            tags = tags
                        )
                    )
                }

                Logger.i("PicMe:Download", "Loaded ${result.size} models from MNN market")
                ModelMarketData(result, tagTranslations)
            }
        } catch (e: Exception) {
            Logger.w("PicMe:Download", "Failed to fetch model market, fallback to local", e)
            ModelMarketData(emptyList(), DEFAULT_TAG_TRANSLATIONS)
        }
    }

    /**
     * 从本地 raw 资源加载模型配置
     */
    private fun loadLocalModels(): List<ModelConfig> {
        return try {
            val json = context.resources.openRawResource(R.raw.llm_models).bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val sourcesObj = obj.getJSONObject("sources")
                val sources = mutableMapOf<String, String>()
                sourcesObj.keys().forEach { key ->
                    sources[key] = sourcesObj.getString(key)
                }
                ModelConfig(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    size = obj.getLong("size"),
                    sources = sources,
                    files = LLM_MODEL_FILES
                )
            }
        } catch (e: Exception) {
            Logger.e("PicMe:Download", "Failed to load local model config", e)
            emptyList()
        }
    }

    private fun buildDescription(obj: JSONObject): String {
        val vendor = obj.optString("vendor", "")
        val tags = obj.optJSONArray("tags")
        val tagList = if (tags != null) {
            (0 until tags.length()).map { tags.getString(it) }
        } else emptyList()

        return buildString {
            append(vendor)
            if (tagList.isNotEmpty()) {
                append(" ")
                append(tagList.joinToString(", "))
            }
            append(" 本地推理模型")
        }
    }

    /**
     * 检查模型是否已下载完成
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val modelDir = File(downloadDir, modelId)
        if (!modelDir.exists()) return false

        return LLM_MODEL_FILES.all { fileName ->
            File(modelDir, fileName).exists()
        }
    }

    /**
     * 获取已下载模型的目录路径
     */
    fun getModelDir(modelId: String): String? {
        return if (isModelDownloaded(modelId)) {
            File(downloadDir, modelId).absolutePath
        } else {
            null
        }
    }

    /**
     * 获取已下载模型列表
     */
    suspend fun getDownloadedModels(): List<ModelConfig> {
        return loadAvailableModels().filter { isModelDownloaded(it.id) }
    }

    /**
     * 删除已下载的模型
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(downloadDir, modelId)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            _downloadStates.update { it - modelId }
            Logger.i("PicMe:Download", "Model deleted: $modelId")
            true
        } catch (e: Exception) {
            Logger.e("PicMe:Download", "Failed to delete model: $modelId", e)
            false
        }
    }

    /**
     * 取消正在进行的下载
     */
    fun cancelDownload(modelId: String) {
        activeDownloads[modelId]?.cancel()
        activeDownloads.remove(modelId)
        _downloadStates.update { current ->
            current + (modelId to DownloadState(modelId, DownloadStatus.CANCELLED, 0, 0))
        }
    }

    /**
     * 下载模型
     *
     * 策略：仅使用 ModelScope（魔搭）源，国内访问最稳定。
     *
     * @param modelId 模型 ID
     * @return Flow<DownloadProgress> 下载进度流
     */
    fun downloadModel(modelId: String, modelConfig: ModelConfig? = null): Flow<DownloadProgress> = flow {
        val config = modelConfig ?: loadAvailableModels().find { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model: $modelId")

        val modelDir = File(downloadDir, modelId).also { it.mkdirs() }
        _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, 0, config.size)) }

        try {
            // 仅使用 ModelScope 源
            val repoPath = config.sources["ModelScope"]
                ?: config.sources["modelscope"]
                ?: throw IOException("ModelScope source not available for $modelId")

            Logger.i("PicMe:Download", "Downloading model $modelId from ModelScope: $repoPath")

            var totalDownloaded = 0L

            for (fileName in LLM_MODEL_FILES) {
                if (activeDownloads[modelId]?.isCanceled() == true) {
                    throw IOException("Download cancelled")
                }

                val url = buildModelScopeUrl(repoPath, fileName)
                val destFile = File(modelDir, fileName)

                Logger.d("PicMe:Download", "Downloading $fileName from $url")

                if (destFile.exists() && destFile.length() > 0) {
                    totalDownloaded += destFile.length()
                    emit(DownloadProgress(modelId, totalDownloaded, config.size, DownloadStatus.DOWNLOADING))
                    continue
                }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PicMe-Android/1.0")
                    .build()
                val call = client.newCall(request)
                activeDownloads[modelId] = call

                call.execute().use { response ->
                    Logger.d("PicMe:Download", "Response: HTTP ${response.code} for $fileName")
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        throw IOException("HTTP ${response.code} for $fileName, url=$url, body=$errorBody")
                    }

                    val body = response.body
                        ?: throw IOException("Empty response for $fileName from $url")

                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead: Int
                            var lastEmitTime = System.currentTimeMillis()
                            var lastEmitBytes = totalDownloaded

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (call.isCanceled()) {
                                    throw IOException("Download cancelled")
                                }
                                output.write(buffer, 0, bytesRead)
                                totalDownloaded += bytesRead

                                // [Perf] 限制进度 emit 频率：每 500ms 或每下载 1MB 才 emit 一次
                                val now = System.currentTimeMillis()
                                val bytesSinceLastEmit = totalDownloaded - lastEmitBytes
                                if (now - lastEmitTime > 500 || bytesSinceLastEmit > 1_048_576) {
                                    emit(DownloadProgress(modelId, totalDownloaded, config.size, DownloadStatus.DOWNLOADING))
                                    lastEmitTime = now
                                    lastEmitBytes = totalDownloaded
                                }
                            }
                        }
                    }
                }
            }

            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.COMPLETED, config.size, config.size)) }
            emit(DownloadProgress(modelId, config.size, config.size, DownloadStatus.COMPLETED))
            Logger.i("PicMe:Download", "Model download completed: $modelId")

        } catch (e: Exception) {
            if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.CANCELLED, 0, config.size)) }
                emit(DownloadProgress(modelId, 0, config.size, DownloadStatus.CANCELLED))
                return@flow
            }
            val errorMsg = e.message ?: e.javaClass.simpleName
            Logger.e("PicMe:Download", "Download failed for $modelId: $errorMsg")
            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.FAILED, 0, config.size)) }
            emit(DownloadProgress(modelId, 0, config.size, DownloadStatus.FAILED))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildModelScopeUrl(repoPath: String, fileName: String): String {
        return "https://modelscope.cn/models/$repoPath/resolve/master/$fileName"
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val MODEL_MARKET_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json"

        /**
         * 默认标签翻译（MNN 官方 tagTranslations 的本地回退）
         */
        val DEFAULT_TAG_TRANSLATIONS = mapOf(
            "Vision" to "图像理解",
            "Video" to "视频理解",
            "Audio" to "音频理解",
            "Code" to "代码",
            "Math" to "数学",
            "ImageGen" to "文生图",
            "AudioGen" to "音频生成",
            "Think" to "深度思考",
            "Chat" to "对话",
            "Safety" to "安全",
            "NPU" to "NPU加速"
        )
    }
}

/**
 * 模型配置
 */
data class ModelConfig(
    val id: String,
    val name: String,
    val description: String,
    val size: Long,
    val sources: Map<String, String>,
    val files: List<String>,
    val tags: List<String> = emptyList()
)

/**
 * 模型市场数据（包含模型列表和标签翻译）
 */
data class ModelMarketData(
    val models: List<ModelConfig>,
    val tagTranslations: Map<String, String>
)

/**
 * 下载进度
 */
data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: DownloadStatus
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f
}

/**
 * 下载状态
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 内部下载状态
 */
data class DownloadState(
    val modelId: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long
)
