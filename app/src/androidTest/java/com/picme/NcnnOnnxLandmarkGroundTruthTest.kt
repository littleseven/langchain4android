package com.picme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picme.beauty.internal.facedetect.InsightFace2D106Detector
import com.picme.beauty.internal.facedetect.InsightFaceDet10GDetector
import com.picme.beauty.internal.facedetect.NcnnLandmarkDetector
import com.picme.beauty.internal.facedetect.NcnnRoiDetector
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NCNN vs ONNX Landmark (2D106) Ground Truth 验证测试
 *
 * 使用同一张静态图片 + 相同的 ROI，逐层对比 ONNX 和 NCNN 的 106 点关键点输出
 */
@RunWith(AndroidJUnit4::class)
class NcnnOnnxLandmarkGroundTruthTest {

    companion object {
        private const val TAG = "LandmarkGT"
    }

    @Test
    fun compareOnnxAndNcnnLandmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val testImagePath = "face.jpg"
        val bitmap = loadTestBitmap(context, testImagePath)
            ?: throw IllegalStateException("Failed to load test image: $testImagePath")

        println("\n")
        println("============================================================")
        println("  NCNN vs ONNX Landmark (2D106) Ground Truth Validation")
        println("============================================================")
        println("Test image: ${bitmap.width}x${bitmap.height}")
        println("")

        // 1. 获取 ROI（使用 ONNX Det10G 确保一致性）
        println("------------------------------------------------------------")
        println("[Step 1] Detect ROI using ONNX Det10G...")
        println("------------------------------------------------------------")
        val roiDetector = InsightFaceDet10GDetector(context)
        val roi = roiDetector.detectLargestFace(bitmap)
        println("ROI: ${roi?.format() ?: "NULL"}")
        roiDetector.release()

        if (roi == null) {
            println("ERROR: No face detected, cannot proceed with landmark test")
            bitmap.recycle()
            return
        }

        println("")

        // 2. ONNX Landmark 路径
        println("------------------------------------------------------------")
        println("[Step 2] ONNX InsightFace2D106Detector...")
        println("------------------------------------------------------------")
        val onnxLandmarkDetector = InsightFace2D106Detector(context)
        val onnxLandmarks = onnxLandmarkDetector.detect(bitmap, 0, roi)
        println("ONNX landmarks: ${onnxLandmarks?.size?.div(2) ?: 0} points")
        if (onnxLandmarks != null) {
            printLandmarkSummary("ONNX", onnxLandmarks)
        }
        onnxLandmarkDetector.release()

        println("")

        // 3. NCNN Landmark 路径
        println("------------------------------------------------------------")
        println("[Step 3] NCNN NcnnLandmarkDetector...")
        println("------------------------------------------------------------")
        val ncnnLandmarkDetector = NcnnLandmarkDetector(context, requireGpu = false)
        val ncnnLandmarks = ncnnLandmarkDetector.detectLandmarks(bitmap, 0, roi)
        println("NCNN landmarks: ${ncnnLandmarks?.size?.div(2) ?: 0} points")
        if (ncnnLandmarks != null) {
            printLandmarkSummary("NCNN", ncnnLandmarks)
        }
        ncnnLandmarkDetector.release()

        println("")

        // 4. 逐点对比
        println("============================================================")
        println("  Landmark Point-by-Point Comparison")
        println("============================================================")
        if (onnxLandmarks != null && ncnnLandmarks != null) {
            compareLandmarks(onnxLandmarks, ncnnLandmarks, bitmap.width, bitmap.height)
        } else {
            println("Cannot compare: ONNX=${onnxLandmarks != null}, NCNN=${ncnnLandmarks != null}")
        }
        println("============================================================")

        bitmap.recycle()
    }

    private fun loadTestBitmap(context: Context, assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load test image from assets/$assetPath", e)
            null
        }
    }

    private fun RectF.format(): String {
        return "[${left.toInt()}, ${top.toInt()}, ${right.toInt()}, ${bottom.toInt()}]"
    }

    private fun printLandmarkSummary(label: String, landmarks: FloatArray) {
        // 打印前 5 个点
        val sb = StringBuilder("$label first 5 points: ")
        for (i in 0 until minOf(5, landmarks.size / 2)) {
            sb.append("(${String.format("%.4f", landmarks[i * 2])},${String.format("%.4f", landmarks[i * 2 + 1])}) ")
        }
        println(sb.toString())

        // 打印关键点索引
        val keyIndices = listOf(0, 1, 2, 33, 38, 46, 52, 55, 58, 61, 64, 67, 80, 84, 87, 90, 93)
        println("$label key points:")
        for (idx in keyIndices) {
            if (idx * 2 + 1 < landmarks.size) {
                println("  [$idx] (${String.format("%.4f", landmarks[idx * 2])}, ${String.format("%.4f", landmarks[idx * 2 + 1])})")
            }
        }
    }

    private fun compareLandmarks(onnx: FloatArray, ncnn: FloatArray, imgW: Int, imgH: Int) {
        val pointCount = minOf(onnx.size, ncnn.size) / 2
        var totalDiff = 0.0
        var maxDiff = 0.0
        var maxDiffIdx = -1
        val diffThreshold = 0.05f // 5% 图像尺寸
        val maxImgDim = kotlin.math.max(imgW, imgH).toFloat()

        var passCount = 0
        var failCount = 0

        println("Point-by-point diff (in pixels, image=${imgW}x${imgH}):")
        for (i in 0 until pointCount) {
            val onnxX = onnx[i * 2] * imgW
            val onnxY = onnx[i * 2 + 1] * imgH
            val ncnnX = ncnn[i * 2] * imgW
            val ncnnY = ncnn[i * 2 + 1] * imgH

            val dx = onnxX - ncnnX
            val dy = onnxY - ncnnY
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            totalDiff += dist

            if (dist > maxDiff) {
                maxDiff = dist
                maxDiffIdx = i
            }

            val normalizedDist = (dist / maxImgDim).toFloat()
            if (normalizedDist < diffThreshold) {
                passCount++
            } else {
                failCount++
                if (failCount <= 10) {
                    println("  [$i] FAIL: ONNX=(${onnxX.toInt()},${onnxY.toInt()}) NCNN=(${ncnnX.toInt()},${ncnnY.toInt()}) diff=${String.format("%.1f", dist)}px")
                }
            }
        }

        val avgDiff = totalDiff / pointCount
        println("")
        println("Summary:")
        println("  Total points: $pointCount")
        println("  Pass (< ${(diffThreshold * 100).toInt()}%): $passCount")
        println("  Fail (>= ${(diffThreshold * 100).toInt()}%): $failCount")
        println("  Average diff: ${String.format("%.2f", avgDiff)}px")
        println("  Max diff: ${String.format("%.2f", maxDiff)}px at point [$maxDiffIdx]")

        if (failCount == 0) {
            println("  RESULT: PASS (all points within threshold)")
        } else if (failCount <= 5) {
            println("  RESULT: WARNING ($failCount points exceed threshold)")
        } else {
            println("  RESULT: FAIL ($failCount points exceed threshold)")
        }
    }
}
