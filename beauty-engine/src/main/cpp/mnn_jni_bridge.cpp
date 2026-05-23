#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "mnn_face_detector.h"

#define LOG_TAG "PicMe:MnnJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeCreate(
        JNIEnv *env,
        jclass clazz,
        jstring modelPath,
        jint inputSize,
        jboolean useGpu,
        jstring inputName,
        jobjectArray outputNames) {

    const char *modelCStr = env->GetStringUTFChars(modelPath, nullptr);
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

    auto *detector = new picme::MnnFaceDetector();
    bool success = detector->load(modelCStr, inputSize, useGpu, inputCStr, outputNameList);

    env->ReleaseStringUTFChars(modelPath, modelCStr);
    env->ReleaseStringUTFChars(inputName, inputCStr);

    if (!success) {
        delete detector;
        LOGE("Failed to load MNN model");
        return 0;
    }

    LOGD("MnnFaceDetector created: inputSize=%d, useGpu=%d, outputs=%zu",
         inputSize, useGpu, outputNameList.size());
    return reinterpret_cast<jlong>(detector);
}

JNIEXPORT void JNICALL
Java_com_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    delete detector;
    LOGD("MnnFaceDetector destroyed");
}

JNIEXPORT jfloatArray JNICALL
Java_com_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetect(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray imageData,
        jint width,
        jint height,
        jint channels) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
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
}

JNIEXPORT jfloatArray JNICALL
Java_com_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetectRetinaFace(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray imageData,
        jint width,
        jint height,
        jint channels,
        jfloat confidenceThreshold,
        jfloat nmsThreshold) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
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

    // [对齐 ONNX] 选择置信度 * 面积最大的人脸
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
}

} // extern "C"
