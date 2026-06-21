package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.core.common.Logger
import java.io.File
import java.nio.FloatBuffer

/**
 * MobileFaceNet 人脸嵌入提取器
 *
 * 使用 ONNX Runtime 运行 MobileFaceNet 模型，
 * 将人脸图片转换为 512 维 embedding 向量。
 *
 * 模型输入: 112x112 RGB 人脸图像
 * 输出: 512 维 float32 特征向量
 * 大小: ~4.5MB
 *
 * 模型来源: 使用 PyTorch ArcFace 实现的 MobileFaceNet 导出为 ONNX。
 * 也可从以下项目获取预转换模型:
 * - OpenCV Zoo: https://github.com/opencv/opencv_zoo/tree/master/models/face_recognition/mobilefacenet
 * - InsightFace: https://github.com/deepinsight/insightface/tree/master/model_zoo
 * - 注意: model_zoo 中的模型需通过 Python SDK 下载 (insightface.model_zoo.get_model)
 *   无法直接通过 HTTP 下载，请手动将 .onnx 文件放入指定路径。
 */
class MobileFaceNetEmbedder(private val context: Context) {

    companion object {
        private const val TAG = "PicMe:MobileFaceNet"
        private const val MODEL_FILENAME = "mobilefacenet.onnx"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_DIM = 512
    }

    private val modelDir: File
        get() = File(context.filesDir, "models/face_embedding")

    private val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    /** 模型是否已下载 */
    val isModelReady: Boolean
        get() = modelFile.exists()

    private var ortEnv: ai.onnxruntime.OrtEnvironment? = null
    private var ortSession: ai.onnxruntime.OrtSession? = null

    /**
     * 初始化 ONNX Runtime 和加载模型
     */
    fun initialize(): Boolean {
        if (ortSession != null) return true
        if (!isModelReady) {
            Logger.w(TAG, "Model not found at ${modelFile.absolutePath}")
            return false
        }
        return try {
            ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val opts = ai.onnxruntime.OrtSession.SessionOptions()
            ortSession = ortEnv?.createSession(modelFile.absolutePath, opts)
            Logger.i(TAG, "MobileFaceNet model loaded, input count=${ortSession?.inputNames?.size}")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load ONNX model", e)
            ortEnv?.close()
            ortEnv = null
            false
        }
    }

    /**
     * 提取人脸 embedding
     *
     * @param faceBitmap 112x112 人脸图像（RGB）
     * @return 512 维归一化 embedding，或 null（失败时）
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val session = ortSession ?: return null
        val env = ortEnv ?: return null

        // 确保输入尺寸
        val resized = if (faceBitmap.width != INPUT_SIZE || faceBitmap.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        } else faceBitmap

        return try {
            // 预处理：RGB → NCHW float32
            val inputTensor = bitmapToInputTensor(env, resized)

            // 推理
            val output = session.run(mapOf(session.inputNames.first() to inputTensor))

            // 提取 embedding
            val outputTensor = output.first().value
            val embedding = when (outputTensor) {
                is Array<*> -> (outputTensor[0] as FloatArray)
                is FloatArray -> outputTensor
                is Array<*> -> {
                    // 可能是 [1, 1, 512] 形状
                    (outputTensor[0] as Array<*>)[0] as FloatArray
                }
                else -> {
                    Logger.w(TAG, "Unexpected output type: ${outputTensor::class.java}")
                    return null
                }
            }

            if (embedding.size != EMBEDDING_DIM) {
                Logger.w(TAG, "Unexpected embedding dim: ${embedding.size}, expected $EMBEDDING_DIM")
                return null
            }

            // L2 归一化
            l2Normalize(embedding)
        } catch (e: Exception) {
            Logger.e(TAG, "Embedding extraction failed", e)
            null
        }
    }

    /**
     * Bitmap → ONNX Runtime float32 tensor (NCHW)
     *
     * 预处理: (pixel / 255.0 - 0.5) / 0.5 → 映射到 [-1, 1]
     * 输入形状: [1, 3, 112, 112]
     * 通道顺序: NCHW = [R_all_pixels, G_all_pixels, B_all_pixels]
     */
    private fun bitmapToInputTensor(
        env: ai.onnxruntime.OrtEnvironment,
        bitmap: Bitmap
    ): ai.onnxruntime.OnnxTensor {
        val totalPixels = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // 提取并归一化 RGB 通道
        val chR = FloatArray(totalPixels)
        val chG = FloatArray(totalPixels)
        val chB = FloatArray(totalPixels)

        for (i in 0 until totalPixels) {
            val px = pixels[i]
            chR[i] = ((px shr 16 and 0xFF) / 255.0f - 0.5f) / 0.5f
            chG[i] = ((px shr 8 and 0xFF) / 255.0f - 0.5f) / 0.5f
            chB[i] = ((px and 0xFF) / 255.0f - 0.5f) / 0.5f
        }

        // 拼接为 NCHW 顺序: 先所有 R, 再所有 G, 再所有 B
        val nchw = FloatArray(3 * totalPixels)
        System.arraycopy(chR, 0, nchw, 0, totalPixels)
        System.arraycopy(chG, 0, nchw, totalPixels, totalPixels)
        System.arraycopy(chB, 0, nchw, 2 * totalPixels, totalPixels)

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        return ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw), shape)
    }

    /**
     * L2 归一化 embedding 向量
     */
    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var norm = 0f
        for (v in embedding) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in embedding.indices) embedding[i] /= norm
        }
        return embedding
    }

    /**
     * 检查模型就绪（如需下载请手动操作）
     *
     * 模型获取方式：
     * 1. InsightFace 模型（推荐）：pip install insightface，运行 Python 代码：
     *    import insightface
     *    insightface.model_zoo.get_model('model_zoo/mobilefacenet.onnx')
     *    导出的模型文件在 ~/.insightface/models/ 目录下
     *
     * 2. OpenCV Zoo:
     *    https://github.com/opencv/opencv_zoo/blob/master/models/face_recognition/mobilefacenet/
     *
     * 3. 将下载好的 .onnx 文件放到以下路径（自动识别）:
     *    {app_files_dir}/models/face_embedding/mobilefacenet.onnx
     */
    fun ensureModel(): Boolean {
        if (isModelReady) return true
        modelDir.mkdirs()
        Logger.i(TAG, "Model file expected at: ${modelFile.absolutePath}")
        return false
    }

    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing ONNX session", e)
        }
        ortSession = null
        ortEnv = null
    }
}
