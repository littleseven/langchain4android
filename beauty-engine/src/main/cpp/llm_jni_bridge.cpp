#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <mutex>
#include <streambuf>

#include <MNN/llm/llm.hpp>

#define LOG_TAG "PicMe:LlmJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

static std::mutex g_llm_mutex;

/**
 * 自定义 streambuf，用于收集 generate() 输出的 token
 * 参考官方 MnnLlmChat 实现
 */
class LlmStreamBuffer : public std::streambuf {
public:
    LlmStreamBuffer() {
        setp(buffer_, buffer_ + sizeof(buffer_) - 1);
    }

    std::string str() const {
        return std::string(pbase(), pptr() - pbase());
    }

protected:
    virtual int_type overflow(int_type c) override {
        if (c != traits_type::eof()) {
            *pptr() = c;
            pbump(1);
        }
        return c;
    }

private:
    char buffer_[4096];
};

JNIEXPORT jlong JNICALL
Java_com_picme_beauty_api_llm_MnnLlmClient_nativeCreate(
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
Java_com_picme_beauty_api_llm_MnnLlmClient_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm != nullptr) {
        MNN::Transformer::Llm::destroy(llm);
        LOGD("LLM destroyed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_picme_beauty_api_llm_MnnLlmClient_nativeGenerate(
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

    std::string result;
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);

        // 阶段1: prefill —— 处理输入 prompt，不生成输出
        LlmStreamBuffer streamBuffer;
        std::ostream outputStream(&streamBuffer);
        llm->response(promptStr, &outputStream, "<eop>", 0);

        // 阶段2: decode —— 循环生成 token
        int generatedTokens = 0;
        const int maxTokens = maxNewTokens > 0 ? maxNewTokens : 128;
        bool finished = false;

        while (generatedTokens < maxTokens && !finished) {
            llm->generate(1);
            generatedTokens++;

            // 检查状态
            const MNN::Transformer::LlmContext *ctx = llm->getContext();
            if (ctx != nullptr) {
                if (ctx->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED ||
                    ctx->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED) {
                    finished = true;
                }
            }
        }

        result = streamBuffer.str();
    }

    LOGD("Generated response: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_picme_beauty_api_llm_MnnLlmClient_nativeGenerateWithSystem(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        return env->NewStringUTF("");
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

    std::ostringstream oss;
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        llm->response(messages, &oss, nullptr, maxNewTokens);
    }

    std::string result = oss.str();
    LOGD("Generated response: %s", result.c_str());

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_picme_beauty_api_llm_MnnLlmClient_nativeIsLoaded(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        return JNI_FALSE;
    }

    const MNN::Transformer::LlmContext *ctx = llm->getContext();
    if (ctx == nullptr) {
        return JNI_FALSE;
    }

    return (ctx->status == MNN::Transformer::LlmStatus::RUNNING ||
            ctx->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED ||
            ctx->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED)
           ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
