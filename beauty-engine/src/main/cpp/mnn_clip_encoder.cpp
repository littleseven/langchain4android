#include "mnn_clip_encoder.h"
#include <android/log.h>
#include <cmath>
#include <cstring>

#define LOG_TAG "PicMe:MobileClip"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace picme {

MobileClipEncoder::MobileClipEncoder()
    : visionSession_(nullptr), visionInputTensor_(nullptr), visionOutputTensor_(nullptr),
      textSession_(nullptr), textInputTensor_(nullptr), textOutputTensor_(nullptr) {
}

MobileClipEncoder::~MobileClipEncoder() {
    release();
}

void MobileClipEncoder::release() {
    // Vision
    if (visionSession_ && visionInterpreter_) {
        visionInterpreter_->releaseSession(visionSession_);
        visionSession_ = nullptr;
    }
    visionInterpreter_.reset();
    visionInputTensor_ = nullptr;
    visionOutputTensor_ = nullptr;
    visionLoaded_ = false;
    visionPretreat_.reset();

    // Text
    if (textSession_ && textInterpreter_) {
        textInterpreter_->releaseSession(textSession_);
        textSession_ = nullptr;
    }
    textInterpreter_.reset();
    textInputTensor_ = nullptr;
    textOutputTensor_ = nullptr;
    textLoaded_ = false;

    LOGD("MobileClipEncoder released");
}

bool MobileClipEncoder::createVisionSession(bool useGpu) {
    MNN::ScheduleConfig scheduleConfig;
    scheduleConfig.type = useGpu ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    scheduleConfig.numThread = useGpu ? 1 : 4;

    MNN::BackendConfig backendConfig;
    backendConfig.precision = MNN::BackendConfig::Precision_Normal;
    backendConfig.power = MNN::BackendConfig::Power_Normal;
    scheduleConfig.backendConfig = &backendConfig;

    visionSession_ = visionInterpreter_->createSession(scheduleConfig);
    if (!visionSession_) {
        LOGE("Failed to create vision session");
        return false;
    }

    visionInputTensor_ = visionInterpreter_->getSessionInput(visionSession_, nullptr);
    visionOutputTensor_ = visionInterpreter_->getSessionOutput(visionSession_, nullptr);

    if (!visionInputTensor_ || !visionOutputTensor_) {
        LOGE("Failed to get vision input/output tensors");
        return false;
    }

    LOGD("Vision session created: inputDims=%d, outputDims=%d",
         visionInputTensor_->dimensions(), visionOutputTensor_->dimensions());
    return true;
}

bool MobileClipEncoder::createTextSession(bool useGpu) {
    MNN::ScheduleConfig scheduleConfig;
    scheduleConfig.type = useGpu ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    scheduleConfig.numThread = useGpu ? 1 : 4;

    MNN::BackendConfig backendConfig;
    backendConfig.precision = MNN::BackendConfig::Precision_Normal;
    backendConfig.power = MNN::BackendConfig::Power_Normal;
    scheduleConfig.backendConfig = &backendConfig;

    textSession_ = textInterpreter_->createSession(scheduleConfig);
    if (!textSession_) {
        LOGE("Failed to create text session");
        return false;
    }

    textInputTensor_ = textInterpreter_->getSessionInput(textSession_, nullptr);
    textOutputTensor_ = textInterpreter_->getSessionOutput(textSession_, nullptr);

    if (!textInputTensor_ || !textOutputTensor_) {
        LOGE("Failed to get text input/output tensors");
        return false;
    }

    LOGD("Text session created: inputDims=%d, outputDims=%d",
         textInputTensor_->dimensions(), textOutputTensor_->dimensions());
    return true;
}

