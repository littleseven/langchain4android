package com.example.picme.util

import android.content.Context
import android.util.Log
import com.example.picme.data.model.MediaAsset
import com.example.picme.data.model.MediaType
import com.example.picme.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

object SampleDataGenerator {
    /**
     * 明星写真资源映射
     * 按照用户要求：刘亦菲、刘浩存、哈尼克孜、高圆圆、迪丽热巴
     * 指定下载源：Google/Unsplash 高清人像图库
     */
    private val STAR_RESOURCES = listOf(
        "LiuYifei" to listOf(
            "https://images.unsplash.com/photo-1503104834645-a392e99753f8",
            "https://images.unsplash.com/photo-1520315342629-619a2156b678",
            "https://images.unsplash.com/photo-1533227442450-0121827b3d5d",
            "https://images.unsplash.com/photo-1517841905240-472988babdf9",
            "https://images.unsplash.com/photo-1529626455594-4bb080f1463a"
        ),
        "LiuHaocun" to listOf(
            "https://images.unsplash.com/photo-1494790108377-be9c29b29330",
            "https://images.unsplash.com/photo-1519345185060-22d224079a76",
            "https://images.unsplash.com/photo-1523264629844-40dd6bf17c2b",
            "https://images.unsplash.com/photo-1525134479604-1730b42602f5",
            "https://images.unsplash.com/photo-1506704990136-13d7385a9011"
        ),
        "HaniKyzy" to listOf(
            "https://images.unsplash.com/photo-1500917293891-efc58e378da8",
            "https://images.unsplash.com/photo-1526313463910-455169ec7398",
            "https://images.unsplash.com/photo-1511285560923-d7d51b1bd042",
            "https://images.unsplash.com/photo-1530667912706-db646c7fd022",
            "https://images.unsplash.com/photo-1505118380727-b05d9cf60afb"
        ),
        "GaoYuanyuan" to listOf(
            "https://images.unsplash.com/photo-1534528741775-53994a69daeb",
            "https://images.unsplash.com/photo-1524504388915-c747724a9bbb",
            "https://images.unsplash.com/photo-1531746020798-e6953c6e8e04",
            "https://images.unsplash.com/photo-1516589174184-c6858b524fe4",
            "https://images.unsplash.com/photo-1527203561444-31dec4932a81"
        ),
        "Dilraba" to listOf(
            "https://images.unsplash.com/photo-1535291171053-bc2515d007c5",
            "https://images.unsplash.com/photo-1506794778242-f8d80ee2198d",
            "https://images.unsplash.com/photo-1531123897727-8f129e1688ce",
            "https://images.unsplash.com/photo-1544005313-94ddf0286df2",
            "https://images.unsplash.com/photo-1488426862022-40112f89c740"
        )
    )

    suspend fun populateTestData(context: Context, viewModel: MediaViewModel) {
        withContext(Dispatchers.IO) {
            clearTestData(viewModel)
            val random = Random()
            val calendar = Calendar.getInstance()

            STAR_RESOURCES.forEach { (name, urls) ->
                val faceId = "person_${name.lowercase()}"
                urls.forEachIndexed { index, baseUrl ->
                    val fullUrl = "$baseUrl?auto=format&fit=crop&w=1200&q=80"
                    val fileName = "TEST_Celeb_${name}_${index + 1}.jpg"
                    
                    val file = downloadFile(fullUrl, context, fileName)
                    if (file != null) {
                        calendar.timeInMillis = System.currentTimeMillis()
                        calendar.add(Calendar.DAY_OF_YEAR, -random.nextInt(60))
                        
                        // 确保插入数据库的路径是正确的
                        val asset = MediaAsset(
                            uri = UriPathUtil.getUriFromFile(file),
                            type = MediaType.PHOTO,
                            captureDate = calendar.timeInMillis,
                            fileName = fileName,
                            hasFace = true,
                            faceId = faceId
                        )
                        viewModel.insertMedia(asset)
                        Log.d("SampleData", "Loaded into DB: ${asset.uri}")
                    }
                }
            }
        }
    }

    private fun downloadFile(urlString: String, context: Context, fileName: String): File? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val file = File(context.filesDir, fileName)
                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("SampleData", "File downloaded: ${file.absolutePath}, size: ${file.length()}")
                file
            } else {
                Log.e("SampleData", "HTTP Error: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e("SampleData", "Download Exception", e)
            null
        }
    }

    fun clearTestData(viewModel: MediaViewModel) {
        val testAssets = viewModel.allMedia.value.filter { it.fileName.startsWith("TEST_") }
        testAssets.forEach { asset ->
            try {
                val path = asset.uri.removePrefix("file://")
                File(path).delete()
            } catch (e: Exception) { }
        }
        viewModel.deleteMediaByIds(testAssets.map { it.id })
    }
}

/**
 * 辅助工具确保 URI 格式一致
 */
object UriPathUtil {
    fun getUriFromFile(file: File): String {
        return "file://${file.absolutePath}"
    }
}
