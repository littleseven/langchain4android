package com.picme.beauty.api.facedetect

import android.content.Context
import com.picme.beauty.internal.facedetect.FaceDetectorManager

/**
 * 人脸检测器工厂
 */
object FaceDetectorFactory {
    fun create(context: Context): FaceDetector {
        return FaceDetectorManager(context)
    }
}
