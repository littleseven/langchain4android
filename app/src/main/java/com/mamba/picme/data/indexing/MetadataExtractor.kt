package com.mamba.picme.data.indexing

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mamba.picme.core.common.Logger
import java.io.IOException

/**
 * 媒体元数据提取器
 *
 * 为单张图片提取：ML Kit 标签、OCR 文字、EXIF GPS、逆地理编码地名。
 * 所有提取均为端侧执行，不上传任何数据。
 */
class MetadataExtractor(private val context: Context) {

    private val tag = "PicMe:MetadataExtractor"

    private val labeler =
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    private val textRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 提取单张图片的全部元数据
     */
    suspend fun extract(imageUri: Uri, inputImage: InputImage): ExtractionResult {
        val labels = extractLabels(inputImage)
        val ocrText = extractOcrText(inputImage)
        val (latitude, longitude, locationName) = extractLocation(imageUri)

        return ExtractionResult(labels, ocrText, latitude, longitude, locationName)
    }

    /**
     * ML Kit 图像标注：返回前 5 个置信度最高的标签
     */
    internal fun extractLabels(inputImage: InputImage): List<String> {
        return try {
            val result = Tasks.await(labeler.process(inputImage))
            result
                .sortedByDescending { label -> label.confidence }
                .take(5)
                .filter { label -> label.confidence >= 0.5f }
                .map { label -> label.text }
                .also { Logger.d(tag, "Labels extracted: $it") }
        } catch (e: com.google.mlkit.common.MlKitException) {
            // 模型尚未下载，跳过（Play Services 后台下载后下次索引会重试）
            if (e.message?.contains("download") == true || e.message?.contains("optional module") == true) {
                Logger.w(tag, "ML Kit label model not ready yet, skipping (will retry later)")
            } else {
                Logger.e(tag, "ML Kit label error", e)
            }
            emptyList()
        } catch (e: Exception) {
            Logger.e(tag, "Label extraction failed", e)
            emptyList()
        }
    }

    /**
     * OCR 文字提取：从图片中提取所有可见文字
     */
    private fun extractOcrText(inputImage: InputImage): String? {
        return try {
            val result = Tasks.await(textRecognizer.process(inputImage))
            val text = result.textBlocks.joinToString(" ") { block -> block.text }.trim()
            if (text.isNotBlank()) {
                Logger.d(tag, "OCR text extracted: ${text.take(100)}...")
                text
            } else null
        } catch (e: Exception) {
            Logger.e(tag, "OCR extraction failed", e)
            null
        }
    }

    /**
     * EXIF 位置提取 + 逆地理编码
     */
    private fun extractLocation(imageUri: Uri): LocationData {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = exif.latLong
                val lat = latLong?.getOrNull(0)
                val lon = latLong?.getOrNull(1)
                val placeName = if (lat != null && lon != null) {
                    reverseGeocode(lat, lon)
                } else null
                LocationData(lat, lon, placeName)
            } ?: LocationData()
        } catch (e: IOException) {
            Logger.w(tag, "EXIF location extraction failed", e)
            LocationData()
        }
    }

    /**
     * 逆地理编码：经纬度 → 地名（优先城市+区域）
     */
    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context)
            val addresses: List<Address>? =
                geocoder.getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.locality, addr.subLocality)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" ")
                    ?: addr.getAddressLine(0)
            }
        } catch (e: IOException) {
            Logger.w(tag, "Geocoding failed", e)
            null
        }
    }

    fun close() {
        try {
            labeler.close()
            textRecognizer.close()
        } catch (e: Exception) {
            Logger.w(tag, "Error closing extractors", e)
        }
    }

    data class ExtractionResult(
        val labels: List<String> = emptyList(),
        val ocrText: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationName: String? = null
    ) {
        val labelsJson: String?
            get() = labels.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "[", postfix = "]") { label ->
                    "\"${label}\""
                }
    }

    private data class LocationData(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationName: String? = null
    )
}
