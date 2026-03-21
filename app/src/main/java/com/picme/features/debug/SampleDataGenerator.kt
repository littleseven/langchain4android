package com.picme.features.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.picme.R
import com.picme.domain.model.MediaAsset
import com.picme.domain.model.MediaType
import com.picme.domain.repository.MediaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class ImageCandidate(val url: String, val source: String)

object SampleDataGenerator {
    private const val TAG = "SampleData"
    private val STAR_NAMES = listOf("刘亦菲", "迪丽热巴", "赵丽颖", "高圆圆", "古力娜扎", "刘诗诗", "倪妮", "钟楚曦", "李宛妲", "黄瑾一", "李沁", "张婧仪", "陈都灵", "王楚然", "周也")
    private val LANDSCAPE_KEYWORDS = listOf("雪山", "草原", "森林", "大海", "星空", "沙漠", "秋色", "雨林", "冰川", "极光")
    
    private val SWIMWEAR_KEYWORDS = listOf(
        "site:worldswimsuit.com bikini model full body",
        "site:frankiesbikinis.com bikini photoshoot high res",
        "site:si.com swimsuit model beach photoshoot",
        "site:pinterest.com bikini model beach portrait full body",
        "site:vogue.com swimwear editorial photoshoot",
        "three-point bikini beach gravure full body",
        "比基尼美女写真 全身 沙滩 三点式",
        "克拉拉 三点式 泳装 全身"
    )
    private val SEXY_KEYWORDS = listOf(
        "site:models.com lingerie editorial full body",
        "site:metart.com artistic portrait photo",
        "site:hegre.com artistic body photography",
        "site:500px.com boudoir photography full body",
        "sexy female portrait lingerie full body",
        "性感女神 全身写真 吊带 诱惑",
        "清凉私房写真 全身 少女",
        "柳岩 性感写真 全身"
    )

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()

    // Channel Stats: source -> Pair(SuccessCount, TotalAttempts)
    private val channelStats = mutableMapOf<String, Pair<AtomicInteger, AtomicInteger>>()

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    init {
        channelStats["google"] = Pair(AtomicInteger(0), AtomicInteger(0))
        channelStats["bing"] = Pair(AtomicInteger(0), AtomicInteger(0))
    }

    private fun getChannelWeight(source: String): Float {
        val stats = channelStats[source] ?: return 1.0f
        val success = stats.first.get().toFloat()
        val total = stats.second.get().toFloat()
        if (total < 5) return 1.0f // Initial weight
        return (success / total).coerceAtLeast(0.1f)
    }

    fun pause(context: Context) {
        _isPaused.value = true
        _progress.value = context.getString(R.string.pause)
    }

    fun resume() {
        _isPaused.value = false
    }

    fun stop(context: Context) {
        _isGenerating.value = false
        _isPaused.value = false
        _progress.value = context.getString(R.string.stop)
    }

