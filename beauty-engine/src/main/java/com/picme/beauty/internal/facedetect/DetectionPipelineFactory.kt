package com.picme.beauty.internal.facedetect

import android.content.Context
import com.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.picme.beauty.api.facedetect.DevicePreference
import com.picme.beauty.api.facedetect.InferenceBackendType
import com.picme.beauty.api.facedetect.LandmarkDetectorType
import com.picme.beauty.api.facedetect.RoiDetectorType

/**
 * 检测流水线工厂
 * 根据模型类型 + 推理引擎 + 设备偏好创建对应检测器
 */
internal object DetectionPipelineFactory {

    /**
     * 创建 ROI 检测器
     * 根据模型类型、引擎类型和设备偏好选择对应实现
     */
    fun createRoiDetector(
        type: RoiDetectorType,
        engine: InferenceBackendType,
        device: DevicePreference,
        context: Context
    ): RoiDetector {
        return when (type) {
            RoiDetectorType.MEDIAPIPE -> {
                // MediaPipe 固定使用 TFLite，忽略引擎和设备选择
                MediaPipeRoiDetector(context)
            }
            RoiDetectorType.DET10G -> {
                when (engine) {
                    InferenceBackendType.MNN -> {
                        // [关键策略] FORCE_GPU 或 AUTO 时要求 GPU，失败不降级
                        val requireGpu = device != DevicePreference.FORCE_CPU
                        MnnRoiDetector(context, requireGpu = requireGpu)
                    }
                    else -> Det10GRoiDetector(context) // ONNX/NCNN/TFLITE 默认用 ONNX
                }
            }
        }
    }

    /**
     * 创建关键点检测器
     * 根据模型类型、引擎类型和设备偏好选择对应实现
     */
    fun createLandmarkDetector(
        type: LandmarkDetectorType,
        engine: InferenceBackendType,
        device: DevicePreference,
        context: Context
    ): LandmarkDetector {
        return when (type) {
            LandmarkDetectorType.INSIGHTFACE_2D106 -> {
                when (engine) {
                    InferenceBackendType.MNN -> {
                        // [关键策略] FORCE_GPU 或 AUTO 时要求 GPU，失败不降级
                        val requireGpu = device != DevicePreference.FORCE_CPU
                        MnnLandmarkDetector(context, requireGpu = requireGpu)
                    }
                    else -> InsightFaceLandmarkDetector(context) // ONNX/NCNN/TFLITE 默认用 ONNX
                }
            }
            LandmarkDetectorType.MEDIAPIPE -> {
                // MediaPipe 固定使用 TFLite，忽略引擎和设备选择
                MediaPipeLandmarkDetector(context)
            }
        }
    }

    /**
     * 从完整配置创建检测器对
     */
    fun createPipeline(
        config: DetectionPipelineConfig,
        context: Context
    ): Pair<RoiDetector, LandmarkDetector> {
        val roiDetector = createRoiDetector(
            config.roiDetector,
            config.roiEngine,
            config.roiDevice,
            context
        )
        val landmarkDetector = createLandmarkDetector(
            config.landmarkDetector,
            config.landmarkEngine,
            config.landmarkDevice,
            context
        )
        return Pair(roiDetector, landmarkDetector)
    }
}