bool MobileClipEncoder::loadVisionModel(const std::string &modelPath, bool useGpu) {
    if (visionLoaded_) {
        release();
    }

    visionInterpreter_.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!visionInterpreter_) {
        LOGE("Failed to create vision interpreter from: %s", modelPath.c_str());
        return false;
    }

    if (!createVisionSession(useGpu)) {
        visionInterpreter_.reset();
        return false;
    }

    // CLIP 标准预处理: mean=[0.48145466, 0.4578275, 0.40821073], std=[0.26862954, 0.26130258, 0.27577711]
    MNN::CV::ImageProcess::Config config;
    config.sourceFormat = MNN::CV::ImageFormat::RGB;
    config.destFormat = MNN::CV::ImageFormat::RGB;
    config.mean[0] = 0.48145466f * 255.0f;
    config.mean[1] = 0.4578275f * 255.0f;
    config.mean[2] = 0.40821073f * 255.0f;
    config.normal[0] = 1.0f / (0.26862954f * 255.0f);
    config.normal[1] = 1.0f / (0.26130258f * 255.0f);
    config.normal[2] = 1.0f / (0.27577711f * 255.0f);

    visionPretreat_.reset(MNN::CV::ImageProcess::create(config));

    visionLoaded_ = true;
    LOGI("Vision model loaded: %s, useGpu=%s", modelPath.c_str(), useGpu ? "true" : "false");
    return true;
}

bool MobileClipEncoder::loadTextModel(const std::string &modelPath, bool useGpu) {
    if (textLoaded_) {
        // 只释放 text 部分
        if (textSession_ && textInterpreter_) {
            textInterpreter_->releaseSession(textSession_);
            textSession_ = nullptr;
        }
        textInterpreter_.reset();
        textInputTensor_ = nullptr;
        textOutputTensor_ = nullptr;
        textLoaded_ = false;
    }

    textInterpreter_.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!textInterpreter_) {
        LOGE("Failed to create text interpreter from: %s", modelPath.c_str());
        return false;
    }

    if (!createTextSession(useGpu)) {
        textInterpreter_.reset();
        return false;
    }

    textLoaded_ = true;
    LOGI("Text model loaded: %s, useGpu=%s", modelPath.c_str(), useGpu ? "true" : "false");
    return true;
}

std::vector<float> MobileClipEncoder::encodeImage(const unsigned char *imageData,
                                                   int width, int height) {
    if (!visionLoaded_ || !visionInputTensor_ || !visionOutputTensor_) {
        LOGE("Vision model not loaded");
        return {};
    }

    // 使用 MNN ImageProcess 进行 resize + normalize
    MNN::CV::Matrix matrix;
    matrix.setScale(1.0f, 1.0f);
    visionPretreat_->setMatrix(matrix);

    // 创建临时输入张量（用于 copyFromHostTensor）
    MNN::Tensor::DimensionType inputDimType = visionInputTensor_->getDimensionType();
    MNN::Tensor tmpInput(visionInputTensor_, inputDimType);

    // 用 ImageProcess 将图像数据转换为模型输入格式
    // 注意：ImageProcess::convert 需要源图像尺寸
    visionPretreat_->convert(imageData, width, height, 0, &tmpInput);

    // 复制到 session 输入张量
    visionInputTensor_->copyFromHostTensor(&tmpInput);

    // 运行推理
    visionInterpreter_->runSession(visionSession_);

    // 获取输出
    MNN::Tensor::DimensionType outputDimType = visionOutputTensor_->getDimensionType();
    MNN::Tensor tmpOutput(visionOutputTensor_, outputDimType);
    visionOutputTensor_->copyToHostTensor(&tmpOutput);

    float *outputData = tmpOutput.host<float>();
    int outputSize = tmpOutput.elementSize();

    if (outputSize < EMBEDDING_DIM) {
        LOGE("Vision output size too small: %d < %d", outputSize, EMBEDDING_DIM);
        return {};
    }

    std::vector<float> embedding(outputData, outputData + EMBEDDING_DIM);
    l2Normalize(embedding);

    LOGD("Image encoded: dim=%d, first5=[%.4f, %.4f, %.4f, %.4f, %.4f]",
         (int)embedding.size(), embedding[0], embedding[1], embedding[2], embedding[3], embedding[4]);

    return embedding;
}

