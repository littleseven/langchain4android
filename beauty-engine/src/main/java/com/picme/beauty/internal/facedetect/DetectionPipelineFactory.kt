package com.picme.beauty.internal.facedetect

import android.content.Context
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType

/**
 * 检测流水线工厂
 */
internal object DetectionPipelineFactory {
    fun createRoiDetector(
        type: RoiDetectorType,
        context: Context
    ): RoiDetector {
        return when (type) {
            RoiDetectorType.MEDIAPIPE -> MediaPipeRoiDetector(context)
            RoiDetectorType.DET10G -> Det10GRoiDetector(context)
            RoiDetectorType.MNN -> MnnRoiDetector(context)
        }
    }

    fun createLandmarkDetector(
        type: LandmarkDetectorType,
        context: Context
    ): LandmarkDetector {
        return when (type) {
            LandmarkDetectorType.INSIGHTFACE_2D106 ->
                InsightFaceLandmarkDetector(context)
            LandmarkDetectorType.MEDIAPIPE ->
                MediaPipeLandmarkDetector(context)
            LandmarkDetectorType.MNN ->
                MnnLandmarkDetector(context)
        }
    }
}
