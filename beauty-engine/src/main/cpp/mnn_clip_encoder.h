#ifndef PICME_MNN_CLIP_ENCODER_H
#define PICME_MNN_CLIP_ENCODER_H

#include <vector>
#include <string>
#include <memory>

// MNN headers
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>
#include <MNN/ImageProcess.hpp>

namespace picme {

/**
 * MobileCLIP 编码器（MNN 引擎）
 *
 * 支持 vision_model 和 text_model 两个 MNN 模型，
 * 分别生成图像和文本的 512 维 L2 归一化 embedding。
 *
 * MobileCLIP-S0 模型规格：
 * - Vision 输入: 256x256 RGB, mean=[0.48145466, 0.4578275, 0.40821073], std=[0.26862954, 0.26130258, 0.27577711]
 * - Text 输入: 77 个 token ID (int64)
 * - 输出: 512 维 float embedding（已 L2 归一化）
 */
class MobileClipEncoder {
public:
    MobileClipEncoder();
    ~MobileClipEncoder();

    /**
     * 加载 vision 模型
     * @param modelPath MNN 模型文件路径
     * @param useGpu 是否使用 OpenCL GPU
     * @return 是否成功
     */
    bool loadVisionModel(const std::string &modelPath, bool useGpu = false);

    /**
     * 加载 text 模型
     * @param modelPath MNN 模型文件路径
     * @param useGpu 是否使用 OpenCL GPU
     * @return 是否成功
     */
    bool loadTextModel(const std::string &modelPath, bool useGpu = false);

    /**
     * 编码图像
     * @param imageData RGB 像素数据 (uint8), 长度 = width * height * 3
     * @param width 图像宽度
     * @param height 图像高度
     * @return 512 维 L2 归一化 embedding，失败返回空 vector
     */
    std::vector<float> encodeImage(const unsigned char *imageData, int width, int height);

    /**
     * 编码文本（token IDs）
     * @param tokenIds 输入 token ID 数组（int64），长度通常为 77
     * @param tokenCount token 数量
     * @return 512 维 L2 归一化 embedding，失败返回空 vector
     */
    std::vector<float> encodeText(const int64_t *tokenIds, int tokenCount);

    /**
     * 释放资源
     */
    void release();

    bool isVisionLoaded() const { return visionLoaded_; }
    bool isTextLoaded() const { return textLoaded_; }

private:
    // Vision 模型
    std::shared_ptr<MNN::Interpreter> visionInterpreter_;
    MNN::Session *visionSession_ = nullptr;
    MNN::Tensor *visionInputTensor_ = nullptr;
    MNN::Tensor *visionOutputTensor_ = nullptr;
    bool visionLoaded_ = false;

    // Text 模型
    std::shared_ptr<MNN::Interpreter> textInterpreter_;
    MNN::Session *textSession_ = nullptr;
    MNN::Tensor *textInputTensor_ = nullptr;
    MNN::Tensor *textOutputTensor_ = nullptr;
    bool textLoaded_ = false;

    // Vision 预处理
    std::unique_ptr<MNN::CV::ImageProcess> visionPretreat_;

    static constexpr int VISION_INPUT_SIZE = 256;
    static constexpr int EMBEDDING_DIM = 512;
    static constexpr int MAX_TEXT_TOKENS = 77;

    bool createVisionSession(bool useGpu);
    bool createTextSession(bool useGpu);

    void l2Normalize(std::vector<float> &vec);
};

} // namespace picme

#endif // PICME_MNN_CLIP_ENCODER_H
