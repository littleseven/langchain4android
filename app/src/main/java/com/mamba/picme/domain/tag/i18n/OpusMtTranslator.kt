package com.mamba.picme.domain.tag.i18n

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import com.mamba.picme.sentencepiece.SentencePieceProcessor
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * OPUS-MT 轻量中英翻译引擎（ONNX Runtime + SentencePiece 后端）
 *
 * 使用 Helsinki-NLP/opus-mt-zh-en 的 ONNX INT8 量化版本，
 * 将中文搜索查询翻译为英文，供 MobileCLIP 语义搜索使用。
 *
 * 模型文件通过 ModelScope/模型中心下载到 llm_models/opus-mt-zh-en/ 目录。
 * 预期文件列表（来自 ModelScope: budaoshou/OPUS-MT-Zh-En-ONNX-INT8）：
 * - encoder_model_quantized.onnx（INT8 量化编码器）
 * - decoder_model_quantized.onnx（INT8 量化解码器，第 1 步：无 past_key_values）
 * - decoder_with_past_model_quantized.onnx（INT8 量化解码器，第 2+ 步：带 past_key_values）
 * - config.json（模型配置）
 * - source.spm（SentencePiece 中文编码模型）
 * - target.spm（SentencePiece 英文解码模型）
 *
 * 设计原则：
 * 1. 可选依赖：模型缺失时自动降级，不影响搜索功能
 * 2. 单例复用：OrtEnvironment 全局共享，Session 按需创建
 * 3. 线程安全：翻译调用串行化（Seq2Seq 模型不适合并发推理）
 *
 * @param context Application Context
 * @param modelDir 模型目录（外部注入，便于测试）
 */
