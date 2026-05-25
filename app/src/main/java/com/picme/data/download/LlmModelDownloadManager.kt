package com.picme.data.download

import android.content.Context
import android.util.Log
import com.picme.R
import com.picme.core.common.Logger
import com.picme.domain.model.ModelCategory
import com.picme.domain.model.TagTranslations
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
     * ASR 模型固定文件列表
     */
    private val ASR_MODEL_FILES = listOf(
        "whisper.mnn",
        "vocab.json"
    )

    /**
     * TTS 模型固定文件列表
     */
    private val TTS_MODEL_FILES = listOf(
        "config.json",
        "tts.mnn",
        "vocab.txt"
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
        // 1. 尝试从网络获取 MNN 官方模型市场（优先网络）
        val remoteData = fetchMarketData()
        if (remoteData.models.isNotEmpty()) {
            // 保存到本地缓存
            saveCachedMarketData(remoteData)
            return@withContext remoteData
        }

        // 2. 网络失败，尝试从本地缓存读取
        val cachedData = loadCachedMarketData()
        if (cachedData != null && cachedData.models.isNotEmpty()) {
            Logger.i("PicMe:Download", "Using cached market data with ${cachedData.models.size} models")
            return@withContext cachedData
        }

        // 3. 回退到本地 raw 资源
        return@withContext ModelMarketData(loadLocalModels(), DEFAULT_TAG_TRANSLATIONS)
    }

    /**
     * 刷新模型市场数据（强制从网络获取并更新缓存）
     */
    suspend fun refreshMarketData(): ModelMarketData = withContext(Dispatchers.IO) {
        val remoteData = fetchMarketData()
        if (remoteData.models.isNotEmpty()) {
            saveCachedMarketData(remoteData)
            Logger.i("PicMe:Download", "Market data refreshed: ${remoteData.models.size} models")
        }
        remoteData
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
                parseMarketJson(body)
            }
        } catch (e: Exception) {
            Logger.w("PicMe:Download", "Failed to fetch model market, fallback to local", e)
            ModelMarketData(emptyList(), DEFAULT_TAG_TRANSLATIONS)
        }
    }

    /**
     * 解析 model_market.json 响应体
     */
    private fun parseMarketJson(jsonBody: String): ModelMarketData {
        val json = JSONObject(jsonBody)

        // 解析标签翻译
        val tagTranslations = mutableMapOf<String, String>()
        val tagTranslationsObj = json.optJSONObject("tagTranslations")
        if (tagTranslationsObj != null) {
            tagTranslationsObj.keys().forEach { key ->
                tagTranslations[key] = tagTranslationsObj.getString(key)
            }
        }

        val result = mutableListOf<ModelConfig>()

        // 解析主模型列表
        val modelsArray = json.optJSONArray("models")
        if (modelsArray != null) {
            for (i in 0 until modelsArray.length()) {
                parseModelObject(modelsArray.getJSONObject(i))?.let { result.add(it) }
            }
        }

        // 解析 TTS 模型
        val ttsArray = json.optJSONArray("tts_models")
        if (ttsArray != null) {
            for (i in 0 until ttsArray.length()) {
                parseModelObject(ttsArray.getJSONObject(i), defaultTags = listOf("TTS"))?.let { result.add(it) }
            }
        }

        // 解析 ASR 模型
        val asrArray = json.optJSONArray("asr_models")
        if (asrArray != null) {
            for (i in 0 until asrArray.length()) {
                parseModelObject(asrArray.getJSONObject(i), defaultTags = listOf("ASR"))?.let { result.add(it) }
            }
        }

        Logger.i("PicMe:Download", "Parsed ${result.size} models from MNN market")
        return ModelMarketData(result, tagTranslations)
    }

    /**
     * 解析单个模型 JSON 对象
     */
    private fun parseModelObject(obj: JSONObject, defaultTags: List<String> = emptyList()): ModelConfig? {
        val modelName = obj.optString("modelName", "")
        if (modelName.isBlank()) return null

        val sourcesObj = obj.optJSONObject("sources")
        val sources = mutableMapOf<String, String>()
        if (sourcesObj != null) {
            sourcesObj.keys().forEach { key ->
                sources[key] = sourcesObj.getString(key)
            }
        }
        if (sources.isEmpty()) return null

        // 解析标签
        val tagsArray = obj.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).map { tagsArray.getString(it) }
        } else defaultTags

        // 从 JSON 读取文件列表，若不存在则根据模型类型推断
        val filesArray = obj.optJSONArray("files")
        val modelFiles = if (filesArray != null) {
            (0 until filesArray.length()).map { filesArray.getString(it) }
        } else {
            getModelFilesByTags(modelName, tags)
        }

        return ModelConfig(
            id = modelName.lowercase().replace("-mnn", "").replace(".", "-"),
            name = modelName,
            description = buildDescription(obj),
            size = obj.optLong("file_size", 0L),
            sources = sources,
            files = modelFiles,
            tags = tags
        )
    }

    /**
     * 本地缓存文件路径
     */
    private val cacheFile: File
        get() = File(context.cacheDir, "model_market_cache.json")

    /**
     * 从本地缓存加载市场数据
     */
    private fun loadCachedMarketData(): ModelMarketData? {
        return try {
            val file = cacheFile
            if (!file.exists() || !file.canRead()) return null

            // 检查缓存是否过期（7天）
            val maxAgeMs = 7L * 24 * 60 * 60 * 1000
            if (System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
                Logger.d("PicMe:Download", "Market cache expired")
                return null
            }

            val jsonBody = file.readText()
            parseMarketJson(jsonBody)
        } catch (e: Exception) {
            Logger.w("PicMe:Download", "Failed to load cached market data", e)
            null
        }
    }

    /**
     * 保存市场数据到本地缓存
     */
    private fun saveCachedMarketData(data: ModelMarketData) {
        try {
            val modelsArray = JSONArray()
            data.models.forEach { model ->
                modelsArray.put(JSONObject().apply {
                    put("modelName", model.name)
                    put("file_size", model.size)
                    put("vendor", "")
                    put("tags", JSONArray(model.tags))
                    put("sources", JSONObject(model.sources.toMap()))
                    put("files", JSONArray(model.files))
                })
            }
            val json = JSONObject().apply {
                put("tagTranslations", JSONObject(data.tagTranslations.toMap()))
                put("models", modelsArray)
            }
            cacheFile.writeText(json.toString())
            Logger.d("PicMe:Download", "Market data cached: ${data.models.size} models")
        } catch (e: Exception) {
            Logger.w("PicMe:Download", "Failed to cache market data", e)
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

                val modelId = obj.getString("id")
                val modelTags = if (obj.has("tags")) {
                    val tagsArray = obj.getJSONArray("tags")
                    (0 until tagsArray.length()).map { tagsArray.getString(it) }
                } else emptyList()
                val modelFiles = if (obj.has("files")) {
                    val filesArray = obj.getJSONArray("files")
                    (0 until filesArray.length()).map { filesArray.getString(it) }
                } else {
                    getModelFiles(modelId)
                }

                ModelConfig(
                    id = modelId,
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    size = obj.getLong("size"),
                    sources = sources,
                    files = modelFiles,
                    tags = modelTags
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

        // ASR 模型：检查目录中是否存在 .mnn 文件（避免硬编码文件名）
        if (modelId.contains("zipformer", ignoreCase = true) ||
            modelId.contains("whisper", ignoreCase = true)
        ) {
            return modelDir.walkTopDown().any { it.name.endsWith(".mnn") }
        }

        val expectedFiles = getModelFiles(modelId)
        return expectedFiles.all { fileName ->
            File(modelDir, fileName).exists()
        }
    }

    /**
     * 根据模型 ID 获取对应的文件列表
     */
    private fun getModelFiles(modelId: String): List<String> {
        return when {
            modelId.contains("whisper", ignoreCase = true) -> ASR_MODEL_FILES
            else -> LLM_MODEL_FILES
        }
    }

    /**
     * 根据模型标签推断文件列表
     */
    private fun getModelFilesByTags(modelId: String, tags: List<String>): List<String> {
        return when {
            tags.any { it.equals("ASR", ignoreCase = true) } -> ASR_MODEL_FILES
            tags.any { it.equals("TTS", ignoreCase = true) } -> TTS_MODEL_FILES
            modelId.contains("whisper", ignoreCase = true) -> ASR_MODEL_FILES
            else -> LLM_MODEL_FILES
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

            // 优先使用模型配置中的文件列表（从模型市场或本地配置获取）
            val expectedFiles = config.files.takeIf { it.isNotEmpty() } ?: getModelFiles(modelId)
            for (fileName in expectedFiles) {
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
                                    _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, totalDownloaded, config.size)) }
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
) {
    /**
     * 便捷属性：判断是否为小模型 (< 50MB)
     */
    val isSmallModel: Boolean get() = size < 50 * 1024 * 1024

    /**
     * 获取模型的第一个分类标签，用于确定所属 Tab
     */
    fun primaryCategory(): ModelCategory {
        val firstTag = tags.firstOrNull { it != "TTS" && it != "ASR" }
            ?: tags.firstOrNull()
            ?: "Chat"
        return ModelCategory(firstTag)
    }

    /**
     * 获取模型类型的简短标签
     */
    fun getTypeLabel(tagTranslations: TagTranslations): String {
        val primaryTag = tags.firstOrNull() ?: "Chat"
        return tagTranslations[primaryTag] ?: primaryTag
    }
}

/**
 * 模型市场数据（包含模型列表和标签翻译）
 */
data class ModelMarketData(
    val models: List<ModelConfig>,
    val tagTranslations: TagTranslations
) {
    /**
     * 获取所有可用的分类标签（按 tagTranslations 顺序）
     */
    fun getCategories(): List<ModelCategory> {
        val allTags = models.flatMap { it.tags }.toSet()
        // 按 tagTranslations 中定义的顺序排列
        val ordered = tagTranslations.keys
            .filter { it in allTags }
            .map { ModelCategory(it) }
        // 补充未在 tagTranslations 中定义的标签
        val remaining = allTags
            .filter { it !in tagTranslations.keys }
            .map { ModelCategory(it) }
        return ordered + remaining
    }

    /**
     * 按分类标签分组模型
     */
    fun groupByCategory(): Map<ModelCategory, List<ModelConfig>> {
        val categories = getCategories()
        val result = mutableMapOf<ModelCategory, List<ModelConfig>>()

        // 每个模型放入其第一个匹配的分类
        val assigned = mutableSetOf<String>()
        for (category in categories) {
            val categoryModels = models.filter { model ->
                model.id !in assigned && category.tag in model.tags
            }
            if (categoryModels.isNotEmpty()) {
                result[category] = categoryModels
                assigned.addAll(categoryModels.map { it.id })
            }
        }

        // 未分类的放入 "All"
        val unassigned = models.filter { it.id !in assigned }
        if (unassigned.isNotEmpty()) {
            result[ModelCategory.ALL] = unassigned
        }

        return result
    }
}

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
