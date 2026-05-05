package com.picme.features.camera.facedetect

import android.content.Context

/**
 * 检测流水线工厂
 */
object DetectionPipelineFactory {
    fun createRoiDetector(
        type: RoiDetectorType,
        context: Context
    ): RoiDetector {
        return when (type) {
            RoiDetectorType.MEDIAPIPE -> MediaPipeRoiDetector(context)
            RoiDetectorType.DET10G -> Det10GRoiDetector(context)
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
        }
    }
}