class OpusMtTranslator(
    private val context: Context,
    private val modelDir: File? = null
) {
    companion object {
        private const val TAG = "OpusMtTranslator"

        /** 编码器模型文件名 */
        private const val ENCODER_MODEL_NAME = "encoder_model_quantized.onnx"

        /** 解码器模型文件名（第 1 步，无 past） */
        private const val DECODER_MODEL_NAME = "decoder_model_quantized.onnx"

        /** 解码器模型文件名（第 2+ 步，带 past_key_values） */
        private const val DECODER_WITH_PAST_MODEL_NAME = "decoder_with_past_model_quantized.onnx"

        /** 源语言 SentencePiece 模型 */
        private const val SOURCE_SPM_NAME = "source.spm"

        /** 目标语言 SentencePiece 模型 */
        private const val TARGET_SPM_NAME = "target.spm"

        /** 模型配置文件名 */
        private const val CONFIG_NAME = "config.json"

        /** 最大输入长度（与训练时一致） */
        private const val MAX_INPUT_LENGTH = 128

        /** 最大输出长度 */
        private const val MAX_OUTPUT_LENGTH = 64

        /** 共享的 ONNX Runtime 环境 */
        private val ortEnvironment: OrtEnvironment by lazy {
            OrtEnvironment.getEnvironment()
        }
    }

    /** 编码器 Session */
    private var encoderSession: OrtSession? = null

    /** 解码器 Session（第 1 步，无 past） */
    private var decoderSession: OrtSession? = null

    /** 解码器 Session（第 2+ 步，带 past_key_values） */
    private var decoderWithPastSession: OrtSession? = null

    /** 源语言 SentencePiece tokenizer（中文） */
    private var sourceTokenizer: SentencePieceProcessor? = null

    /** 目标语言 SentencePiece tokenizer（英文） */
    private var targetTokenizer: SentencePieceProcessor? = null

    /** 特殊 token ID */
    private var padTokenId: Int = 0
    private var eosTokenId: Int = 1
    private var unkTokenId: Int = 2

    /** 引擎是否已初始化 */
    val isInitialized: Boolean
        get() = encoderSession != null && decoderSession != null &&
                sourceTokenizer != null && targetTokenizer != null

    /** 模型目录（优先使用注入的，否则从 ModelPathConfig 获取） */
    private val resolvedModelDir: File
        get() = modelDir ?: com.mamba.picme.data.download.ModelPathConfig
            .getModelDir(context, MODEL_ID)

    /**
     * 初始化引擎：加载 ONNX 模型和 SentencePiece tokenizer
     *
     * @return 是否成功
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }

        val dir = resolvedModelDir
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "Model directory not found: ${dir.absolutePath}")
            return false
        }

        // 检查必需文件
        val encoderFile = File(dir, ENCODER_MODEL_NAME)
        val decoderFile = File(dir, DECODER_MODEL_NAME)
        val sourceSpmFile = File(dir, SOURCE_SPM_NAME)
        val targetSpmFile = File(dir, TARGET_SPM_NAME)

        if (!encoderFile.exists() || !decoderFile.exists()) {
            Log.w(TAG, "ONNX model files missing. Expected: $ENCODER_MODEL_NAME, $DECODER_MODEL_NAME")
            return false
        }

        if (!sourceSpmFile.exists() || !targetSpmFile.exists()) {
            Log.w(TAG, "SentencePiece models missing. Expected: $SOURCE_SPM_NAME, $TARGET_SPM_NAME")
            return false
        }

        return try {
            // 1. 加载 SentencePiece tokenizers
            loadTokenizers(sourceSpmFile, targetSpmFile)

            // 2. 创建 ONNX Session（使用与 sherpa-onnx 兼容的配置）
            val sessionOptions = OrtSession.SessionOptions().apply {
                // 使用 CPU 推理（翻译模型很小，CPU 足够）
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                // 内存优化
                setMemoryPatternOptimization(true)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            encoderSession = ortEnvironment.createSession(encoderFile.absolutePath, sessionOptions)
            decoderSession = ortEnvironment.createSession(decoderFile.absolutePath, sessionOptions)

            // 可选加载 decoder_with_past（如果存在）
            val decoderWithPastFile = File(dir, DECODER_WITH_PAST_MODEL_NAME)
            if (decoderWithPastFile.exists()) {
                decoderWithPastSession = ortEnvironment.createSession(decoderWithPastFile.absolutePath, sessionOptions)
                Log.i(TAG, "  DecoderWithPast: ${decoderWithPastFile.name} (${decoderWithPastFile.length() / 1024 / 1024}MB)")
            } else {
                Log.w(TAG, "decoder_with_past_model not found, fallback to decoder_model for all steps")
            }

            Log.i(TAG, "OpusMtTranslator initialized successfully")
            Log.i(TAG, "  Encoder: ${encoderFile.name} (${encoderFile.length() / 1024 / 1024}MB)")
            Log.i(TAG, "  Decoder: ${decoderFile.name} (${decoderFile.length() / 1024 / 1024}MB)")
            Log.i(TAG, "  Source tokenizer: vocabSize=${sourceTokenizer?.vocabSize()}")
            Log.i(TAG, "  Target tokenizer: vocabSize=${targetTokenizer?.vocabSize()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpusMtTranslator", e)
            release()
            false
        }
    }

    /**
     * 翻译中文查询为英文
     *
     * @param chineseText 中文输入（如 "公园里的猫"）
     * @return 英文翻译结果；失败时返回原输入
     */
    fun translate(chineseText: String): String {
        if (!isInitialized && !initialize()) {
            Log.w(TAG, "Engine not ready, returning original text")
            return chineseText
        }

        if (chineseText.isBlank()) return chineseText

        return try {
            val result = translateInternal(chineseText.trim())
            Log.d(TAG, "Translated: '$chineseText' -> '$result'")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed for '$chineseText', returning original", e)
            chineseText
        }
    }

    /**
     * 批量翻译（用于一次处理多个候选词）
     */
    fun translateBatch(texts: List<String>): List<String> {
        return texts.map { translate(it) }
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        encoderSession?.close()
        decoderSession?.close()
        decoderWithPastSession?.close()
        encoderSession = null
        decoderSession = null
        decoderWithPastSession = null
        sourceTokenizer?.close()
        targetTokenizer?.close()
        sourceTokenizer = null
        targetTokenizer = null
        Log.i(TAG, "Engine released")
    }

    // ===== 内部实现 =====

    /**
     * 核心翻译逻辑：Encoder-Decoder Seq2Seq 推理
     *
     * 简化实现：针对短查询优化，使用 greedy decoding
     */
    @Suppress("UNCHECKED_CAST")
    private fun translateInternal(input: String): String {
        val enc = encoderSession ?: throw IllegalStateException("Encoder not loaded")
        val dec = decoderSession ?: throw IllegalStateException("Decoder not loaded")
        val decPast = decoderWithPastSession
        val srcTok = sourceTokenizer ?: throw IllegalStateException("Source tokenizer not loaded")

        // 1. Tokenize 输入（中文 → token IDs）
        val inputIds = srcTok.encode(input).map { it.toLong() }.toLongArray()
        val inputLength = inputIds.size

        // 2. 编码器前向（获取 encoder hidden states）
        // 构建 attention_mask（所有有效 token 位置为 1）
        val attentionMask = LongArray(inputLength) { 1L }

        val encoderInputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            LongBuffer.wrap(inputIds),
            longArrayOf(1, inputLength.toLong())
        )

        val encoderAttentionMaskTensor = OnnxTensor.createTensor(
            ortEnvironment,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1, inputLength.toLong())
        )

        val encoderInputMap = java.util.HashMap<String, OnnxTensor>()
        encoderInputMap["input_ids"] = encoderInputTensor
        encoderInputMap["attention_mask"] = encoderAttentionMaskTensor
        val encoderOutputs = enc.run(encoderInputMap)
        val encoderHiddenStates = encoderOutputs[0] as OnnxTensor

        // 3. 解码器贪婪解码
        // 注意：当前模型不支持 past_key_values 单一输入（需要展开为多个张量），
        // 因此所有步骤都使用 decoder_model（不带 past），每次传完整序列。
        // 性能稍差但实现简单且正确。如需优化，需解析并展开 KV 缓存张量。
        val outputIds = mutableListOf<Long>(padTokenId.toLong())

        for (step in 0 until MAX_OUTPUT_LENGTH) {
            // 构建完整输入序列（包含所有已生成的 token）
            val decoderInputIds = LongArray(outputIds.size) { outputIds[it] }

            val decoderInputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                LongBuffer.wrap(decoderInputIds),
                longArrayOf(1, decoderInputIds.size.toLong())
            )

            // 构建解码器输入：所有步骤都需要 encoder_hidden_states 和 encoder_attention_mask
            val decoderInputs = java.util.HashMap<String, OnnxTensor>()
            decoderInputs["input_ids"] = decoderInputTensor
            decoderInputs["encoder_hidden_states"] = encoderHiddenStates
            decoderInputs["encoder_attention_mask"] = encoderAttentionMaskTensor

            val decoderOutputs = dec.run(decoderInputs)

            // 获取 logits（第 1 个输出）
            val logitsTensor = decoderOutputs[0]
            val outputValue = logitsTensor.value
            val logits = outputValue as Array<Array<FloatArray>>

            // 取最后一个时间步的 logits，greedy 选择最大概率
            val lastLogits = logits[0][decoderInputIds.size - 1]
            val nextTokenId = lastLogits.indices.maxByOrNull { lastLogits[it] } ?: unkTokenId

            logitsTensor.close()
            decoderInputTensor.close()

            if (nextTokenId == eosTokenId) break
            outputIds.add(nextTokenId.toLong())
        }
        encoderHiddenStates.close()
        encoderAttentionMaskTensor.close()
        encoderInputTensor.close()

        // 4. 将输出 token IDs 解码为文本（使用 target tokenizer）
        return detokenize(outputIds.drop(1)) // 去掉起始 token
    }

    /**
     * 加载 SentencePiece tokenizers
     */
    private fun loadTokenizers(sourceSpmFile: File, targetSpmFile: File) {
        try {
            sourceTokenizer = SentencePieceProcessor().apply {
                loadModel(sourceSpmFile.absolutePath)
            }
            Log.i(TAG, "Source SentencePiece loaded: ${sourceSpmFile.name}, vocabSize=${sourceTokenizer?.vocabSize()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load source SentencePiece model", e)
            throw e
        }

        try {
            targetTokenizer = SentencePieceProcessor().apply {
                loadModel(targetSpmFile.absolutePath)
            }
            Log.i(TAG, "Target SentencePiece loaded: ${targetSpmFile.name}, vocabSize=${targetTokenizer?.vocabSize()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load target SentencePiece model", e)
            throw e
        }

        // 加载模型配置获取特殊 token
        val configFile = File(resolvedModelDir, CONFIG_NAME)
        if (configFile.exists()) {
            val configJson = JSONObject(configFile.readText())
            padTokenId = configJson.optInt("pad_token_id", 0)
            eosTokenId = configJson.optInt("eos_token_id", 1)
            unkTokenId = configJson.optInt("unk_token_id", 2)
        }
    }

    /**
     * 将 token ID 数组解码为文本（使用 target SentencePiece tokenizer）
     */
    private fun detokenize(ids: List<Long>): String {
        val tgtTok = targetTokenizer
        if (tgtTok == null) {
            return ids.filter { it != padTokenId.toLong() && it != eosTokenId.toLong() }
                .map { it.toInt().toChar() }
                .joinToString("")
        }

        return try {
            // 过滤特殊 token 后解码
            val filteredIds = ids
                .filter { it != padTokenId.toLong() && it != eosTokenId.toLong() }
                .map { it.toInt() }
                .toIntArray()
            if (filteredIds.isEmpty()) return ""
            tgtTok.decode(filteredIds)
        } catch (e: Exception) {
            Log.w(TAG, "SentencePiece detokenization failed, using manual fallback", e)
            ids.filter { it != padTokenId.toLong() && it != eosTokenId.toLong() }
                .map { it.toInt().toChar() }
                .joinToString("")
        }
    }

    /** 模型 ID 常量 */
    private val MODEL_ID = "opus-mt-zh-en"
}

/**
 * 翻译结果封装
 */
data class TranslationResult(
    val original: String,
    val translated: String,
    val success: Boolean
)
