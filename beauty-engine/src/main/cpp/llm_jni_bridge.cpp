#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <mutex>

#include <MNN/llm/llm.hpp>

#define LOG_TAG "PicMe:LlmJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

static std::mutex g_llm_mutex;

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

    std::ostringstream oss;
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        llm->response(promptStr, &oss, nullptr, maxNewTokens);
    }

    std::string result = oss.str();
    LOGD("Generated response: %s", result.c_str());

    jstring jResult = env->NewStringUTF(result.c_str());
    if (jResult == nullptr) {
        LOGE("NewStringUTF failed for response, returning empty string");
        return env->NewStringUTF("");
    }
    return jResult;
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
    LOGD("Generated with system response length=%zu, content=%s", result.length(), result.c_str());

    jstring jResult = env->NewStringUTF(result.c_str());
    if (jResult == nullptr) {
        LOGE("NewStringUTF failed for system prompt response, returning empty string");
        return env->NewStringUTF("");
    }
    return jResult;
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
