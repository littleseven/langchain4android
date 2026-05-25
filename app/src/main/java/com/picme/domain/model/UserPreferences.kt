package com.picme.domain.model

/**
 * 主题模式（领域模型，与 Android 平台无关）
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

/**
 * 应用语言（领域模型）
 */
enum class AppLanguage {
    SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE
}

/**
 * 美颜引擎策略（领域模型）
 *
 * BIG_BEAUTY: R 计划自研 OpenGL ES 管线（默认主引擎）
 */
enum class BeautyStrategy {
    BIG_BEAUTY
}

/**
 * 人脸检测算法模式（领域模型）
 * 表示使用的检测算法/模型系列
 */
enum class FaceDetectionEngineMode {
    MEDIAPIPE,  // MediaPipe 算法系列 (TFLite)
    INSIGHTFACE, // InsightFace 算法系列 (ONNX)
    MNN,        // MNN GPU 加速
    NCNN,       // NCNN 轻量级 GPU
    CUSTOM      // 使用 StageConfig 独立配置
}

/**
 * 人脸检测间隔档位（领域模型）
 */
enum class FaceDetectIntervalProfile {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE
}

/**
 * 检测阶段类型（领域模型）
 */
enum class DetectionStage {
    ROI,        // ROI 检测阶段
    LANDMARK    // 关键点检测阶段
}

/**
 * 检测模型类型（领域模型）
 * 用于 ROI 和 Landmark 阶段的模型选择
 */
enum class DetectionModelType {
    MEDIAPIPE,          // MediaPipe 系列模型
    INSIGHTFACE_DET10G, // InsightFace Det10G (ROI)
    INSIGHTFACE_2D106   // InsightFace 2D106 (Landmark)
}

/**
 * 推理引擎类型（领域模型）
 * 用于控制人脸检测和关键点检测的底层推理框架
 */
enum class InferenceEngineType {
    ONNX,               // ONNX Runtime (CPU/NNAPI)
    MNN,                // MNN (支持 CPU/GPU)
    NCNN,               // NCNN (轻量级)
    TFLITE              // TensorFlow Lite (MediaPipe 默认)
}

/**
 * 推理设备偏好（领域模型）
 * 用于强制指定推理引擎使用 CPU 或 GPU
 */
enum class InferenceDevicePreference {
    AUTO,               // 自动选择（默认）
    FORCE_CPU,          // 强制使用 CPU
    FORCE_GPU           // 强制使用 GPU
}

/**
 * 阶段配置数据类（领域模型）
 * 每个检测阶段（ROI/Landmark）独立配置模型、推理引擎和设备偏好
 */
data class StageConfig(
    val stage: DetectionStage,
    val modelType: DetectionModelType,
    val engineType: InferenceEngineType,
    val devicePreference: InferenceDevicePreference
) {
    companion object {
        fun defaultRoi(): StageConfig = StageConfig(
            stage = DetectionStage.ROI,
            modelType = DetectionModelType.MEDIAPIPE,
            engineType = InferenceEngineType.TFLITE,
            devicePreference = InferenceDevicePreference.AUTO
        )

        fun defaultLandmark(): StageConfig = StageConfig(
            stage = DetectionStage.LANDMARK,
            modelType = DetectionModelType.INSIGHTFACE_2D106,
            engineType = InferenceEngineType.TFLITE,
            devicePreference = InferenceDevicePreference.AUTO
        )
    }
}

/**
 * AI Agent 推理模式（领域模型）
 * 控制使用本地 MNN-LLM 模型还是远程 API
 */
enum class AiAgentMode {
    LOCAL,   // 本地 MNN-LLM 模型（默认，符合隐私红线）
    REMOTE   // 远程 Kimi/Moonshot API
}

/**
 * 模型分类标签（基于 MNN model_market.json 的 tagTranslations）
 *
 * 每个标签对应一个 Tab 分类，标签名来自 API 返回的 tagTranslations key。
 * 使用 value class 包装 String，支持动态标签且保持类型安全。
 *
 * @property tag 标签英文名（如 "Vision", "Think", "Audio"）
 */
@JvmInline
value class ModelCategory(val tag: String) {
    companion object {
        /** 所有模型（无标签过滤） */
        val ALL = ModelCategory("All")

        /** 预置的常见分类，用于本地缓存缺失时的默认展示 */
        val DEFAULT_CATEGORIES = listOf(
            ModelCategory("Think"),
            ModelCategory("Vision"),
            ModelCategory("Audio"),
            ModelCategory("Code"),
            ModelCategory("Math"),
            ModelCategory("ImageGen"),
            ModelCategory("AudioGen"),
            ModelCategory("Chat")
        )
    }

    override fun toString(): String = tag
}

/**
 * 模型分类标签翻译映射
 *
 * 来自 MNN model_market.json 的 tagTranslations 字段。
 * key: 英文标签（如 "Vision"）
 * value: 中文翻译（如 "图像理解"）
 */
typealias TagTranslations = Map<String, String>

