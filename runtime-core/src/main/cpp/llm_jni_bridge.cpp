#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <mutex>
#include <streambuf>
#include <dlfcn.h>
#include <chrono>
#include <vector>
#include <android/bitmap.h>

#include <MNN/llm/llm.hpp>

#define LOG_TAG "PicMe:LlmJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

static std::mutex g_llm_mutex;

// ── 性能指标结构 ──────────────────────────────────────
struct LlmMetrics {
    int64_t prompt_len = 0;
    int64_t decode_len = 0;
    int64_t vision_time = 0;
    int64_t audio_time = 0;
    int64_t prefill_time = 0;
    int64_t decode_time = 0;
};

/**
 * 恢复 Android 预编译 MNN 库的 stepping 状态。
 *
 * 背景：Android 预编译的 libMNN.so 在每次 generate() 后可能将 context->status
 * 设置为 MAX_TOKENS_FINISHED 或 NORMAL_FINISHED。如果不重置为 RUNNING，
 * 下次调用 response()/generate() 会失败。
 *
 * 参考：官方 MnnLlmChat demo 的 llm_session.cpp restoreAndroidSteppingStatusIfNeeded()
 */
static void restoreLlmStatusIfNeeded(MNN::Transformer::Llm* llm) {
    if (llm == nullptr) {
        return;
    }
    const auto* context = llm->getContext();
    if (context == nullptr) {
        return;
    }
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        auto* mutableContext = const_cast<MNN::Transformer::LlmContext*>(context);
        mutableContext->status = MNN::Transformer::LlmStatus::RUNNING;
        LOGD("LLM status restored to RUNNING (was FINISHED)");
    }
}

/**
 * 自定义 streambuf，用于收集 generate() 输出的 token
 * 参考官方 MnnLlmChat 实现，通过回调机制实时传递数据
 */
class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char *str, size_t len)>;

    explicit LlmStreamBuffer(CallBack callback) : callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char *s, std::streamsize n) override {
        if (callback_) {
            callback_(s, n);
        }
        return n;
    }

    virtual int_type overflow(int_type c) override {
        if (c != traits_type::eof() && callback_) {
            char ch = static_cast<char>(c);
            callback_(&ch, 1);
        }
        return c;
    }

private:
    CallBack callback_ = nullptr;
};

// ── 流式生成辅助：将 C++ token 回调桥接到 Java ──────────
static void streamTokensToJava(
        JNIEnv* env,
        jobject progressListener,
        jmethodID onTokenMethod,
        const std::string& token,
        bool isEop,
        bool& stopRequested) {
    if (progressListener == nullptr || onTokenMethod == nullptr) {
        return;
    }
    jstring tokenStr = isEop ? nullptr : env->NewStringUTF(token.c_str());
    jboolean shouldStop = env->CallBooleanMethod(progressListener, onTokenMethod, tokenStr, isEop);
    if (tokenStr != nullptr) {
        env->DeleteLocalRef(tokenStr);
    }
    if (shouldStop == JNI_TRUE) {
        stopRequested = true;
    }
}

JNIEXPORT jlong JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeCreate(
        JNIEnv *env,
        jclass clazz,
        jstring configPath) {

    const char *configCStr = env->GetStringUTFChars(configPath, nullptr);
    LOGD("Creating LLM with config: %s", configCStr);

    MNN::Transformer::Llm *llm = MNN::Transformer::Llm::createLLM(std::string(configCStr));
    env->ReleaseStringUTFChars(configPath, configCStr);

    if (llm == nullptr) {
        LOGE("Failed to create LLM");
        return 0;
    }

    bool loaded = llm->load();
    if (!loaded) {
        LOGE("Failed to load LLM model");
        MNN::Transformer::Llm::destroy(llm);
        return 0;
    }

    LOGD("LLM created and loaded successfully");
    return reinterpret_cast<jlong>(llm);
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm != nullptr) {
        MNN::Transformer::Llm::destroy(llm);
        LOGD("LLM destroyed");
    }
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeReset(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm != nullptr) {
        llm->reset();
        LOGD("LLM reset (history cleared)");
    }
}

