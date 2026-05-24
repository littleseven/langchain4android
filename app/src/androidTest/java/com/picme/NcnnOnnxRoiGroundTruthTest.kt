package com.picme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picme.beauty.internal.facedetect.InsightFaceDet10GDetector
import com.picme.beauty.internal.facedetect.NcnnRoiDetector
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NCNN vs ONNX ROI 检测 Ground Truth 验证测试
 *
 * 使用同一张静态图片，逐层对比 ONNX 和 NCNN 的检测中间结果：
 * Layer 1: 输入预处理后的像素值
 * Layer 2: 模型原始输出（score/bbox/landmark）
 * Layer 3: Anchor 解码后的检测框
 * Layer 4: NMS 后的结果
 * Layer 5: 映射回原图的 ROI
 */
@RunWith(AndroidJUnit4::class)
class NcnnOnnxRoiGroundTruthTest {

    companion object {
        private const val TAG = "PicMe:GroundTruth"
    }

    @Test
    fun compareOnnxAndNcnnRoiLayerByLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val testImagePath = "face.jpg"
        val bitmap = loadTestBitmap(context, testImagePath)
            ?: throw IllegalStateException("Failed to load test image: $testImagePath")

        println("\n")
        println("============================================================")
        println("  NCNN vs ONNX ROI Ground Truth Validation")
        println("============================================================")
        println("Test image: ${bitmap.width}x${bitmap.height}")
        println("")

        // 1. ONNX 路径
        println("------------------------------------------------------------")
        println("[ONNX] Running InsightFaceDet10GDetector...")
        println("------------------------------------------------------------")
        val onnxDetector = InsightFaceDet10GDetector(context)
        val onnxRoi = onnxDetector.detectLargestFace(bitmap)
        println("[ONNX] Final ROI: ${onnxRoi?.format() ?: "NULL"}")
        onnxDetector.release()

        println("")

        // 2. NCNN 路径
        println("------------------------------------------------------------")
        println("[NCNN] Running NcnnRoiDetector...")
        println("------------------------------------------------------------")
        val ncnnDetector = NcnnRoiDetector(context, requireGpu = false)
        val ncnnRoi = ncnnDetector.detectRoi(bitmap)
        println("[NCNN] Final ROI: ${ncnnRoi?.format() ?: "NULL"}")
        ncnnDetector.release()

        println("")

        // 3. 最终对比
        println("============================================================")
        println("  Layer 5: Final ROI Comparison")
        println("============================================================")
        if (onnxRoi != null && ncnnRoi != null) {
            val diff = calculateDiff(onnxRoi, ncnnRoi)
            println("ONNX ROI:  ${onnxRoi.format()}")
            println("NCNN ROI:  ${ncnnRoi.format()}")
            println("Diff:      left=${diff.left.toInt()}, top=${diff.top.toInt()}, right=${diff.right.toInt()}, bottom=${diff.bottom.toInt()}")
            println("Width diff:  ${(onnxRoi.width() - ncnnRoi.width()).toInt()}px")
            println("Height diff: ${(onnxRoi.height() - ncnnRoi.height()).toInt()}px")

            val centerDiffX = kotlin.math.abs((onnxRoi.left + onnxRoi.right) / 2f - (ncnnRoi.left + ncnnRoi.right) / 2f)
            val centerDiffY = kotlin.math.abs((onnxRoi.top + onnxRoi.bottom) / 2f - (ncnnRoi.top + ncnnRoi.bottom) / 2f)
            println("Center diff: (${centerDiffX.toInt()}, ${centerDiffY.toInt()})")

            if (centerDiffX < 5 && centerDiffY < 5) {
                println("RESULT: PASS (center diff < 5px)")
            } else {
                println("RESULT: FAIL (center diff >= 5px)")
            }
        } else {
            println("ONNX ROI: ${onnxRoi?.format() ?: "NULL"}")
            println("NCNN ROI: ${ncnnRoi?.format() ?: "NULL"}")
            println("RESULT: Cannot compare (one or both detectors returned null)")
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
