package com.mamba.picme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mamba.picme.beauty.internal.facedetect.Det10GRoiDetector
import com.mamba.picme.beauty.internal.facedetect.MnnRoiDetector
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ROI 检测器对比测试
 * 使用同一张静态图片分别测试 ONNX 和 MNN 路径的 ROI 输出
 */
@RunWith(AndroidJUnit4::class)
class RoiDetectorComparisonTest {

    companion object {
        private const val TAG = "RoiCompare"
    }

    @Test
    fun compareOnnxAndMnnRoi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 使用测试图片 (input_images/face.jpg)
        val testImagePath = "face.jpg"
        val bitmap = loadTestBitmap(context, testImagePath)
            ?: throw IllegalStateException("Failed to load test image: $testImagePath")

        println("========================================")
        println("ROI Detector Comparison Test")
        println("Test image: ${bitmap.width}x${bitmap.height}")
        println("========================================")

        // 1. ONNX 路径测试
        val onnxRoi = testOnnxRoi(context, bitmap)

        // 2. MNN 路径测试 (GPU)
        val mnnGpuRoi = testMnnRoi(context, bitmap, forceGpu = true)

        // 3. MNN 路径测试 (CPU)
        val mnnCpuRoi = testMnnRoi(context, bitmap, forceGpu = false)

        // 4. 对比结果
        println("========================================")
        println("Comparison Results:")
        println("ONNX    ROI: ${onnxRoi?.format() ?: "NULL"}")
        println("MNN-GPU ROI: ${mnnGpuRoi?.format() ?: "NULL"}")
        println("MNN-CPU ROI: ${mnnCpuRoi?.format() ?: "NULL"}")

        if (onnxRoi != null && mnnGpuRoi != null) {
            val diff = calculateDiff(onnxRoi, mnnGpuRoi)
            println("ONNX vs MNN-GPU diff: left=${diff.left}, top=${diff.top}, right=${diff.right}, bottom=${diff.bottom}")
            println("ONNX vs MNN-GPU width diff: ${(onnxRoi.width() - mnnGpuRoi.width())}")
            println("ONNX vs MNN-GPU height diff: ${(onnxRoi.height() - mnnGpuRoi.height())}")
        }

        if (onnxRoi != null && mnnCpuRoi != null) {
            val diff = calculateDiff(onnxRoi, mnnCpuRoi)
            println("ONNX vs MNN-CPU diff: left=${diff.left}, top=${diff.top}, right=${diff.right}, bottom=${diff.bottom}")
            println("ONNX vs MNN-CPU width diff: ${(onnxRoi.width() - mnnCpuRoi.width())}")
            println("ONNX vs MNN-CPU height diff: ${(onnxRoi.height() - mnnCpuRoi.height())}")
        }

        println("========================================")

        bitmap.recycle()
    }

    private fun testOnnxRoi(context: Context, bitmap: Bitmap): RectF? {
        println("[ONNX] Initializing Det10GRoiDetector...")
        val detector = Det10GRoiDetector(context)

        println("[ONNX] Running detectRoi on ${bitmap.width}x${bitmap.height} bitmap...")
        val roi = detector.detectRoi(bitmap)

        println("[ONNX] Result: ${roi?.format() ?: "NULL"}")
        detector.release()
        return roi
    }

    private fun testMnnRoi(context: Context, bitmap: Bitmap, forceGpu: Boolean): RectF? {
        val mode = if (forceGpu) "GPU" else "CPU"
        println("[MNN-$mode] Initializing MnnRoiDetector (forceGpu=$forceGpu)...")
        val detector = MnnRoiDetector(context, requireGpu = forceGpu)

        println("[MNN-$mode] Running detectRoi on ${bitmap.width}x${bitmap.height} bitmap...")
        val roi = detector.detectRoi(bitmap)

        println("[MNN-$mode] Result: ${roi?.format() ?: "NULL"}")
        detector.release()
        return roi
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
        return "[${left.toInt()}, ${top.toInt()}, ${right.toInt()}, ${bottom.toInt()}] size=${width().toInt()}x${height().toInt()}"
    }

    private fun calculateDiff(a: RectF, b: RectF): RectF {
        return RectF(
            a.left - b.left,
            a.top - b.top,
            a.right - b.right,
            a.bottom - b.bottom
        )
    }
}
