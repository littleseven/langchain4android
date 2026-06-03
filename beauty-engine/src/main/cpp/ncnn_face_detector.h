#ifndef PICME_NCNN_FACE_DETECTOR_H
#define PICME_NCNN_FACE_DETECTOR_H

#include <vector>
#include <string>

#if NCNN_AVAILABLE
// NCNN headers
#include <net.h>
#endif

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
 * NCNN 人脸检测器封装
 * 支持 ROI 检测（RetinaFace）和关键点检测（2D106）
 */
class NcnnFaceDetector {
public:
    NcnnFaceDetector();
    ~NcnnFaceDetector();

    /**
     * 加载 NCNN 模型
     * @param paramPath NCNN param 文件路径
     * @param binPath NCNN bin 文件路径
     * @param inputSize 模型输入尺寸（正方形）
     * @param useGpu 是否使用 Vulkan GPU
     * @param inputName 输入 blob 名称
     * @param outputNames 输出 blob 名称列表（RetinaFace 多输出）
     */
    bool load(const std::string &paramPath,
              const std::string &binPath,
              int inputSize,
              bool useGpu,
              const std::string &inputName = "data",
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
#if NCNN_AVAILABLE
    ncnn::Net net_;
#endif

    int inputSize_;
    bool useGpu_;
    bool loaded_;
    bool hasBuiltInNormalization_;
    std::string inputName_;
    std::vector<std::string> outputNames_;

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

#if NCNN_AVAILABLE
    // 辅助函数：预处理图像数据到 ncnn::Mat
    ncnn::Mat preprocess(const unsigned char *imageData, int width, int height, int channels);
#endif
};

} // namespace picme

#endif // PICME_NCNN_FACE_DETECTOR_H