// ── 同步生成（保留原有实现）─────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerate(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring prompt,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        return env->NewStringUTF("");
    }

    const char *promptCStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptCStr);
    env->ReleaseStringUTFChars(prompt, promptCStr);

    LOGD("Generating response for prompt: %s", promptStr.c_str());

    std::ostringstream oss;
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(promptStr, &oss, nullptr, maxNewTokens);
    }

    std::string result = oss.str();

    // 分段打印长 response，避免 Android 日志长度限制截断
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("Generated response: %s", result.c_str());
    } else {
        LOGD("Generated response (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t len = (i + LOG_CHUNK_SIZE <= result.length()) ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, len);
            LOGD("  [chunk %zu/%zu]: %s", i / LOG_CHUNK_SIZE + 1,
                 (result.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE, chunk.c_str());
        }
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithSystem(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    const char *systemCStr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *userCStr = env->GetStringUTFChars(userPrompt, nullptr);

    std::string systemStr(systemCStr);
    std::string userStr(userCStr);

    env->ReleaseStringUTFChars(systemPrompt, systemCStr);
    env->ReleaseStringUTFChars(userPrompt, userCStr);

    MNN::Transformer::ChatMessages messages;
    messages.emplace_back("system", systemStr);
    messages.emplace_back("user", userStr);

    LOGD("Generating with system prompt + user prompt");

    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(messages, &oss, nullptr, maxNewTokens);
    }
    auto end = std::chrono::high_resolution_clock::now();
    // 同步生成无法区分 prefill/decode，将总耗时全部计入 decode_time
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = oss.str();
    // 分段打印长 response，避免 Android 日志长度限制截断
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("Generated response: %s", result.c_str());
    } else {
        LOGD("Generated response (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t len = (i + LOG_CHUNK_SIZE <= result.length()) ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, len);
            LOGD("  [chunk %zu/%zu]: %s", i / LOG_CHUNK_SIZE + 1,
                 (result.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE, chunk.c_str());
        }
    }

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}

// ── 多模态图片生成 + 性能指标 ─────────────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithImage(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jobject bitmap,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    // 1. 获取 system / user prompt
    const char *systemCStr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *userCStr = env->GetStringUTFChars(userPrompt, nullptr);
    std::string systemStr(systemCStr);
    std::string userStr(userCStr);
    env->ReleaseStringUTFChars(systemPrompt, systemCStr);
    env->ReleaseStringUTFChars(userPrompt, userCStr);

    // 2. 从 Android Bitmap 提取像素数据
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
        LOGE("Failed to get bitmap info");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed to get bitmap info"));
        return hashMap;
    }

    void *bitmapPixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed to lock bitmap pixels"));
        return hashMap;
    }

    int width = bitmapInfo.width;
    int height = bitmapInfo.height;

    LOGD("[Vision] Image: %dx%d, format=%d, stride=%d", width, height, bitmapInfo.format, bitmapInfo.stride);

    // 手动 RGBA_8888 → RGB float32 NCHW 转换 + Qwen3.5 归一化
    // 参数来自 llm_config.json: image_mean=[127.5], image_norm=[1/127.5]
    // 公式: (pixel - 127.5) / 127.5 → 范围 [-1.0, 1.0]
    constexpr float MEAN  = 127.5f;
    constexpr float NORM  = 0.00784313725490196f;  // 1/127.5

    std::vector<float> rgbData(3 * height * width);
    uint8_t *pixels = static_cast<uint8_t *>(bitmapPixels);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int srcIdx = y * bitmapInfo.stride + x * 4;
            int dstR = 0 * height * width + y * width + x;
            int dstG = 1 * height * width + y * width + x;
            int dstB = 2 * height * width + y * width + x;
            rgbData[dstR] = (static_cast<float>(pixels[srcIdx])     - MEAN) * NORM;
            rgbData[dstG] = (static_cast<float>(pixels[srcIdx + 1]) - MEAN) * NORM;
            rgbData[dstB] = (static_cast<float>(pixels[srcIdx + 2]) - MEAN) * NORM;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    // 创建 MNN VARP: _Const(data, {1,3,H,W}, NCHW, float32)
    std::vector<int> imageShape = {1, 3, height, width};
    auto imageVar = MNN::Express::_Const(
        rgbData.data(), imageShape, MNN::Express::NCHW, halide_type_of<float>());

    // 3. 构建 MultimodalPrompt（Qwen-VL chat template）
    MNN::Transformer::MultimodalPrompt multimodal;
    multimodal.prompt_template =
        "<|im_start|>system\n" + systemStr + "<|im_end|>\n"
        "<|im_start|>user\n"
        "<|vision_start|><|image_pad|><|vision_end|>" + userStr + "<|im_end|>\n"
        "<|im_start|>assistant\n";
    multimodal.images["image"] = {imageVar, width, height};

    LOGD("[Vision] Generating: system=%zu chars, user=%zu chars, maxTokens=%d",
         systemStr.size(), userStr.size(), maxNewTokens);

    // 4. 调用 MNN-LLM multimodal response
    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(multimodal, &oss, nullptr, maxNewTokens);
    }
    auto end = std::chrono::high_resolution_clock::now();
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = oss.str();

    // 日志：打印识别结果 + 性能指标
    LOGD("═══════════════════════════════════════════");
    LOGD("[Vision] ║ 图片识别结果 (Image Recognition Result)");
    LOGD("[Vision] ╠═══════════════════════════════════════");
    const size_t LOG_CHUNK_SIZE = 512;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("[Vision] ║ %s", result.c_str());
    } else {
        LOGD("[Vision] ║ (len=%zu chars):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t chunkLen = (i + LOG_CHUNK_SIZE <= result.length())
                ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, chunkLen);
            LOGD("[Vision] ║   %s", chunk.c_str());
        }
    }
    LOGD("[Vision] ╠═══════════════════════════════════════");
    LOGD("[Vision] ║ 性能: prompt=%lld tokens, decode=%lld tokens",
         (long long)metrics.prompt_len, (long long)metrics.decode_len);
    LOGD("[Vision] ║ 耗时: vision=%lld us, decode=%lld us",
         (long long)metrics.vision_time, (long long)metrics.decode_time);
    if (metrics.prompt_len > 0 && metrics.decode_time > 0) {
        float totalSpeed = (metrics.prompt_len + metrics.decode_len) * 1000000.0f / metrics.decode_time;
        LOGD("[Vision] ║ 速度: %.1f tokens/s", totalSpeed);
    }
    LOGD("═══════════════════════════════════════════");

    // 5. 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}

