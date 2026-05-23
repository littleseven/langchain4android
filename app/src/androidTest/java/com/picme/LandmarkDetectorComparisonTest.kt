package com.picme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picme.beauty.internal.facedetect.InsightFaceLandmarkDetector
import com.picme.beauty.internal.facedetect.MnnLandmarkDetector
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Landmark 检测器对比测试
 * 使用同一张静态图片分别测试 ONNX 和 MNN 路径的 106 点输出
 */
@RunWith(AndroidJUnit4::class)
class LandmarkDetectorComparisonTest {

    companion object {
        private const val TAG = "PicMe:LandmarkCompare"
    }

    @Test
    fun compareOnnxAndMnnLandmarks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 使用测试图片 (input_images/face.jpg)
        val testImagePath = "face.jpg"
        val bitmap = loadTestBitmap(context, testImagePath)
            ?: throw IllegalStateException("Failed to load test image: $testImagePath")

        println("========================================")
        println("Landmark Detector Comparison Test")
        println("Test image: ${bitmap.width}x${bitmap.height}")
        println("========================================")

        // 使用固定的 ROI 确保输入区域一致
        val roi = RectF(200f, 150f, 600f, 750f)
        println("Fixed ROI: $roi")

        // 1. ONNX 路径测试
        val onnxLandmarks = testOnnxLandmarks(context, bitmap, roi)

        // 2. MNN 路径测试 (GPU)
        val mnnGpuLandmarks = testMnnLandmarks(context, bitmap, roi, forceGpu = true)

        // 3. MNN 路径测试 (CPU)
        val mnnCpuLandmarks = testMnnLandmarks(context, bitmap, roi, forceGpu = false)

        // 4. 对比结果
        println("========================================")
        println("Comparison Results:")
        println("ONNX    points: ${onnxLandmarks?.size?.div(2) ?: 0}")
        println("MNN-GPU points: ${mnnGpuLandmarks?.size?.div(2) ?: 0}")
        println("MNN-CPU points: ${mnnCpuLandmarks?.size?.div(2) ?: 0}")

        if (onnxLandmarks != null && mnnGpuLandmarks != null) {
            val diff = calculatePointDiffs(onnxLandmarks, mnnGpuLandmarks, bitmap.width, bitmap.height)
            println("ONNX vs MNN-GPU:")
            printDiffSummary(diff)
        }

        if (onnxLandmarks != null && mnnCpuLandmarks != null) {
            val diff = calculatePointDiffs(onnxLandmarks, mnnCpuLandmarks, bitmap.width, bitmap.height)
            println("ONNX vs MNN-CPU:")
            printDiffSummary(diff)
        }

        println("========================================")

        bitmap.recycle()
    }

    private fun testOnnxLandmarks(context: Context, bitmap: Bitmap, roi: RectF): FloatArray? {
        println("[ONNX] Initializing InsightFaceLandmarkDetector...")
        val detector = InsightFaceLandmarkDetector(context)

        println("[ONNX] Running detectLandmarks on ${bitmap.width}x${bitmap.height} bitmap...")
        val landmarks = detector.detectLandmarks(bitmap, 1, roi)

        if (landmarks != null) {
            println("[ONNX] Detected ${landmarks.size / 2} points")
            // 输出前 5 个点
            for (i in 0 until minOf(5, landmarks.size / 2)) {
                val px = landmarks[i * 2] * bitmap.width
                val py = landmarks[i * 2 + 1] * bitmap.height
                println("[ONNX] Point $i: ($px, $py)")
            }
        } else {
            println("[ONNX] No landmarks detected")
        }

        detector.release()
        return landmarks
    }

    private fun testMnnLandmarks(context: Context, bitmap: Bitmap, roi: RectF, forceGpu: Boolean): FloatArray? {
        val mode = if (forceGpu) "GPU" else "CPU"
        println("[MNN-$mode] Initializing MnnLandmarkDetector (forceGpu=$forceGpu)...")
        val detector = MnnLandmarkDetector(context, requireGpu = forceGpu)

        println("[MNN-$mode] Running detectLandmarks on ${bitmap.width}x${bitmap.height} bitmap...")
        val landmarks = detector.detectLandmarks(bitmap, 1, roi)

        if (landmarks != null) {
            println("[MNN-$mode] Detected ${landmarks.size / 2} points")
            // 输出前 5 个点
            for (i in 0 until minOf(5, landmarks.size / 2)) {
                val px = landmarks[i * 2] * bitmap.width
                val py = landmarks[i * 2 + 1] * bitmap.height
                println("[MNN-$mode] Point $i: ($px, $py)")
            }
        } else {
            println("[MNN-$mode] No landmarks detected")
        }

        detector.release()
        return landmarks
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

    data class PointDiff(
        val index: Int,
        val dx: Float,
        val dy: Float,
        val distance: Float
    )

    private fun calculatePointDiffs(
        onnx: FloatArray,
        mnn: FloatArray,
        width: Int,
        height: Int
    ): List<PointDiff> {
        val diffs = mutableListOf<PointDiff>()
        val pointCount = minOf(onnx.size, mnn.size) / 2

        for (i in 0 until pointCount) {
            val onnxX = onnx[i * 2] * width
            val onnxY = onnx[i * 2 + 1] * height
            val mnnX = mnn[i * 2] * width
            val mnnY = mnn[i * 2 + 1] * height

            val dx = abs(onnxX - mnnX)
            val dy = abs(onnxY - mnnY)
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            diffs.add(PointDiff(i, dx, dy, dist))
        }

        return diffs
    }

    private fun printDiffSummary(diffs: List<PointDiff>) {
        val sorted = diffs.sortedByDescending { it.distance }
        val maxDiff = sorted.first()
        val avgDiff = diffs.map { it.distance }.average()
        val medianDiff = diffs.map { it.distance }.sorted()[diffs.size / 2]

        println("  Max diff: point ${maxDiff.index}, dist=${String.format("%.2f", maxDiff.distance)}px " +
                "(dx=${String.format("%.2f", maxDiff.dx)}, dy=${String.format("%.2f", maxDiff.dy)})")
        println("  Avg diff: ${String.format("%.2f", avgDiff)}px")
        println("  Median diff: ${String.format("%.2f", medianDiff)}px")
        println("  Top 10 worst points:")
        for (i in 0 until minOf(10, sorted.size)) {
            val d = sorted[i]
            println("    #${d.index}: dist=${String.format("%.2f", d.distance)}px " +
                    "(dx=${String.format("%.2f", d.dx)}, dy=${String.format("%.2f", d.dy)})")
        }

        // 统计误差分布
        val within1px = diffs.count { it.distance < 1 }
        val within3px = diffs.count { it.distance < 3 }
        val within5px = diffs.count { it.distance < 5 }
        val within10px = diffs.count { it.distance < 10 }
        println("  Error distribution:")
        println("    <1px: $within1px/${diffs.size} (${String.format("%.1f", within1px * 100f / diffs.size)}%)")
        println("    <3px: $within3px/${diffs.size} (${String.format("%.1f", within3px * 100f / diffs.size)}%)")
        println("    <5px: $within5px/${diffs.size} (${String.format("%.1f", within5px * 100f / diffs.size)}%)")
        println("    <10px: $within10px/${diffs.size} (${String.format("%.1f", within10px * 100f / diffs.size)}%)")
    }
}
