#include <android/bitmap.h>
#include <jni.h>
#include <list>
#include <string>

#include "android/jni/jni_helpers.h"
#include "core/gpupixel_context.h"
#include "gpupixel/source/source_yuv.h"

using namespace gpupixel;

// Create new SourceYUV instance
extern "C" JNIEXPORT jlong JNICALL
Java_com_pixpark_gpupixel_GPUPixelSourceYUV_nativeCreate(JNIEnv* env,
                                                         jclass clazz) {
  auto source_yuv = SourceYUV::Create();
  if (!source_yuv) {
    return 0;
  }

  auto* ptr = new std::shared_ptr<SourceYUV>(source_yuv);
  return reinterpret_cast<jlong>(ptr);
}

// Destroy SourceYUV instance
extern "C" JNIEXPORT void JNICALL
Java_com_pixpark_gpupixel_GPUPixelSourceYUV_nativeDestroy(
    JNIEnv* env,
    jclass clazz,
    jlong native_obj) {
  auto* ptr = reinterpret_cast<std::shared_ptr<SourceYUV>*>(native_obj);
  delete ptr;
}

// Release SourceYUV framebuffer
extern "C" JNIEXPORT void JNICALL
Java_com_pixpark_gpupixel_GPUPixelSourceYUV_nativeFinalize(
    JNIEnv* env,
    jclass clazz,
    jlong native_obj) {
  auto* ptr = reinterpret_cast<std::shared_ptr<SourceYUV>*>(native_obj);
  if (ptr && *ptr) {
    (*ptr)->ReleaseFramebuffer(false);
  }
}

// Process YUV data using DirectByteBuffer
extern "C" JNIEXPORT void JNICALL
Java_com_pixpark_gpupixel_GPUPixelSourceYUV_nativeProcessData(
    JNIEnv* env,
    jclass clazz,
    jlong native_obj,
    jobject y_buffer,
    jobject u_buffer,
    jobject v_buffer,
    jint width,
    jint height,
    jint rotation) {
  auto* ptr = reinterpret_cast<std::shared_ptr<SourceYUV>*>(native_obj);
  if (!ptr || !*ptr) {
    return;
  }

  uint8_t* y_data = (uint8_t*)env->GetDirectBufferAddress(y_buffer);
  uint8_t* u_data = (uint8_t*)env->GetDirectBufferAddress(u_buffer);
  uint8_t* v_data = (uint8_t*)env->GetDirectBufferAddress(v_buffer);

  if (!y_data || !u_data || !v_data) {
    return;
  }

  (*ptr)->ProcessData(y_data, u_data, v_data, width, height, (RotationMode)rotation);
}

// Set rotation mode
extern "C" JNIEXPORT void JNICALL
Java_com_pixpark_gpupixel_GPUPixelSourceYUV_nativeSetRotation(
    JNIEnv* env,
    jclass clazz,
    jlong native_obj,
    jint rotation) {
  auto* ptr = reinterpret_cast<std::shared_ptr<SourceYUV>*>(native_obj);
  if (ptr && *ptr) {
    (*ptr)->SetRotation((RotationMode)rotation);
  }
}
