#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdlib>

#if NCNN_AVAILABLE
#include "ncnn_face_detector.h"
#include <cpu.h>
#endif

#define LOG_TAG "PicMe:NcnnJNI"
#if NCNN_AVAILABLE
#define LOGD(...) do { if (picme::NcnnFaceDetector::isLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while(0)
#else
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// [修复 OpenMP 崩溃] 在 JNI_OnLoad 中提前收敛 OpenMP 运行时配置
// 必须在任何 NCNN/OpenMP 操作之前调用
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    // 关闭线程亲和性与绑定，避免 __kmp_affinity_initialize 断言失败
    setenv("KMP_AFFINITY", "none", 1);
    setenv("OMP_PROC_BIND", "false", 1);
    setenv("OMP_NUM_THREADS", "1", 1);
    setenv("KMP_WARNINGS", "0", 1);

#if NCNN_AVAILABLE
    // 通过 NCNN API 强制单线程 OpenMP，避免在部分机型上触发 KMP 并发初始化崩溃
    ncnn::set_omp_num_threads(1);
    ncnn::set_omp_dynamic(0);
    ncnn::set_kmp_blocktime(0);
#endif

    LOGD("JNI_OnLoad: KMP_AFFINITY=none, OMP_PROC_BIND=false, OMP_NUM_THREADS=1");
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeSetLogEnabled(
        JNIEnv *env,
        jclass clazz,
        jboolean enabled) {
#if NCNN_AVAILABLE
    picme::NcnnFaceDetector::setLogEnabled(enabled);
    LOGD("Native log enabled set to: %d", enabled);
#else
    (void)env;
    (void)clazz;
    (void)enabled;
#endif
}

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

JNIEXPORT jint JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeDetect(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject imageData,      // DirectByteBuffer
        jint width,
        jint height,
        jint channels,
        jfloatArray outResult) {  // 预分配结果缓冲区
#if NCNN_AVAILABLE
    auto *detector = reinterpret_cast<picme::NcnnFaceDetector *>(handle);
    if (!detector) {
        return 0;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(imageData));
    if (!data) {
        LOGE("nativeDetect: GetDirectBufferAddress returned null");
        return 0;
    }

    std::vector<float> result = detector->detect(data, width, height, channels);

    if (result.empty()) {
        return 0;
    }

    jsize maxSize = env->GetArrayLength(outResult);
    jsize copySize = static_cast<jsize>(std::min(result.size(), static_cast<size_t>(maxSize)));
    env->SetFloatArrayRegion(outResult, 0, copySize, result.data());
    return copySize;
#else
    (void)env;
    (void)clazz;
    (void)handle;
    (void)imageData;
    (void)width;
    (void)height;
    (void)channels;
    (void)outResult;
    return 0;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeDetectRetinaFace(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject imageData,      // DirectByteBuffer
        jint width,
        jint height,
        jint channels,
        jfloat confidenceThreshold,
        jfloat nmsThreshold,
        jfloatArray outResult) {  // 预分配结果缓冲区 [15 floats]
#if NCNN_AVAILABLE
    auto *detector = reinterpret_cast<picme::NcnnFaceDetector *>(handle);
    if (!detector) {
        return JNI_FALSE;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(imageData));
    if (!data) {
        LOGE("nativeDetectRetinaFace: GetDirectBufferAddress returned null");
        return JNI_FALSE;
    }

    std::vector<picme::FaceBox> faces = detector->detectRetinaFace(
            data, width, height, channels,
            confidenceThreshold, nmsThreshold);

    if (faces.empty()) {
        return JNI_FALSE;
    }

    // 选择置信度 * 面积最大的人脸
    const picme::FaceBox *selectedFace = &faces[0];
    float maxScore = faces[0].confidence * faces[0].area();
    for (size_t i = 1; i < faces.size(); i++) {
        float score = faces[i].confidence * faces[i].area();
        if (score > maxScore) {
            maxScore = score;
            selectedFace = &faces[i];
        }
    }

    // 写入预分配的 outResult: [x1, y1, x2, y2, score, landmarks(10)]
    jfloat output[15];
    output[0] = selectedFace->x1;
    output[1] = selectedFace->y1;
    output[2] = selectedFace->x2;
    output[3] = selectedFace->y2;
    output[4] = selectedFace->confidence;
    for (int i = 0; i < 10; i++) {
        output[5 + i] = selectedFace->landmarks[i];
    }

    env->SetFloatArrayRegion(outResult, 0, 15, output);
    return JNI_TRUE;
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
    (void)outResult;
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_picme_beauty_internal_facedetect_ncnn_NcnnFaceDetector_nativeDetectRetinaFaceFromNv21(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject nv21Data,        // DirectByteBuffer (compact NV21)
        jint width,
        jint height,
        jint rotationDegrees,    // 旋转角度 (0/90/180/270)
        jfloat confidenceThreshold,
        jfloat nmsThreshold,
        jfloatArray outResult) {
#if NCNN_AVAILABLE
    auto *detector = reinterpret_cast<picme::NcnnFaceDetector *>(handle);
    if (!detector) {
        return JNI_FALSE;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(nv21Data));
    if (!data) {
        LOGE("nativeDetectRetinaFaceFromNv21: GetDirectBufferAddress returned null");
        return JNI_FALSE;
    }

    std::vector<picme::FaceBox> faces = detector->detectRetinaFaceFromNv21(
            data, width, height, rotationDegrees, confidenceThreshold, nmsThreshold);

    if (faces.empty()) {
        return JNI_FALSE;
    }

    // 选择置信度 * 面积最大的人脸
    const picme::FaceBox *selectedFace = &faces[0];
    float maxScore = faces[0].confidence * faces[0].area();
    for (size_t i = 1; i < faces.size(); i++) {
        float score = faces[i].confidence * faces[i].area();
        if (score > maxScore) {
            maxScore = score;
            selectedFace = &faces[i];
        }
    }

    jfloat output[15];
    output[0] = selectedFace->x1;
    output[1] = selectedFace->y1;
    output[2] = selectedFace->x2;
    output[3] = selectedFace->y2;
    output[4] = selectedFace->confidence;
    for (int i = 0; i < 10; i++) {
        output[5 + i] = selectedFace->landmarks[i];
    }

    env->SetFloatArrayRegion(outResult, 0, 15, output);
    return JNI_TRUE;
#else
    (void)env;
    (void)clazz;
    (void)handle;
    (void)nv21Data;
    (void)width;
    (void)height;
    (void)rotationDegrees;
    (void)confidenceThreshold;
    (void)nmsThreshold;
    (void)outResult;
    return JNI_FALSE;
#endif
}

} // extern "C"
