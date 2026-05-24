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
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * LLM 模型下载管理器
 *
 * 负责从 HuggingFace/ModelScope 下载 MNN-LLM 模型文件到本地存储。
 * 支持下载进度追踪、取消下载、删除已下载模型。
 */
class LlmModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val downloadDir: File
        get() = File(context.filesDir, "llm_models").also { it.mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates = _downloadStates.asStateFlow()

    private val activeDownloads = mutableMapOf<String, okhttp3.Call>()

    /**
     * 加载可用模型配置
     */
    fun loadAvailableModels(): List<ModelConfig> {
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
                    files = (0 until obj.getJSONArray("files").length()).map {
                        obj.getJSONArray("files").getString(it)
                    }
                )
            }
        } catch (e: Exception) {
            Logger.e("PicMe:Download", "Failed to load model config", e)
            emptyList()
        }
    }

    /**
     * 检查模型是否已下载完成
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val modelDir = File(downloadDir, modelId)
        if (!modelDir.exists()) return false

        val config = loadAvailableModels().find { it.id == modelId } ?: return false
        return config.files.all { fileName ->
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
    fun getDownloadedModels(): List<ModelConfig> {
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
     * @param modelId 模型 ID
     * @param source 下载源，如 "huggingface" 或 "modelscope"
     * @return Flow<DownloadProgress> 下载进度流
     */
    fun downloadModel(modelId: String, source: String = "huggingface"): Flow<DownloadProgress> = flow {
        // [Fix] 在 IO 线程执行网络下载，避免主线程网络异常
        val config = loadAvailableModels().find { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model: $modelId")

        val repoPath = config.sources[source]
            ?: throw IllegalArgumentException("Source not available: $source")

        val modelDir = File(downloadDir, modelId).also { it.mkdirs() }

        _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, 0, config.size)) }

        var totalDownloaded = 0L

        try {
            for (fileName in config.files) {
                if (activeDownloads[modelId]?.isCanceled() == true) {
                    throw IOException("Download cancelled")
                }

                val url = buildDownloadUrl(repoPath, fileName, source)
                val destFile = File(modelDir, fileName)

                if (destFile.exists() && destFile.length() > 0) {
                    totalDownloaded += destFile.length()
                    emit(DownloadProgress(modelId, totalDownloaded, config.size, DownloadStatus.DOWNLOADING))
                    continue
                }

                val request = Request.Builder().url(url).build()
                val call = client.newCall(request)
                activeDownloads[modelId] = call

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Failed to download $fileName: ${response.code}")
                    }

                    val body = response.body
                        ?: throw IOException("Empty response for $fileName")

                    val fileLength = body.contentLength()
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead: Int
                            var fileDownloaded = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (call.isCanceled()) {
                                    throw IOException("Download cancelled")
                                }
                                output.write(buffer, 0, bytesRead)
                                fileDownloaded += bytesRead
                                totalDownloaded += bytesRead

                                emit(DownloadProgress(modelId, totalDownloaded, config.size, DownloadStatus.DOWNLOADING))
                            }
                        }
                    }
                }
            }

            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.COMPLETED, config.size, config.size)) }
            emit(DownloadProgress(modelId, config.size, config.size, DownloadStatus.COMPLETED))
            Logger.i("PicMe:Download", "Model download completed: $modelId")

        } catch (e: Exception) {
            val status = if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                DownloadStatus.CANCELLED
            } else {
                DownloadStatus.FAILED
            }
            _downloadStates.update { it + (modelId to DownloadState(modelId, status, totalDownloaded, config.size)) }
            emit(DownloadProgress(modelId, totalDownloaded, config.size, status))
            Logger.e("PicMe:Download", "Model download failed: $modelId", e)
        } finally {
            activeDownloads.remove(modelId)
        }
    }.flowOn(Dispatchers.IO)

    private fun buildDownloadUrl(repoPath: String, fileName: String, source: String): String {
        return when (source) {
            "huggingface" -> "https://huggingface.co/$repoPath/resolve/main/$fileName"
            "modelscope" -> "https://modelscope.cn/models/$repoPath/resolve/master/$fileName"
            else -> throw IllegalArgumentException("Unknown source: $source")
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
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
    val files: List<String>
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
