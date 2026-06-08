#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <mutex>
#include <streambuf>
#include <dlfcn.h>

#include <MNN/llm/llm.hpp>

#define LOG_TAG "PicMe:LlmJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

static std::mutex g_llm_mutex;

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
        // 安全地重置状态为 RUNNING，允许继续生成
        auto* mutableContext = const_cast<MNN::Transformer::LlmContext*>(context);
        mutableContext->status = MNN::Transformer::LlmStatus::RUNNING;
        LOGD("LLM status restored to RUNNING (was FINISHED)");
    }
}

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
Java_com_picme_agent_core_platform_llm_local_MnnLlmClient_nativeCreate(
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
Java_com_picme_agent_core_platform_llm_local_MnnLlmClient_nativeDestroy(
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
Java_com_picme_agent_core_platform_llm_local_MnnLlmClient_nativeReset(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm != nullptr) {
        llm->reset();
        LOGD("LLM reset (history cleared)");
    }
}

JNIEXPORT jstring JNICALL
Java_com_picme_agent_core_platform_llm_local_MnnLlmClient_nativeGenerate(
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

    LOGD("Generated response: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_picme_agent_core_platform_llm_local_MnnLlmClient_nativeGenerateWithSystem(
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
        restoreLlmStatusIfNeeded(llm);
        llm->response(messages, &oss, nullptr, maxNewTokens);
    }

    std::string result = oss.str();
    LOGD("Generated response: %s", result.c_str());

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_picme_agent_core_platform_llm_local_MnnLlmClient_nativeIsLoaded(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    // nativeHandle 有效即表示模型已加载
    // reset() 会重置 ctx->status 为 NOT_LOADED，但不销毁模型
    // 因此不能依赖 ctx->status 判断加载状态
    return (llm != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