// ── 流式生成 + 性能指标（新增）──────────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateStream(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring prompt,
        jint maxNewTokens,
        jobject progressListener) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    const char *promptCStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptCStr);
    env->ReleaseStringUTFChars(prompt, promptCStr);

    LOGD("Stream generating for prompt: %s", promptStr.c_str());

    // 获取 Java 回调方法
    jclass listenerClass = env->GetObjectClass(progressListener);
    jmethodID onTokenMethod = env->GetMethodID(listenerClass, "onToken",
                                               "(Ljava/lang/String;Z)Z");
    if (onTokenMethod == nullptr) {
        LOGE("ProgressListener onToken method not found");
    }

    LlmMetrics metrics;
    std::stringstream response_buffer;
    bool stopRequested = false;
    bool generateTextEnd = false;

    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);

        auto prefill_start = std::chrono::high_resolution_clock::now();

        // 使用 Utf8StreamProcessor 处理流式输出，确保中文字符完整
        std::string utf8Buffer;
        auto processUtf8Chunk = [&](const char *str, size_t len) {
            utf8Buffer.append(str, len);

            size_t i = 0;
            std::string completeChars;
            while (i < utf8Buffer.size()) {
                unsigned char byte = static_cast<unsigned char>(utf8Buffer[i]);
                int charLen = 0;
                if ((byte & 0x80) == 0) charLen = 1;
                else if ((byte & 0xE0) == 0xC0) charLen = 2;
                else if ((byte & 0xF0) == 0xE0) charLen = 3;
                else if ((byte & 0xF8) == 0xF0) charLen = 4;
                else break;

                if (i + charLen > utf8Buffer.size()) break;
                completeChars.append(utf8Buffer, i, charLen);
                i += charLen;
            }
            utf8Buffer = utf8Buffer.substr(i);

            if (!completeChars.empty()) {
                // 检查是否包含 <eop>（end-of-prefill 标记）
                bool isEop = (completeChars.find("<eop>") != std::string::npos);
                if (isEop) {
                    generateTextEnd = true;
                }

                // 将 <eop> 从展示文本/最终回复中移除，避免 UI 显示出来
                std::string displayChars = completeChars;
                size_t eopPos = displayChars.find("<eop>");
                if (eopPos != std::string::npos) {
                    displayChars.erase(eopPos, 5); // strlen("<eop>") == 5
                }
                if (!displayChars.empty()) {
                    response_buffer << displayChars;
                }

                streamTokensToJava(env, progressListener, onTokenMethod,
                                   displayChars, isEop, stopRequested);
            }
        };

        LlmStreamBuffer stream_buffer(processUtf8Chunk);
        std::ostream output_ostream(&stream_buffer);

        // prefill 阶段：response(..., 0) 只编码不生成
        llm->response(promptStr, &output_ostream, "<eop>", 0);

        auto prefill_end = std::chrono::high_resolution_clock::now();
        metrics.prefill_time = std::chrono::duration_cast<std::chrono::microseconds>(
                prefill_end - prefill_start).count();

        // 获取 prefill 后的上下文信息
        auto* ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.prompt_len = ctx->prompt_len;
        }

        // decode 阶段：逐 token 生成
        auto decode_start = std::chrono::high_resolution_clock::now();
        int current_size = 0;

        while (!stopRequested && !generateTextEnd && current_size < maxNewTokens) {
            llm->generate(1);
            current_size++;

            // 检查状态
            ctx = llm->getContext();
            if (ctx != nullptr) {
                if (ctx->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED ||
                    ctx->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED) {
                    generateTextEnd = true;
                }
            }
        }

        // 处理缓冲区中剩余的未完整 UTF-8 字符
        if (!utf8Buffer.empty() && !generateTextEnd) {
            response_buffer << utf8Buffer;
            streamTokensToJava(env, progressListener, onTokenMethod,
                               utf8Buffer, false, stopRequested);
        }

        auto decode_end = std::chrono::high_resolution_clock::now();
        metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
                decode_end - decode_start).count();

        // 获取最终指标
        ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.decode_len = ctx->gen_seq_len;
            metrics.vision_time = ctx->vision_us;
            metrics.audio_time = ctx->audio_us;
        }
    }

    std::string result = response_buffer.str();
    LOGD("Stream generation complete. len=%zu, prompt=%ld, decode=%ld, prefill_time=%ldus, decode_time=%ldus",
         result.length(), metrics.prompt_len, metrics.decode_len,
         metrics.prefill_time, metrics.decode_time);

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    env->DeleteLocalRef(listenerClass);

    return hashMap;
}

