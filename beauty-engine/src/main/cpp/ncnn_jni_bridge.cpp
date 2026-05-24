#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdlib>

#if NCNN_AVAILABLE
#include "ncnn_face_detector.h"
#endif

#define LOG_TAG "PicMe:NcnnJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// [修复 OpenMP 崩溃] 在 JNI_OnLoad 中提前禁用 OpenMP 线程亲和性
// 必须在任何 OpenMP 操作之前调用
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    // 禁用 OpenMP 线程亲和性，避免 __kmp_affinity_initialize 断言失败
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("OMP_PROC_BIND", "false", 1);
    LOGD("JNI_OnLoad: KMP_AFFINITY=disabled, OMP_PROC_BIND=false");
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeCreate(
        JNIEnv *env,
        jclass clazz,
        jstring paramPath,
        jstring binPath,
        jint inputSize,
        jboolean useGpu,
        jstring inputName,
        jobjectArray outputNames) {

#if !NCNN_AVAILABLE
    (void)env;
    (void)clazz;
    (void)paramPath;
    (void)binPath;
    (void)inputSize;
    (void)useGpu;
    (void)inputName;
    (void)outputNames;
    LOGE("NCNN support is not compiled in. Please add NCNN headers and libraries.");
    return 0;
#else
    const char *paramCStr = env->GetStringUTFChars(paramPath, nullptr);
    const char *binCStr = env->GetStringUTFChars(binPath, nullptr);
    const char *inputCStr = env->GetStringUTFChars(inputName, nullptr);

    // 解析输出名称数组
    std::vector<std::string> outputNameList;
    if (outputNames != nullptr) {
        jsize len = env->GetArrayLength(outputNames);
        for (jsize i = 0; i < len; i++) {
            jstring str = (jstring) env->GetObjectArrayElement(outputNames, i);
            const char *cstr = env->GetStringUTFChars(str, nullptr);
            outputNameList.push_back(std::string(cstr));
            env->ReleaseStringUTFChars(str, cstr);
            env->DeleteLocalRef(str);
        }
    }

    auto *detector = new picme::NcnnFaceDetector();
    bool success = detector->load(paramCStr, binCStr, inputSize, useGpu, inputCStr, outputNameList);

    env->ReleaseStringUTFChars(paramPath, paramCStr);
    env->ReleaseStringUTFChars(binPath, binCStr);
    env->ReleaseStringUTFChars(inputName, inputCStr);

    if (!success) {
        delete detector;
        LOGE("Failed to load NCNN model");
        return 0;
    }

    LOGD("NcnnFaceDetector created: inputSize=%d, useGpu=%d, outputs=%zu",
         inputSize, useGpu, outputNameList.size());
    return reinterpret_cast<jlong>(detector);
#endif
}

JNIEXPORT void JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
#if NCNN_AVAILABLE
    auto *detector = reinterpret_cast<picme::NcnnFaceDetector *>(handle);
    delete detector;
    LOGD("NcnnFaceDetector destroyed");
#else
    (void)env;
    (void)clazz;
    (void)handle;
#endif
}

JNIEXPORT jfloatArray JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeDetect(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray imageData,
        jint width,
        jint height,
        jint channels) {
#if NCNN_AVAILABLE
    auto *detector = reinterpret_cast<picme::NcnnFaceDetector *>(handle);
    if (!detector) {
        return nullptr;
    }

    jbyte *data = env->GetByteArrayElements(imageData, nullptr);
    std::vector<float> result = detector->detect(
            reinterpret_cast<const unsigned char *>(data),
            width, height, channels);
    env->ReleaseByteArrayElements(imageData, data, JNI_ABORT);

    if (result.empty()) {
        return nullptr;
    }

    jfloatArray output = env->NewFloatArray(result.size());
    env->SetFloatArrayRegion(output, 0, result.size(), result.data());
    return output;
#else
    (void)env;
    (void)clazz;
    (void)handle;
    (void)imageData;
    (void)width;
    (void)height;
    (void)channels;
    return nullptr;
#endif
}

JNIEXPORT jfloatArray JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeDetectRetinaFace(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray imageData,
        jint width,
        jint height,
        jint channels,
        jfloat confidenceThreshold,
        jfloat nmsThreshold) {
#if NCNN_AVAILABLE
    auto *detector = reinterpret_cast<picme::NcnnFaceDetector *>(handle);
    if (!detector) {
        return nullptr;
    }

    jbyte *data = env->GetByteArrayElements(imageData, nullptr);
    std::vector<picme::FaceBox> faces = detector->detectRetinaFace(
            reinterpret_cast<const unsigned char *>(data),
            width, height, channels,
            confidenceThreshold, nmsThreshold);
    env->ReleaseByteArrayElements(imageData, data, JNI_ABORT);

    if (faces.empty()) {
        return nullptr;
    }

    // [对齐 ONNX/MNN] 选择置信度 * 面积最大的人脸
    const picme::FaceBox *selectedFace = &faces[0];
    float maxScore = faces[0].confidence * faces[0].area();
    for (size_t i = 1; i < faces.size(); i++) {
        float score = faces[i].confidence * faces[i].area();
        if (score > maxScore) {
            maxScore = score;
            selectedFace = &faces[i];
        }
    }

    // 取置信度最高的人脸，返回 [x1, y1, x2, y2, score, landmarks(10)]
    const int OUTPUT_SIZE = 15; // 4 bbox + 1 score + 10 landmarks
    float output[OUTPUT_SIZE];
    output[0] = selectedFace->x1;
    output[1] = selectedFace->y1;
    output[2] = selectedFace->x2;
    output[3] = selectedFace->y2;
    output[4] = selectedFace->confidence;
    for (int i = 0; i < 10; i++) {
        output[5 + i] = selectedFace->landmarks[i];
    }

    jfloatArray result = env->NewFloatArray(OUTPUT_SIZE);
    env->SetFloatArrayRegion(result, 0, OUTPUT_SIZE, output);
    return result;
#else
    (void)env;
    (void)clazz;
    (void)handle;
    (void)imageData;
    (void)width;
    (void)height;
    (void)channels;
    (void)confidenceThreshold;
    (void)nmsThreshold;
    return nullptr;
#endif
}

} // extern "C"
