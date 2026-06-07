package com.picme.agent.core.voice

import android.content.Context
import com.k2fsa.sherpa.mnn.AsrConfigManager
import com.k2fsa.sherpa.mnn.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.mnn.OnlineRecognizer
import com.k2fsa.sherpa.mnn.OnlineRecognizerConfig
import com.k2fsa.sherpa.mnn.OnlineStream
import com.k2fsa.sherpa.mnn.getEndpointConfig
import com.k2fsa.sherpa.mnn.getFeatureConfig
import com.picme.agent.core.Logger
import com.picme.agent.core.mnn.MnnResourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

/**
 * Sherpa-MNN ASR 引擎实现
 *
 * 使用 MNN 官方集成的 sherpa-onnx-mnn 库进行端侧语音识别。
 * 支持两种模式：
 * 1. 离线模式（Push-to-Talk）：一次性送入所有音频
 * 2. 实时流式模式（Streaming）：持续送入音频 chunk，实时获取结果
 *
 * 流式模式参考 MnnLlmChat AsrService 的实现：
 * - 每 100ms 读取一个音频 chunk
 * - 送入 OnlineStream.acceptWaveform()
 * - 循环 decode → 检查 endpoint → 返回结果 → reset stream
 *
 * @param context Application Context
 * @param modelDir 模型目录路径（包含 encoder/decoder/joiner .mnn 和 tokens.txt）
 */