// ── 多轮对话同步生成（含性能指标）───────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithHistory(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject historyList,  // List<Pair<String, String>>
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    // 解析 Java List<Pair<String, String>> 到 C++ ChatMessages
    MNN::Transformer::ChatMessages messages;

    jclass listClass = env->GetObjectClass(historyList);
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    jint listSize = env->CallIntMethod(historyList, sizeMethod);

    jclass pairClass = env->FindClass("android/util/Pair");
    jfieldID firstField = env->GetFieldID(pairClass, "first", "Ljava/lang/Object;");
    jfieldID secondField = env->GetFieldID(pairClass, "second", "Ljava/lang/Object;");

    for (jint i = 0; i < listSize; i++) {
        jobject pairObj = env->CallObjectMethod(historyList, getMethod, i);
        if (pairObj == nullptr) continue;

        jobject roleObj = env->GetObjectField(pairObj, firstField);
        jobject contentObj = env->GetObjectField(pairObj, secondField);

        const char *role = nullptr;
        const char *content = nullptr;
        if (roleObj != nullptr) {
            role = env->GetStringUTFChars((jstring) roleObj, nullptr);
        }
        if (contentObj != nullptr) {
            content = env->GetStringUTFChars((jstring) contentObj, nullptr);
        }

        if (role && content) {
            messages.emplace_back(std::string(role), std::string(content));
        }

        if (role) env->ReleaseStringUTFChars((jstring) roleObj, role);
        if (content) env->ReleaseStringUTFChars((jstring) contentObj, content);
        env->DeleteLocalRef(pairObj);
        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
    }

    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(pairClass);

    LOGD("Generating with history (sync), messages=%zu", messages.size());

    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(messages, &oss, nullptr, maxNewTokens);
    }
    auto end = std::chrono::high_resolution_clock::now();
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = oss.str();
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("History sync generated (len=%zu): %s", result.length(), result.c_str());
    } else {
        LOGD("History sync generated (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t len = (i + LOG_CHUNK_SIZE <= result.length()) ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, len);
            LOGD("  [chunk %zu/%zu]: %s", i / LOG_CHUNK_SIZE + 1,
                 (result.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE, chunk.c_str());
        }
    }

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}