std::vector<float> MobileClipEncoder::encodeText(const int64_t *tokenIds, int tokenCount) {
    if (!textLoaded_ || !textInputTensor_ || !textOutputTensor_) {
        LOGE("Text model not loaded");
        return {};
    }

    if (tokenCount > MAX_TEXT_TOKENS) {
        LOGE("Token count too large: %d > %d", tokenCount, MAX_TEXT_TOKENS);
        return {};
    }

    // MNN 动态维度模型需要先 resize 输入张量
    // text 模型输入 shape: [batch=1, seq_len=77]
    std::vector<int> inputShape = {1, MAX_TEXT_TOKENS};
    textInterpreter_->resizeTensor(textInputTensor_, inputShape);
    textInterpreter_->resizeSession(textSession_);

    // 重新获取 resize 后的输入/输出张量
    textInputTensor_ = textInterpreter_->getSessionInput(textSession_, nullptr);
    textOutputTensor_ = textInterpreter_->getSessionOutput(textSession_, nullptr);

    // 创建临时输入张量
    MNN::Tensor::DimensionType inputDimType = textInputTensor_->getDimensionType();
    MNN::Tensor tmpInput(textInputTensor_, inputDimType);

    // MNN 模型输入类型为 int32，需将 int64 token IDs 转换为 int32
    int32_t *inputData = tmpInput.host<int32_t>();
    int inputSize = tmpInput.elementSize();

    // 先填充 0（padding）
    for (int i = 0; i < inputSize; i++) {
        inputData[i] = 0;
    }

    // 复制实际 token IDs（int64 -> int32）
    int copyCount = std::min(tokenCount, inputSize);
    for (int i = 0; i < copyCount; i++) {
        inputData[i] = static_cast<int32_t>(tokenIds[i]);
    }

    // 复制到 session 输入张量
    textInputTensor_->copyFromHostTensor(&tmpInput);

    // 调试：打印输入张量信息
    LOGD("Text input: dims=%d, elementSize=%d, type=%d",
         textInputTensor_->dimensions(),
         textInputTensor_->elementSize(),
         (int)textInputTensor_->getType().code);
    for (int i = 0; i < textInputTensor_->dimensions(); i++) {
        LOGD("Text input dim[%d]=%d", i, textInputTensor_->length(i));
    }

    // 运行推理
    auto errorCode = textInterpreter_->runSession(textSession_);
    if (errorCode != MNN::NO_ERROR) {
        LOGE("Text runSession failed: %d", errorCode);
    }

    // 获取输出
    MNN::Tensor::DimensionType outputDimType = textOutputTensor_->getDimensionType();
    MNN::Tensor tmpOutput(textOutputTensor_, outputDimType);
    textOutputTensor_->copyToHostTensor(&tmpOutput);

    // 调试：打印输出张量信息
    LOGD("Text output: dims=%d, elementSize=%d, type=%d",
         tmpOutput.dimensions(),
         tmpOutput.elementSize(),
         (int)tmpOutput.getType().code);
    for (int i = 0; i < tmpOutput.dimensions(); i++) {
        LOGD("Text output dim[%d]=%d", i, tmpOutput.length(i));
    }

    float *outputData = tmpOutput.host<float>();
    int outputSize = tmpOutput.elementSize();

    if (outputSize < EMBEDDING_DIM) {
        LOGE("Text output size too small: %d < %d", outputSize, EMBEDDING_DIM);
        return {};
    }

    std::vector<float> embedding(outputData, outputData + EMBEDDING_DIM);
    l2Normalize(embedding);

    LOGD("Text encoded: dim=%d, first5=[%.4f, %.4f, %.4f, %.4f, %.4f]",
         (int)embedding.size(), embedding[0], embedding[1], embedding[2], embedding[3], embedding[4]);

    return embedding;
}

void MobileClipEncoder::l2Normalize(std::vector<float> &vec) {
    float norm = 0.0f;
    for (float v : vec) {
        norm += v * v;
    }
    norm = std::sqrt(norm);
    if (norm > 1e-6f) {
        for (float &v : vec) {
            v /= norm;
        }
    }
}

} // namespace picme