class SherpaMnnAsrEngine(
    private val context: Context,
    private val modelDir: String
) : AsrEngine {

    private val tag = "SherpaMnnAsr"
    private var recognizer: OnlineRecognizer? = null
    private val initLock = Object()
    private val resourceManager = MnnResourceManager.getInstance(context)

    // 流式识别状态
    private var isStreaming = AtomicBoolean(false)
    private var streamingThread: Thread? = null
    private var audioRecorder: AudioRecorder? = null
    private var streamingScope: CoroutineScope? = null
    private var streamingJob: Job? = null

    /**
     * 是否已向 ResourceManager 注册引用
     */
    private val isRegistered = AtomicBoolean(false)

    init {
        resourceManager.registerSoftTrimListener(::onSoftTrim)
        resourceManager.registerSafeUnloadListener(::onSafeUnload)
    }

    companion object {
        private const val TAG = "SherpaMnnAsr"
        /**
         * 使用 dlopen(RTLD_GLOBAL) 加载 libMNN_Express.so 和 libsherpa-mnn-jni.so。
         *
         * 背景：sherpa-mnn-jni.so 依赖 libMNN_Express.so 中的 MNN::Express::Module::load 符号。
         * 但 Android System.loadLibrary 使用类加载器命名空间隔离，dlopen(RTLD_GLOBAL) 的符号
         * 对 System.loadLibrary 不可见。因此必须先 dlopen(RTLD_GLOBAL) libMNN_Express.so，
         * 再 dlopen libsherpa-mnn-jni.so，这样 linker 才能在同一个命名空间中解析符号。
         *
         * 如果加载失败（如 MNN 版本不兼容），引擎将报告不可用，避免启动崩溃。
         */
        val isJniLoaded: Boolean = try {
            // MNN 已合并 Express 符号到 libMNN.so，直接加载 sherpa-mnn-jni
            System.loadLibrary("sherpa-mnn-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI load failed: ${e.message}")
            false
        }
    }

    override fun isAvailable(): Boolean {
        if (!isJniLoaded) return false
        return recognizer != null || tryInitRecognizer()
    }

    /**
     * 是否支持实时流式识别
     */
    override fun supportsStreaming(): Boolean = true

    /**
     * 将音频数据转录为文本（离线模式）
     *
     * 音频数据格式：16kHz, 16bit, 单声道 PCM
     * 使用 OnlineRecognizer 模拟离线识别（一次性送入所有音频）
     */
    override suspend fun transcribe(audioData: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext Result.failure(
                    IllegalStateException("Sherpa-MNN recognizer not initialized")
                )
            }

            val recog = recognizer ?: return@withContext Result.failure(
                IllegalStateException("Recognizer is null")
            )

            @Suppress("TooGenericExceptionCaught")
            try {
                // 1. 将 16bit PCM ByteArray 转为 FloatArray（归一化到 [-1, 1]）
                val samples = pcm16ToFloatArray(audioData)
                Logger.d(tag, "Transcribing ${samples.size} samples (${audioData.size} bytes)")

                // 2. 创建识别流
                val stream = recog.createStream("")

                // 3. 送入音频数据
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()

                // 4. 循环解码直到没有更多结果
                while (recog.isReady(stream)) {
                    recog.decode(stream)
                }

                // 5. 获取最终结果
                val result = recog.getResult(stream)
                val text = result.text.trim()

                stream.release()

                Logger.i(tag, "ASR result: '$text'")
                Result.success(text)
            } catch (e: Exception) {
                Logger.e(tag, "ASR transcription failed", e)
                Result.failure(e)
            }
        }

    /**
     * 开始实时流式识别
     *
     * 参考 AsrService.processSamples():
     * - 启动 AudioRecord 持续录音
     * - 每 100ms 读取一个 chunk（约 1600 samples）
     * - 送入 OnlineStream，循环 decode
     * - 检测到 endpoint 时返回结果并 reset stream
     */
    override fun startStreaming(
        onPartialResult: ((String) -> Unit)?,
        onFinalResult: (String) -> Unit
    ) {
        if (isStreaming.get()) {
            Logger.w(tag, "Streaming already started")
            return
        }
        if (!isAvailable()) {
            Logger.e(tag, "Recognizer not available for streaming")
            return
        }

        val recog = recognizer ?: run {
            Logger.e(tag, "Recognizer is null")
            return
        }

        isStreaming.set(true)
        streamingScope = CoroutineScope(Dispatchers.IO)

        streamingJob = streamingScope?.launch {
            val recorder = AudioRecorder(context)
            audioRecorder = recorder

            val started = recorder.start()
            if (!started) {
                Logger.e(tag, "Failed to start audio recorder for streaming")
                isStreaming.set(false)
                return@launch
            }

            Logger.i(tag, "Streaming ASR started")

            // 创建识别流
            val stream = recog.createStream("")

            val interval = 0.1 // 100ms per chunk
            val bufferSize = (interval * SAMPLE_RATE).toInt() // samples per chunk
            val buffer = ShortArray(bufferSize)

            try {
                while (isStreaming.get()) {
                    val ret = recorder.readShortArray(buffer, 0, buffer.size)
                    if (ret > 0) {
                        // 转换为 FloatArray 并归一化
                        val samples = FloatArray(ret) { i -> buffer[i] / 32768.0f }

                        // 送入音频数据
                        stream.acceptWaveform(samples, SAMPLE_RATE)

                        // 循环解码
                        while (recog.isReady(stream)) {
                            recog.decode(stream)
                        }

                        // 检查端点
                        val isEndpoint = recog.isEndpoint(stream)

                        // 获取当前结果（用于 partial result）
                        val currentResult = recog.getResult(stream)
                        if (currentResult.text.isNotBlank()) {
                            onPartialResult?.invoke(currentResult.text.trim())
                        }

                        if (isEndpoint) {
                            val text = currentResult.text.trim()
                            if (text.isNotEmpty()) {
                                Logger.i(tag, "Streaming ASR endpoint: '$text'")
                                withContext(Dispatchers.Main) {
                                    onFinalResult(text)
                                }
                            }
                            // Reset stream for next utterance
                            recog.reset(stream)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(tag, "Streaming ASR error", e)
            } finally {
                stream.release()
                recorder.stop()
                audioRecorder = null
                Logger.i(tag, "Streaming ASR stopped")
            }
        }
    }

    /**
     * 停止实时流式识别
     *
     * 等待流式协程完全结束后才返回，确保 recognizer 不会在推理中被释放。
     */
    override fun stopStreaming() {
        if (!isStreaming.get()) return
        isStreaming.set(false)
        audioRecorder?.stop()

        // 等待流式协程结束（最多 2 秒），防止 recognizer 在推理中被释放导致崩溃
        val job = streamingJob
        if (job != null && job.isActive) {
            try {
                val latch = CountDownLatch(1)
                streamingScope?.launch {
                    job.join()
                    latch.countDown()
                }
                // 阻塞等待最多 2 秒
                val finished = latch.await(2000, TimeUnit.MILLISECONDS)
                if (!finished) {
                    Logger.w(tag, "Streaming job did not finish within 2s, proceeding anyway")
                }
            } catch (e: Exception) {
                Logger.w(tag, "Error waiting for streaming job", e)
            }
        }

        streamingScope?.launch { } // 取消所有协程
        streamingJob = null
        Logger.d(tag, "Streaming ASR stopped")
    }

    /**
     * 尝试初始化识别器
     */
    private fun tryInitRecognizer(): Boolean {
        synchronized(initLock) {
            if (recognizer != null) return true

            return try {
                val dir = File(modelDir)
                if (!dir.exists() || !dir.isDirectory) {
                    Logger.w(tag, "Model directory not found: $modelDir")
                    return false
                }

                // 检查必需文件是否存在
                val requiredFiles = listOf(
                    "encoder-epoch-99-avg-1.int8.mnn",
                    "decoder-epoch-99-avg-1.int8.mnn",
                    "joiner-epoch-99-avg-1.int8.mnn",
                    "tokens.txt"
                )
                val missingFiles = requiredFiles.filter { !File(dir, it).exists() }
                if (missingFiles.isNotEmpty()) {
                    Logger.w(tag, "Missing model files: $missingFiles")
                    return false
                }

                Logger.i(tag, "Initializing Sherpa-MNN recognizer with model: $modelDir")

                // 使用 AsrConfigManager 自动推断配置
                val modelConfig = AsrConfigManager.getModelConfigFromDirectory(modelDir)
                    ?: run {
                        Logger.e(tag, "Failed to get model config from $modelDir")
                        return false
                    }

                val config = OnlineRecognizerConfig(
                    featConfig = getFeatureConfig(SAMPLE_RATE, FEATURE_DIM),
                    modelConfig = modelConfig,
                    lmConfig = AsrConfigManager.getLmConfigFromDirectory(modelDir),
                    ctcFstDecoderConfig = OnlineCtcFstDecoderConfig("", 3000),
                    endpointConfig = getEndpointConfig(),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4,
                    hotwordsFile = "",
                    hotwordsScore = 1.5f,
                    ruleFsts = "",
                    ruleFars = "",
                )

                recognizer = OnlineRecognizer(null, config)
                ensureRegistered()
                Logger.i(tag, "Sherpa-MNN recognizer initialized successfully")
                true
            } catch (e: Exception) {
                Logger.e(tag, "Failed to initialize Sherpa-MNN recognizer", e)
                false
            }
        }
    }

    /**
     * 释放识别器资源
     *
     * 通过 ResourceManager 协调释放，避免与 LLM 的 MNN 全局状态冲突。
     */
    fun release() {
        resourceManager.releaseAsr(
            owner = "SherpaMnnAsrEngine",
            onSafeUnload = ::performUnload,
            onSoftRelease = ::softRelease
        )
        isRegistered.set(false)
    }

    /**
     * 软释放：停止流式识别，保留 recognizer 实例
     */
    private fun softRelease() {
        stopStreaming()
        Logger.i(tag, "ASR soft released (recognizer kept)")
    }

    /**
     * 完全卸载：释放 recognizer
     *
     * 注意：此操作会调用 MNN native 释放，必须通过 MnnGlobalReleaseLock 串行化，
     * 防止与人脸检测或 LLM 的 MNN 释放并发导致崩溃。
     */
    private fun performUnload() {
        // 先停止流式识别，等待协程结束
        stopStreaming()
        synchronized(initLock) {
            recognizer?.release()
            recognizer = null
            Logger.i(tag, "ASR fully unloaded")
        }
    }

    private fun onSoftTrim() {
        if (isStreaming.get()) {
            stopStreaming()
        }
    }

    private fun onSafeUnload() {
        performUnload()
        isRegistered.set(false)
    }

    private fun ensureRegistered() {
        if (isRegistered.compareAndSet(false, true)) {
            resourceManager.acquireAsr("SherpaMnnAsrEngine")
        }
    }

    /**
     * 将 16bit PCM ByteArray 转换为 FloatArray（归一化到 [-1, 1]）
     */
    private fun pcm16ToFloatArray(pcmData: ByteArray): FloatArray {
        if (pcmData.size % 2 != 0) {
            // 奇数字节，截断最后一个字节
            return pcm16ToFloatArray(pcmData.copyOf(pcmData.size - 1))
        }

        val sampleCount = pcmData.size / 2
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(sampleCount) { i ->
            buffer.getShort(i * 2).toFloat() / 32768.0f
        }
    }
}

private const val SAMPLE_RATE = 16000
private const val FEATURE_DIM = 80
