package com.picme.util

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.picme.data.model.MediaAsset
import com.picme.data.model.MediaType
import com.picme.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*

object SampleDataGenerator {
    private const val TAG = "SampleData"
    private val STAR_NAMES = listOf("刘亦菲", "迪丽热巴", "赵丽颖", "高圆圆", "古力娜扎", "刘诗诗", "倪妮", "钟楚曦", "李宛妲", "黄瑾一", "李沁", "张婧仪", "陈都灵", "王楚然", "周也")
    private val LANDSCAPE_KEYWORDS = listOf("雪山", "草原", "森林", "大海", "星空", "沙漠", "秋色", "雨林", "冰川", "极光")

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    fun pause() {
        _isPaused.value = true
        _progress.value = "已暂停"
    }

    fun resume() {
        _isPaused.value = false
    }

    fun stop() {
        _isGenerating.value = false
        _isPaused.value = false
        _progress.value = "已停止"
    }

    suspend fun populateTestData(context: Context, repository: MediaRepository) {
        populatePersonTestData(context, repository)
        populateLandscapeTestData(context, repository)
    }

    suspend fun populatePersonTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, STAR_NAMES, isPerson = true)
    }

    suspend fun populateLandscapeTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, LANDSCAPE_KEYWORDS, isPerson = false)
    }

    private suspend fun generateData(
        context: Context, 
        repository: MediaRepository, 
        keywords: List<String>, 
        isPerson: Boolean
    ) {
        if (_isGenerating.value && !_isPaused.value) return
        
        if (!_isGenerating.value) {
            _isGenerating.value = true
            _isPaused.value = false
        } else if (_isPaused.value) {
            _isPaused.value = false
            return
        }

        withContext(Dispatchers.IO) {
            val random = Random()
            val calendar = Calendar.getInstance()

            for (keyword in keywords) {
                if (!_isGenerating.value) break
                
                while (_isPaused.value && _isGenerating.value) {
                    delay(500)
                }
                if (!_isGenerating.value) break

                val faceId = if (isPerson) "person_${keyword}" else null
                _progress.value = "正在搜索: $keyword"
                val candidateUrls = searchImages(keyword)
                Log.d(TAG, "Search results for $keyword: found ${candidateUrls.size} candidates")
                
                var downloadedCount = 0
                for (url in candidateUrls) {
                    if (!_isGenerating.value) break
                    while (_isPaused.value && _isGenerating.value) {
                        delay(500)
                    }
                    if (!_isGenerating.value) break

                    Log.d(TAG, ">>> [ATTEMPT DOWNLOAD] $keyword - URL: $url")
                    
                    val timestamp = System.currentTimeMillis()
                    val prefix = if (isPerson) "TEST_PERSON" else "TEST_LANDSCAPE"
                    val fileName = "${prefix}_${keyword}_${downloadedCount + 1}_$timestamp.jpg"
                    
                    _progress.value = "正在下载: $keyword (${downloadedCount + 1}/3)"
                    val file = downloadAndValidateImage(url, context, fileName)
                    
                    if (file != null) {
                        calendar.timeInMillis = System.currentTimeMillis()
                        calendar.add(Calendar.DAY_OF_YEAR, -random.nextInt(180)) // Longer range for variety
                        
                        val asset = MediaAsset(
                            uri = UriPathUtil.getUriFromFile(file),
                            type = MediaType.PHOTO,
                            captureDate = calendar.timeInMillis,
                            fileName = fileName,
                            hasFace = isPerson,
                            faceId = faceId
                        )
                        repository.insertMedia(asset)
                        downloadedCount++
                        Log.d(TAG, ">>> [DOWNLOAD SUCCESS] $keyword - Saved: ${asset.uri}")
                    } else {
                        Log.w(TAG, ">>> [DOWNLOAD FAILED] $keyword - URL: $url")
                    }
                    
                    if (downloadedCount >= 3) break
                }
            }
            _isGenerating.value = false
            _isPaused.value = false
            _progress.value = "完成"
        }
    }

    private suspend fun searchImages(keyword: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")

        // 策略 1: Bing Image Search
        try {
            val searchUrl = "https://www.bing.com/images/search?q=$encodedKeyword&first=1"
            val connection = URL(searchUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            val responseCode = try { connection.responseCode } catch (e: Exception) { -1 }
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val html = connection.inputStream.bufferedReader().readText()
                val murlRegex = "murl&quot;:&quot;(https?://.*?)&quot;".toRegex()
                murlRegex.findAll(html).forEach { 
                    urls.add(it.groupValues[1])
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bing search failed: ${e.message}")
        }

        // 策略 2: 百度图片
        if (urls.size < 10) {
            try {
                val searchUrl = "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&ct=201326592&fp=result&queryWord=$encodedKeyword&cl=2&lm=-1&ie=utf-8&oe=utf-8&word=$encodedKeyword&pn=0&rn=30"
                val connection = URL(searchUrl).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Referer", "https://image.baidu.com/")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                
                val responseCode = try { connection.responseCode } catch (e: Exception) { -1 }
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    "\"middleURL\":\"(.*?)\"".toRegex().findAll(response).forEach {
                        val rawUrl = it.groupValues[1].replace("\\/", "/")
                        if (rawUrl.startsWith("http") && !urls.contains(rawUrl)) {
                            urls.add(rawUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Baidu search failed: ${e.message}")
            }
        }

        // 策略 3: Unsplash (With robust error handling)
        try {
            val unsplashUrl = "https://unsplash.com/napi/search/photos?query=$encodedKeyword&per_page=20"
            val connection = URL(unsplashUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://unsplash.com/")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true

            val responseCode = try { connection.responseCode } catch (e: Exception) { -1 }
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try {
                    val response = connection.inputStream.bufferedReader().readText()
                    "\"small\":\"(https?://.*?)\"".toRegex().findAll(response).forEach {
                        urls.add(it.groupValues[1].replace("\\u0026", "&"))
                    }
                } catch (e: java.io.FileNotFoundException) {
                    Log.w(TAG, "Unsplash NAPI stream failed after 200 OK (unusual redirect)")
                }
            } else {
                Log.w(TAG, "Unsplash NAPI failed ($responseCode), trying HTML fallback")
                val htmlUrl = "https://unsplash.com/s/photos/$encodedKeyword"
                val htmlConn = URL(htmlUrl).openConnection() as HttpURLConnection
                htmlConn.setRequestProperty("User-Agent", USER_AGENT)
                htmlConn.connectTimeout = 5000
                if (htmlConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val html = htmlConn.inputStream.bufferedReader().readText()
                    "https://images\\.unsplash\\.com/photo-[^\"?\\s]+".toRegex().findAll(html).forEach {
                        val fullUrl = "${it.value}?auto=format&fit=crop&w=400&q=80"
                        if (!urls.contains(fullUrl)) urls.add(fullUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unsplash failed gracefully: ${e.message}")
        }

        urls.distinct()
            .filter { url ->
                val lUrl = url.lowercase()
                !lUrl.contains("gstatic.com") && 
                !lUrl.contains("tbn") && 
                !lUrl.contains("encrypted-tbn") &&
                (lUrl.contains(".jpg") || lUrl.contains(".jpeg") || lUrl.contains(".png") || lUrl.contains("images.unsplash.com"))
            }
            .shuffled()
    }

    private fun downloadAndValidateImage(urlString: String, context: Context, fileName: String): File? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            
            val host = url.host
            connection.setRequestProperty("Referer", "${url.protocol}://$host/")

            val responseCode = try { connection.responseCode } catch (e: Exception) { -1 }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}")
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                return null
            }
            
            if (tempFile.length() < 5120) {
                tempFile.delete()
                return null
            }
            
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                tempFile.delete()
                return null
            }
            
            val finalFile = File(context.filesDir, fileName)
            if (finalFile.exists()) finalFile.delete()
            
            return if (tempFile.renameTo(finalFile)) {
                finalFile
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun clearTestData(context: Context, repository: MediaRepository, allMedia: List<MediaAsset>) {
        val testAssetsInDb = allMedia.filter { it.fileName.startsWith("TEST_") }
        
        testAssetsInDb.forEach { asset ->
            try {
                val filePath = asset.uri.removePrefix("file://")
                val file = File(filePath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file", e)
            }
        }

        try {
            val files = context.filesDir.listFiles()
            files?.forEach { file ->
                if (file.name.startsWith("TEST_")) file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during directory cleanup", e)
        }

        if (testAssetsInDb.isNotEmpty()) {
            val ids = testAssetsInDb.map { it.id }
            repository.deleteMediaByIds(ids)
        }
    }
}

object UriPathUtil {
    fun getUriFromFile(file: File): String {
        return "file://${file.absolutePath}"
    }
}