    suspend fun populatePersonTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, STAR_NAMES, prefix = "TEST_PERSON")
    }

    suspend fun populateLandscapeTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, LANDSCAPE_KEYWORDS, prefix = "TEST_LANDSCAPE")
    }

    suspend fun populateSwimwearTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, SWIMWEAR_KEYWORDS, prefix = "TEST_SWIMWEAR")
    }

    suspend fun populateSexyTestData(context: Context, repository: MediaRepository) {
        generateData(context, repository, SEXY_KEYWORDS, prefix = "TEST_SEXY")
    }

    private suspend fun generateData(
        context: Context, 
        repository: MediaRepository, 
        keywords: List<String>, 
        prefix: String
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
            val semaphore = Semaphore(5) // Limit max parallel downloads/analyses

            for (keyword in keywords) {
                if (!_isGenerating.value) break
                while (_isPaused.value && _isGenerating.value) { delay(500) }
                if (!_isGenerating.value) break

                _progress.value = keyword
                val candidates = searchImagesParallel(keyword)
                
                val downloadedCount = AtomicInteger(0)
                val targetCount = 3

                coroutineScope {
                    candidates.forEach { candidate ->
                        launch {
                            if (downloadedCount.get() >= targetCount || !_isGenerating.value) return@launch
                            
                            semaphore.withPermit {
                                if (downloadedCount.get() >= targetCount || !_isGenerating.value) return@withPermit
                                while (_isPaused.value && _isGenerating.value) { delay(500) }

                                val stats = channelStats[candidate.source]
                                stats?.second?.incrementAndGet()

                                val timestamp = System.currentTimeMillis()
                                val fileName = "${prefix}_${keyword.replace(" ", "_").replace(":", "_")}_${timestamp}.jpg"
                                
                                val file = downloadAndValidateImage(candidate.url, context, fileName)
                                if (file != null) {
                                    val bitmap = decodeSampledBitmap(file, 400, 400)
                                    if (bitmap != null) {
                                        val faceResult = analyzeFace(bitmap)
                                        val skinExposure = checkSkinExposure(bitmap)
                                        
                                        var isEligible = true
                                        if (prefix == "TEST_SWIMWEAR" || prefix == "TEST_SEXY") {
                                            val isTooMuchFace = faceResult.maxHeightRatio > 0.18f
                                            val isNotEnoughSkin = skinExposure < 30.0f
                                            if (faceResult.count == 0 || isTooMuchFace || isNotEnoughSkin) {
                                                isEligible = false
                                            }
                                        }

                                        if (isEligible) {
                                            if (downloadedCount.get() < targetCount) {
                                                calendar.timeInMillis = System.currentTimeMillis()
                                                calendar.add(Calendar.DAY_OF_YEAR, -random.nextInt(180))
                                                repository.insertMedia(MediaAsset(
                                                    uri = UriPathUtil.getUriFromFile(file),
                                                    type = MediaType.PHOTO,
                                                    captureDate = calendar.timeInMillis,
                                                    fileName = fileName,
                                                    hasFace = faceResult.count > 0
                                                ))
                                                downloadedCount.incrementAndGet()
                                                stats?.first?.incrementAndGet()
                                                _progress.value = "$keyword (${downloadedCount.get()}/$targetCount)"
                                            } else {
                                                file.delete()
                                            }
                                        } else {
                                            file.delete()
                                        }
                                        bitmap.recycle()
                                    } else {
                                        file.delete()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            _isGenerating.value = false
            _isPaused.value = false
            _progress.value = ""
        }
    }

    private suspend fun searchImagesParallel(keyword: String): List<ImageCandidate> = coroutineScope {
        val googleJob = async { searchGoogle(keyword) }
        val bingJob = async { searchBing(keyword) }
        
        val googleRes = googleJob.await().map { ImageCandidate(it, "google") }
        val bingRes = bingJob.await().map { ImageCandidate(it, "bing") }
        
        (googleRes + bingRes).shuffled().sortedByDescending { getChannelWeight(it.source) }
    }

    private suspend fun searchGoogle(keyword: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        try {
            val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(keyword, "UTF-8")}&tbm=isch&safe=off"
            val connection = URL(searchUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 5000
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val html = connection.inputStream.bufferedReader().readText()
                val googleRegex = "\"(https?://[^\"]+?\\.(?:jpg|jpeg|png))\",\\d+,\\d+".toRegex()
                googleRegex.findAll(html).forEach { urls.add(it.groupValues[1]) }
            }
        } catch (e: Exception) { Log.w(TAG, "Google search failed: ${e.message}") }
        urls
    }

    private suspend fun searchBing(keyword: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        try {
            val searchUrl = "https://www.bing.com/images/search?q=${URLEncoder.encode(keyword, "UTF-8")}&first=1"
            val connection = URL(searchUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 5000
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val html = connection.inputStream.bufferedReader().readText()
                "murl&quot;:&quot;(https?://.*?)&quot;".toRegex().findAll(html).forEach { urls.add(it.groupValues[1]) }
            }
        } catch (e: Exception) { Log.w(TAG, "Bing search failed: ${e.message}") }
        urls
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return try { BitmapFactory.decodeFile(file.absolutePath, options) } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    data class FaceAnalysisResult(val count: Int, val maxHeightRatio: Float)

    private suspend fun analyzeFace(bitmap: Bitmap): FaceAnalysisResult = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(options)
        detector.process(image)
            .addOnSuccessListener { faces -> 
                val maxRatio = if (faces.isNotEmpty()) faces.maxOf { it.boundingBox.height().toFloat() / bitmap.height } else 0f
                continuation.resume(FaceAnalysisResult(faces.size, maxRatio)) 
            }
            .addOnFailureListener { continuation.resume(FaceAnalysisResult(0, 0f)) }
            .addOnCompleteListener { detector.close() }
    }

    private fun checkSkinExposure(bitmap: Bitmap): Float {
        var skinPixels = 0
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            if (r > 95 && g > 40 && b > 20 && (maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b)) > 15) &&
                kotlin.math.abs(r - g) > 15 && r > g && r > b) skinPixels++
        }
        return (skinPixels.toFloat() / (width * height).toFloat()) * 100f
    }

    private fun downloadAndValidateImage(urlString: String, context: Context, fileName: String): File? {
        try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}")
            connection.inputStream.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
            if (tempFile.length() < 20480) { tempFile.delete(); return null }
            val finalFile = File(context.filesDir, fileName)
            return if (tempFile.renameTo(finalFile)) finalFile else { tempFile.delete(); null }
        } catch (e: Exception) { return null }
    }

    suspend fun clearTestData(context: Context, repository: MediaRepository, allMedia: List<MediaAsset>) {
        val testAssetsInDb = allMedia.filter { it.fileName.startsWith("TEST_") }
        testAssetsInDb.forEach { asset ->
            try { File(asset.uri.removePrefix("file://")).delete() } catch (e: Exception) {}
        }
        if (testAssetsInDb.isNotEmpty()) repository.deleteMediaByIds(testAssetsInDb.map { it.id })
    }
}

object UriPathUtil {
    fun getUriFromFile(file: File): String = "file://${file.absolutePath}"
}