// ── 多轮对话流式生成（已有）─────────────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithHistoryStream(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject historyList,  // List<Pair<String, String>>
        jint maxNewTokens,
        jobject progressListener) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    // 解析 Java List<Pair<String, String>> 到 C++ ChatMessages
    MNN::Transformer::ChatMessages messages;

    jclass listClass = env->GetObjectClass(historyList);
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    jint listSize = env->CallIntMethod(historyList, sizeMethod);

    jclass pairClass = env->FindClass("android/util/Pair");
    jfieldID firstField = env->GetFieldID(pairClass, "first", "Ljava/lang/Object;");
    jfieldID secondField = env->GetFieldID(pairClass, "second", "Ljava/lang/Object;");

    for (jint i = 0; i < listSize; i++) {
        jobject pairObj = env->CallObjectMethod(historyList, getMethod, i);
        if (pairObj == nullptr) continue;

        jobject roleObj = env->GetObjectField(pairObj, firstField);
        jobject contentObj = env->GetObjectField(pairObj, secondField);

        const char *role = nullptr;
        const char *content = nullptr;
        if (roleObj != nullptr) {
            role = env->GetStringUTFChars((jstring) roleObj, nullptr);
        }
        if (contentObj != nullptr) {
            content = env->GetStringUTFChars((jstring) contentObj, nullptr);
        }

        if (role && content) {
            messages.emplace_back(std::string(role), std::string(content));
        }

        if (role) env->ReleaseStringUTFChars((jstring) roleObj, role);
        if (content) env->ReleaseStringUTFChars((jstring) contentObj, content);
        env->DeleteLocalRef(pairObj);
        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
    }

    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(pairClass);

    LOGD("Stream generating with history, messages=%zu", messages.size());

    // 获取 Java 回调方法
    jclass listenerClass = env->GetObjectClass(progressListener);
    jmethodID onTokenMethod = env->GetMethodID(listenerClass, "onToken",
                                               "(Ljava/lang/String;Z)Z");
    if (onTokenMethod == nullptr) {
        LOGE("ProgressListener onToken method not found");
    }

    LlmMetrics metrics;
    std::stringstream response_buffer;
    bool stopRequested = false;
    bool generateTextEnd = false;

    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);

        auto prefill_start = std::chrono::high_resolution_clock::now();

        // 使用 Utf8StreamProcessor 处理流式输出，确保中文字符完整
        std::string utf8Buffer;
        auto processUtf8Chunk = [&](const char *str, size_t len) {
            utf8Buffer.append(str, len);

            size_t i = 0;
            std::string completeChars;
            while (i < utf8Buffer.size()) {
                unsigned char byte = static_cast<unsigned char>(utf8Buffer[i]);
                int charLen = 0;
                if ((byte & 0x80) == 0) charLen = 1;
                else if ((byte & 0xE0) == 0xC0) charLen = 2;
                else if ((byte & 0xF0) == 0xE0) charLen = 3;
                else if ((byte & 0xF8) == 0xF0) charLen = 4;
                else break;

                if (i + charLen > utf8Buffer.size()) break;
                completeChars.append(utf8Buffer, i, charLen);
                i += charLen;
            }
            utf8Buffer = utf8Buffer.substr(i);

            if (!completeChars.empty()) {
                // 检查是否包含 <eop>（end-of-prefill 标记）
                bool isEop = (completeChars.find("<eop>") != std::string::npos);
                if (isEop) {
                    generateTextEnd = true;
                }

                // 将 <eop> 从展示文本/最终回复中移除，避免 UI 显示出来
                std::string displayChars = completeChars;
                size_t eopPos = displayChars.find("<eop>");
                if (eopPos != std::string::npos) {
                    displayChars.erase(eopPos, 5); // strlen("<eop>") == 5
                }
                if (!displayChars.empty()) {
                    response_buffer << displayChars;
                }

                streamTokensToJava(env, progressListener, onTokenMethod,
                                   displayChars, isEop, stopRequested);
            }
        };

        LlmStreamBuffer stream_buffer(processUtf8Chunk);
        std::ostream output_ostream(&stream_buffer);

        // prefill 阶段
        llm->response(messages, &output_ostream, "<eop>", 0);

        auto prefill_end = std::chrono::high_resolution_clock::now();
        metrics.prefill_time = std::chrono::duration_cast<std::chrono::microseconds>(
                prefill_end - prefill_start).count();

        auto* ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.prompt_len = ctx->prompt_len;
        }

        // decode 阶段
        auto decode_start = std::chrono::high_resolution_clock::now();
        int current_size = 0;

        while (!stopRequested && !generateTextEnd && current_size < maxNewTokens) {
            llm->generate(1);
            current_size++;

            ctx = llm->getContext();
            if (ctx != nullptr) {
                if (ctx->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED ||
                    ctx->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED) {
                    generateTextEnd = true;
                }
            }
        }

        // 处理缓冲区中剩余的未完整 UTF-8 字符
        if (!utf8Buffer.empty() && !generateTextEnd) {
            response_buffer << utf8Buffer;
            streamTokensToJava(env, progressListener, onTokenMethod,
                               utf8Buffer, false, stopRequested);
        }

        auto decode_end = std::chrono::high_resolution_clock::now();
        metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
                decode_end - decode_start).count();

        ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.decode_len = ctx->gen_seq_len;
            metrics.vision_time = ctx->vision_us;
            metrics.audio_time = ctx->audio_us;
        }
    }

    std::string result = response_buffer.str();
    LOGD("History stream generation complete. len=%zu, prompt=%ld, decode=%ld",
         result.length(), metrics.prompt_len, metrics.decode_len);

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    env->DeleteLocalRef(listenerClass);

    return hashMap;
}

JNIEXPORT jboolean JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeIsLoaded(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    return (llm != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
