#ifndef PICME_MNN_FACE_DETECTOR_H
#define PICME_MNN_FACE_DETECTOR_H

#include <vector>
#include <string>
#include <memory>

// MNN headers
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>
#include <MNN/ImageProcess.hpp>

namespace picme {

struct FaceBox {
    float x1, y1, x2, y2;
    float confidence;
    float landmarks[10]; // 5 points * 2

    float width() const { return x2 - x1; }
    float height() const { return y2 - y1; }
    float area() const { return width() * height(); }
};

/**
 * MNN 人脸检测器封装
 * 支持 ROI 检测（RetinaFace）和关键点检测（2D106）
 * 兼容骁龙 765G + Adreno 620（Vulkan 1.1）
 */
class MnnFaceDetector {
public:
    MnnFaceDetector();
    ~MnnFaceDetector();

    /**
     * 加载 MNN 模型
     * @param modelPath MNN 模型文件路径
     * @param inputSize 模型输入尺寸（正方形）
     * @param useGpu 是否使用 Vulkan GPU
     * @param inputName 输入层名称
     * @param outputNames 输出层名称列表（RetinaFace 多输出）
     */
    bool load(const std::string &modelPath,
              int inputSize,
              bool useGpu,
              const std::string &inputName = "input",
              const std::vector<std::string> &outputNames = {});

    /**
     * 单输出检测（2D106 关键点）
     */
    std::vector<float> detect(const unsigned char *imageData,
                              int width,
                              int height,
                              int channels);

    /**
     * RetinaFace 多输出检测（score + bbox + landmark）
     */
    std::vector<FaceBox> detectRetinaFace(const unsigned char *imageData,
                                          int width,
                                          int height,
                                          int channels,
                                          float confidenceThreshold = 0.5f,
                                          float nmsThreshold = 0.4f);

    void release();

    bool isLoaded() const { return loaded_; }

    /**
     * 设置日志开关状态（从 Kotlin 层传递）
     */
    static void setLogEnabled(bool enabled) { logEnabled_ = enabled; }
    static bool isLogEnabled() { return logEnabled_; }

private:
    static bool logEnabled_;
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session *session_;
    MNN::Tensor *inputTensor_;
    std::vector<MNN::Tensor *> outputTensors_;

    int inputSize_;
    bool useGpu_;
    bool loaded_;
    bool hasBuiltInNormalization_;  // [关键] 模型是否包含内置归一化节点
    std::string inputName_;
    std::vector<std::string> outputNames_;

    // 预处理配置
    std::unique_ptr<MNN::CV::ImageProcess> pretreat_;

    // RetinaFace 后处理
    void processRetinaFaceOutput(const float *score,
                                 const float *bbox,
                                 const float *landmark,
                                 int featureSize,
                                 int stride,
                                 int scoreChannels,
                                 std::vector<FaceBox> &faces,
                                 float threshold);
    float calculateIoU(const FaceBox &a, const FaceBox &b);
    std::vector<FaceBox> applyNMS(std::vector<FaceBox> &faces, float threshold);

    // 辅助函数
    bool createSession();
};

} // namespace picme

#endif // PICME_MNN_FACE_DETECTOR_H
