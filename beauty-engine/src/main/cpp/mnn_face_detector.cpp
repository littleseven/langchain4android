#include "mnn_face_detector.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>

#define LOG_TAG "PicMe:MnnFaceDetect"
#define LOGD(...) do { if (picme::MnnFaceDetector::isLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGI(...) do { if (picme::MnnFaceDetector::isLogEnabled()) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace picme {

bool MnnFaceDetector::logEnabled_ = true;

MnnFaceDetector::MnnFaceDetector()
    : session_(nullptr), inputTensor_(nullptr), inputSize_(0), useGpu_(false), loaded_(false), hasBuiltInNormalization_(false) {
}

MnnFaceDetector::~MnnFaceDetector() {
    release();
}

bool MnnFaceDetector::load(const std::string &modelPath,
                           int inputSize,
                           bool useGpu,
                           const std::string &inputName,
                           const std::vector<std::string> &outputNames) {
    release();

    inputSize_ = inputSize;
    useGpu_ = useGpu;
    inputName_ = inputName;
    outputNames_ = outputNames;

    // 创建 MNN 解释器
    interpreter_.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!interpreter_) {
        LOGE("Failed to create MNN interpreter from: %s", modelPath.c_str());
        return false;
    }

    LOGI("MNN model loaded: %s", modelPath.c_str());

    // 配置调度器
    MNN::ScheduleConfig config;
    config.numThread = 4;

    if (useGpu_) {
        // [关键] 强制使用 Vulkan GPU，不允许降级到 CPU
        config.type = MNN_FORWARD_VULKAN;
        
        LOGI("Requesting Vulkan GPU backend...");
    } else {
        config.numThread = 4;
        config.type = MNN_FORWARD_CPU;
        LOGI("Using CPU backend with %d threads", config.numThread);
    }

    // 创建会话
    auto sessionCreateStart = std::chrono::high_resolution_clock::now();
    session_ = interpreter_->createSession(config);
    auto sessionCreateElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - sessionCreateStart).count();
    
    if (!session_) {
        LOGE("Failed to create MNN session - GPU not supported or model incompatible!");
        return false;
    }
    
    // [简化验证] 仅记录请求的后端类型
    // 注意：MNN 旧版本 API 不支持 getBackendCode()，只能通过性能判断是否真正使用 GPU
    LOGI("MNN session created in %lldms, requested=%s", 
         sessionCreateElapsed, 
         useGpu_ ? "Vulkan" : "CPU");
    
    if (useGpu_) {
        LOGI("Note: If inference is slow (~seconds), MNN may have fallen back to CPU");
        LOGI("      Check device Vulkan support and model compatibility");
    }

    // 获取输入张量
    inputTensor_ = interpreter_->getSessionInput(session_, inputName_.c_str());
    if (!inputTensor_) {
        // 尝试用 nullptr 获取默认输入
        inputTensor_ = interpreter_->getSessionInput(session_, nullptr);
        if (!inputTensor_) {
            LOGE("Failed to get input tensor");
            return false;
        }
        LOGI("Using default input tensor");
    }

    LOGI("Input tensor shape: [%d, %d, %d, %d]",
         inputTensor_->batch(), inputTensor_->channel(),
         inputTensor_->height(), inputTensor_->width());

    // [关键修复] 如果输入张量有动态维度 (-1)，reshape 为固定尺寸
    // 这修复了 ONNX→MNN 转换时未指定固定输入尺寸的问题
    if (inputTensor_->height() <= 0 || inputTensor_->width() <= 0) {
        LOGI("Dynamic input detected, reshaping to fixed size: %d x %d", inputSize_, inputSize_);
        interpreter_->resizeTensor(inputTensor_, {1, 3, inputSize_, inputSize_});
        interpreter_->resizeSession(session_);
        LOGI("Reshaped input tensor: [%d, %d, %d, %d]",
             inputTensor_->batch(), inputTensor_->channel(),
             inputTensor_->height(), inputTensor_->width());
    }

    // 获取输出张量
    if (!outputNames_.empty()) {
        for (const auto &name : outputNames_) {
            MNN::Tensor *tensor = interpreter_->getSessionOutput(session_, name.c_str());
            if (tensor) {
                outputTensors_.push_back(tensor);
                LOGI("Output tensor '%s': [%d, %d, %d, %d]",
                     name.c_str(), tensor->batch(), tensor->channel(),
                     tensor->height(), tensor->width());
            } else {
                LOGE("Failed to get output tensor: %s", name.c_str());
            }
        }
    } else {
        // 单输出模式：获取默认输出
        MNN::Tensor *tensor = interpreter_->getSessionOutput(session_, nullptr);
        if (tensor) {
            outputTensors_.push_back(tensor);
            LOGI("Default output tensor: [%d, %d, %d, %d]",
                 tensor->batch(), tensor->channel(),
                 tensor->height(), tensor->width());
        }
    }

    // 配置图像预处理（用于 BGR/RGB 归一化）
    MNN::CV::ImageProcess::Config pretreatConfig;
    pretreatConfig.sourceFormat = MNN::CV::ImageFormat::RGB;
    pretreatConfig.destFormat = MNN::CV::ImageFormat::RGB;
    // RetinaFace 和 2D106 都使用 mean=127.5, std=128.0 (即 /128.0 - 1.0)
    pretreatConfig.mean[0] = 127.5f;
    pretreatConfig.mean[1] = 127.5f;
    pretreatConfig.mean[2] = 127.5f;
    pretreatConfig.normal[0] = 0.0078125f; // 1/128
    pretreatConfig.normal[1] = 0.0078125f;
    pretreatConfig.normal[2] = 0.0078125f;

    pretreat_.reset(MNN::CV::ImageProcess::create(pretreatConfig));

    // [关键修复] 检测模型是否包含内置归一化节点
    // 2d106det.mnn 包含 _minusscalar0 和 _mulscalar0，有内置归一化
    // det_10g.mnn 没有这些节点，需要外部归一化
    std::ifstream modelCheck(modelPath.c_str(), std::ios::binary);
    if (modelCheck.is_open()) {
        std::string modelContent((std::istreambuf_iterator<char>(modelCheck)),
                                  std::istreambuf_iterator<char>());
        hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                                   (modelContent.find("_mulscalar0") != std::string::npos);
        modelCheck.close();
        LOGI("Model normalization check: hasBuiltIn=%s", hasBuiltInNormalization_ ? "true" : "false");
    }

    loaded_ = true;
    LOGI("MNN detector ready: inputSize=%d, useGpu=%s, outputs=%zu, builtInNorm=%s",
         inputSize_, useGpu_ ? "true" : "false", outputTensors_.size(),
         hasBuiltInNormalization_ ? "true" : "false");
    return true;
}

std::vector<float> MnnFaceDetector::detect(const unsigned char *imageData,
                                           int width,
                                           int height,
                                           int channels) {
    if (!loaded_ || !inputTensor_ || outputTensors_.empty()) {
        return {};
    }

    // [关键修复] 使用与输入张量相同的维度类型，避免 copyFromHostTensor 时数据重排
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    int totalPixels = inputSize_ * inputSize_;

    // [关键修复] 根据模型是否有内置归一化选择归一化方式
    // 2d106det.mnn 有内置归一化 (_minusscalar0, _mulscalar0) -> 直接传递原始像素值
    //   ONNX 版本也检测到内置归一化 (mean=0, std=1)，直接传递 0-255
    // det_10g.mnn 无内置归一化 -> 做 (x-127.5)/128.0
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;

    bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

    if (width == inputSize_ && height == inputSize_) {
        // [关键修复] 根据维度类型选择正确的数据布局
        // CAFFE (NCHW): inputData[c * totalPixels + i]
        // TENSORFLOW (NHWC): inputData[i * 3 + c]
        for (int i = 0; i < totalPixels; i++) {
            for (int c = 0; c < 3; c++) {
                float val = imageData[i * 3 + c];
                if (isNCHW) {
                    inputData[c * totalPixels + i] = (val - normMean) / normStd;
                } else {
                    inputData[i * 3 + c] = (val - normMean) / normStd;
                }
            }
        }
    } else {
        // [关键修复] 使用与 ONNX 完全相同的 letterbox 预处理
        // 1. 计算保持宽高比的缩放比例
        float scale = std::min((float)inputSize_ / width, (float)inputSize_ / height);
        int scaledW = static_cast<int>(width * scale);
        int scaledH = static_cast<int>(height * scale);
        int padLeft = (inputSize_ - scaledW) / 2;
        int padTop = (inputSize_ - scaledH) / 2;

        // 2. 填充黑色背景
        float blackValue = (0.0f - normMean) / normStd;
        for (int i = 0; i < totalPixels * 3; i++) {
            inputData[i] = blackValue;
        }

        // 3. 手动进行 letterbox resize + normalize
        bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);
        for (int y = 0; y < scaledH; y++) {
            for (int x = 0; x < scaledW; x++) {
                float srcX = (x + 0.5f) * width / scaledW - 0.5f;
                float srcY = (y + 0.5f) * height / scaledH - 0.5f;

                int srcX0 = static_cast<int>(srcX);
                int srcY0 = static_cast<int>(srcY);
                int srcX1 = std::min(srcX0 + 1, width - 1);
                int srcY1 = std::min(srcY0 + 1, height - 1);

                float fx = srcX - srcX0;
                float fy = srcY - srcY0;

                int dstX = padLeft + x;
                int dstY = padTop + y;
                int dstIdx = dstY * inputSize_ + dstX;

                for (int c = 0; c < 3; c++) {
                    int srcIdx00 = (srcY0 * width + srcX0) * 3 + c;
                    int srcIdx01 = (srcY0 * width + srcX1) * 3 + c;
                    int srcIdx10 = (srcY1 * width + srcX0) * 3 + c;
                    int srcIdx11 = (srcY1 * width + srcX1) * 3 + c;

                    float val00 = imageData[srcIdx00];
                    float val01 = imageData[srcIdx01];
                    float val10 = imageData[srcIdx10];
                    float val11 = imageData[srcIdx11];

                    float val = val00 * (1-fx) * (1-fy) +
                               val01 * fx * (1-fy) +
                               val10 * (1-fx) * fy +
                               val11 * fx * fy;

                    if (isNCHW) {
                        inputData[c * totalPixels + dstIdx] = (val - normMean) / normStd;
                    } else {
                        inputData[dstIdx * 3 + c] = (val - normMean) / normStd;
                    }
                }
            }
        }
    }

    // 拷贝到会话输入
    inputTensor_->copyFromHostTensor(&tmpInput);

    // 运行推理
    interpreter_->runSession(session_);

    // 获取输出
    MNN::Tensor *output = outputTensors_[0];
    // [关键修复] 使用与输出张量相同的维度类型
    MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
    MNN::Tensor tmpOutput(output, outputDimType);
    output->copyToHostTensor(&tmpOutput);

    // 转为 vector
    int elementSize = tmpOutput.elementSize();
    std::vector<float> result;
    result.reserve(elementSize);
    const float *data = tmpOutput.host<float>();

    for (int i = 0; i < elementSize; i++) {
        result.push_back(data[i]);
    }

    return result;
}

std::vector<FaceBox> MnnFaceDetector::detectRetinaFace(const unsigned char *imageData,
                                                       int width,
                                                       int height,
                                                       int channels,
                                                       float confidenceThreshold,
                                                       float nmsThreshold) {
    if (!loaded_ || !inputTensor_) {
        LOGE("Detector not ready");
        return {};
    }

    auto totalStart = std::chrono::high_resolution_clock::now();

    // [关键修复] 如果输入已经是 inputSize_ x inputSize_，直接复制并归一化
    // 因为 Kotlin 层已经做了 letterbox 预处理
    // [关键修复] 使用与输入张量相同的维度类型
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    int totalPixels = inputSize_ * inputSize_;
    bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

    // [关键修复] 根据模型是否有内置归一化选择归一化方式
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;

    if (width == inputSize_ && height == inputSize_) {
        // 直接复制并归一化，无需 letterbox
        for (int i = 0; i < totalPixels; i++) {
            for (int c = 0; c < 3; c++) {
                float val = imageData[i * 3 + c];
                if (isNCHW) {
                    inputData[c * totalPixels + i] = (val - normMean) / normStd;
                } else {
                    inputData[i * 3 + c] = (val - normMean) / normStd;
                }
            }
        }
    } else {
        // [关键修复] 使用与 ONNX 完全相同的 letterbox 预处理
        // 1. 计算保持宽高比的缩放比例
        float scale = std::min((float)inputSize_ / width, (float)inputSize_ / height);
        int scaledW = static_cast<int>(width * scale);
        int scaledH = static_cast<int>(height * scale);
        int padLeft = (inputSize_ - scaledW) / 2;
        int padTop = (inputSize_ - scaledH) / 2;

        // 2. 填充黑色背景
        float blackValue = (0.0f - normMean) / normStd;
        for (int i = 0; i < totalPixels * 3; i++) {
            inputData[i] = blackValue;
        }

        // 3. 手动进行 letterbox resize + normalize
        for (int y = 0; y < scaledH; y++) {
            for (int x = 0; x < scaledW; x++) {
                float srcX = (x + 0.5f) * width / scaledW - 0.5f;
                float srcY = (y + 0.5f) * height / scaledH - 0.5f;

                int srcX0 = static_cast<int>(srcX);
                int srcY0 = static_cast<int>(srcY);
                int srcX1 = std::min(srcX0 + 1, width - 1);
                int srcY1 = std::min(srcY0 + 1, height - 1);

                float fx = srcX - srcX0;
                float fy = srcY - srcY0;

                int dstX = padLeft + x;
                int dstY = padTop + y;
                int dstIdx = dstY * inputSize_ + dstX;

                for (int c = 0; c < 3; c++) {
                    int srcIdx00 = (srcY0 * width + srcX0) * 3 + c;
                    int srcIdx01 = (srcY0 * width + srcX1) * 3 + c;
                    int srcIdx10 = (srcY1 * width + srcX0) * 3 + c;
                    int srcIdx11 = (srcY1 * width + srcX1) * 3 + c;

                    float val00 = imageData[srcIdx00];
                    float val01 = imageData[srcIdx01];
                    float val10 = imageData[srcIdx10];
                    float val11 = imageData[srcIdx11];

                    float val = val00 * (1-fx) * (1-fy) +
                               val01 * fx * (1-fy) +
                               val10 * (1-fx) * fy +
                               val11 * fx * fy;

                    if (isNCHW) {
                        inputData[c * totalPixels + dstIdx] = (val - normMean) / normStd;
                    } else {
                        inputData[dstIdx * 3 + c] = (val - normMean) / normStd;
                    }
                }
            }
        }
    }

    // 拷贝到会话输入
    auto tCopyStart = std::chrono::high_resolution_clock::now();
    inputTensor_->copyFromHostTensor(&tmpInput);
    auto tCopyEnd = std::chrono::high_resolution_clock::now();
    auto copyMs = std::chrono::duration_cast<std::chrono::milliseconds>(tCopyEnd - tCopyStart).count();

    // 推理
    auto tInferStart = std::chrono::high_resolution_clock::now();
    interpreter_->runSession(session_);
    auto tInferEnd = std::chrono::high_resolution_clock::now();
    auto inferMs = std::chrono::duration_cast<std::chrono::milliseconds>(tInferEnd - tInferStart).count();

    // RetinaFace 输出：9 个张量
    // [关键修复] MNN 转换后的输出顺序是按类型分组，不是按尺度分组：
    // score  outputs: 448 (stride=8, 3200), 471 (stride=16, 800), 494 (stride=32, 200)
    // bbox   outputs: 451 (stride=8, 3200*4), 474 (stride=16, 800*4), 497 (stride=32, 200*4)
    // landmark outputs: 454 (stride=8, 3200*10), 477 (stride=16, 800*10), 500 (stride=32, 200*10)

    std::vector<FaceBox> allFaces;

    // 尺度 1: stride=8, featureSize=40 (320/8)
    {
        MNN::Tensor *scoreOut = interpreter_->getSessionOutput(session_, "448");
        MNN::Tensor *bboxOut = interpreter_->getSessionOutput(session_, "451");
        MNN::Tensor *landmarkOut = interpreter_->getSessionOutput(session_, "454");
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 1 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            int scoreChannels = scoreOut->channel();
            if (featH > 1 && featW > 1) {
                featureSize = featH;
                scoreChannels = scoreOut->channel();
            } else {
                int totalElements = scoreOut->elementSize();
                if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                    scoreChannels = scoreOut->channel();
                    // [关键修复] 扁平化输出: [anchors*2, channels, 1, 1] 或 [anchors, channels, 1, 1]
                    // 对于 RetinaFace，anchors = featureSize * featureSize * numAnchorsPerLocation
                    // 每个位置有 2 个 anchor，所以 batch = featureSize^2 * 2 (如果 channel=1)
                    // 或 batch = featureSize^2 (如果 channel=2，包含 bg+face)
                    if (scoreChannels == 1) {
                        // 只有 face score: batch = featureSize^2 * 2
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f));
                    } else {
                        // 包含 bg+face: batch = featureSize^2
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch()));
                    }
                } else {
                    scoreChannels = 2;
                    featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                }
                if (featureSize <= 0) featureSize = inputSize_ / 8;
            }
            LOGD("Scale 1 (stride=8): featureSize=%d, scoreChannels=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreChannels, scoreOut->batch(), scoreOut->channel(),
                 scoreOut->height(), scoreOut->width());

            MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
            scoreOut->copyToHostTensor(&scoreTensor);
            bboxOut->copyToHostTensor(&bboxTensor);
            landmarkOut->copyToHostTensor(&landmarkTensor);

            const float *scoreData = scoreTensor.host<float>();
            const float *bboxData = bboxTensor.host<float>();
            const float *landmarkData = landmarkTensor.host<float>();
            if (!scoreData || !bboxData || !landmarkData) {
                LOGE("Scale 1 host data is null, skipping");
            } else {
                // [调试] 打印该 scale 的最高 score
                int totalAnchors = featureSize * featureSize * 2;
                float maxScore = 0.0f;
                int maxScoreIdx = -1;
                for (int i = 0; i < totalAnchors; i++) {
                    float s = scoreChannels == 1 ? scoreData[i] : scoreData[totalAnchors + i];
                    if (s > maxScore) {
                        maxScore = s;
                        maxScoreIdx = i;
                    }
                }
                if (maxScoreIdx >= 0) {
                    float dx = bboxData[maxScoreIdx * 4 + 0];
                    float dy = bboxData[maxScoreIdx * 4 + 1];
                    float dw = bboxData[maxScoreIdx * 4 + 2];
                    float dh = bboxData[maxScoreIdx * 4 + 3];
                    int y = maxScoreIdx / (featureSize * 2);
                    int x = (maxScoreIdx % (featureSize * 2)) / 2;
                    float cx = (x + 0.5f) * 8;
                    float cy = (y + 0.5f) * 8;
                    LOGD("  Scale 1 max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                         maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
                }
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 8, scoreChannels, allFaces, confidenceThreshold);
            }
        }
    }

    // 尺度 2: stride=16, featureSize=20 (320/16)
    {
        MNN::Tensor *scoreOut = interpreter_->getSessionOutput(session_, "471");
        MNN::Tensor *bboxOut = interpreter_->getSessionOutput(session_, "474");
        MNN::Tensor *landmarkOut = interpreter_->getSessionOutput(session_, "477");
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 2 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            int scoreChannels = scoreOut->channel();
            if (featH > 1 && featW > 1) {
                featureSize = featH;
                scoreChannels = scoreOut->channel();
            } else {
                int totalElements = scoreOut->elementSize();
                if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                    scoreChannels = scoreOut->channel();
                    if (scoreChannels == 1) {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f));
                    } else {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch()));
                    }
                } else {
                    scoreChannels = 2;
                    featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                }
                if (featureSize <= 0) featureSize = inputSize_ / 16;
            }
            LOGD("Scale 2 (stride=16): featureSize=%d, scoreChannels=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreChannels, scoreOut->batch(), scoreOut->channel(),
                 scoreOut->height(), scoreOut->width());

            MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
            scoreOut->copyToHostTensor(&scoreTensor);
            bboxOut->copyToHostTensor(&bboxTensor);
            landmarkOut->copyToHostTensor(&landmarkTensor);

            const float *scoreData = scoreTensor.host<float>();
            const float *bboxData = bboxTensor.host<float>();
            const float *landmarkData = landmarkTensor.host<float>();
            if (!scoreData || !bboxData || !landmarkData) {
                LOGE("Scale 2 host data is null, skipping");
            } else {
                // [调试] 打印该 scale 的最高 score
                int totalAnchors = featureSize * featureSize * 2;
                float maxScore = 0.0f;
                int maxScoreIdx = -1;
                for (int i = 0; i < totalAnchors; i++) {
                    float s = scoreChannels == 1 ? scoreData[i] : scoreData[totalAnchors + i];
                    if (s > maxScore) {
                        maxScore = s;
                        maxScoreIdx = i;
                    }
                }
                if (maxScoreIdx >= 0) {
                    float dx = bboxData[maxScoreIdx * 4 + 0];
                    float dy = bboxData[maxScoreIdx * 4 + 1];
                    float dw = bboxData[maxScoreIdx * 4 + 2];
                    float dh = bboxData[maxScoreIdx * 4 + 3];
                    int y = maxScoreIdx / (featureSize * 2);
                    int x = (maxScoreIdx % (featureSize * 2)) / 2;
                    float cx = (x + 0.5f) * 16;
                    float cy = (y + 0.5f) * 16;
                    LOGD("  Scale 2 max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                         maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
                }
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 16, scoreChannels, allFaces, confidenceThreshold);
            }
        }
    }

    // 尺度 3: stride=32, featureSize=10 (320/32)
    {
        MNN::Tensor *scoreOut = interpreter_->getSessionOutput(session_, "494");
        MNN::Tensor *bboxOut = interpreter_->getSessionOutput(session_, "497");
        MNN::Tensor *landmarkOut = interpreter_->getSessionOutput(session_, "500");
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 3 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            int scoreChannels = scoreOut->channel();
            if (featH > 1 && featW > 1) {
                featureSize = featH;
                scoreChannels = scoreOut->channel();
            } else {
                int totalElements = scoreOut->elementSize();
                if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                    scoreChannels = scoreOut->channel();
                    if (scoreChannels == 1) {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f));
                    } else {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch()));
                    }
                } else {
                    scoreChannels = 2;
                    featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                }
                if (featureSize <= 0) featureSize = inputSize_ / 32;
            }
            LOGD("Scale 3 (stride=32): featureSize=%d, scoreChannels=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreChannels, scoreOut->batch(), scoreOut->channel(),
                 scoreOut->height(), scoreOut->width());

            MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
            scoreOut->copyToHostTensor(&scoreTensor);
            bboxOut->copyToHostTensor(&bboxTensor);
            landmarkOut->copyToHostTensor(&landmarkTensor);

            const float *scoreData = scoreTensor.host<float>();
            const float *bboxData = bboxTensor.host<float>();
            const float *landmarkData = landmarkTensor.host<float>();
            if (!scoreData || !bboxData || !landmarkData) {
                LOGE("Scale 3 host data is null, skipping");
            } else {
                // [调试] 打印该 scale 的最高 score
                int totalAnchors = featureSize * featureSize * 2;
                float maxScore = 0.0f;
                int maxScoreIdx = -1;
                for (int i = 0; i < totalAnchors; i++) {
                    float s = scoreChannels == 1 ? scoreData[i] : scoreData[totalAnchors + i];
                    if (s > maxScore) {
                        maxScore = s;
                        maxScoreIdx = i;
                    }
                }
                if (maxScoreIdx >= 0) {
                    float dx = bboxData[maxScoreIdx * 4 + 0];
                    float dy = bboxData[maxScoreIdx * 4 + 1];
                    float dw = bboxData[maxScoreIdx * 4 + 2];
                    float dh = bboxData[maxScoreIdx * 4 + 3];
                    int y = maxScoreIdx / (featureSize * 2);
                    int x = (maxScoreIdx % (featureSize * 2)) / 2;
                    float cx = (x + 0.5f) * 32;
                    float cy = (y + 0.5f) * 32;
                    LOGD("  Scale 3 max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                         maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
                }
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 32, scoreChannels, allFaces, confidenceThreshold);
            }
        }
    }

    LOGD("RetinaFace raw detections: %zu", allFaces.size());

    // NMS
    auto tNmsStart = std::chrono::high_resolution_clock::now();
    auto result = applyNMS(allFaces, nmsThreshold);
    auto tNmsEnd = std::chrono::high_resolution_clock::now();
    auto nmsMs = std::chrono::duration_cast<std::chrono::milliseconds>(tNmsEnd - tNmsStart).count();

    auto totalEnd = std::chrono::high_resolution_clock::now();
    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(totalEnd - totalStart).count();

    LOGI("[Perf] MNN RetinaFace DONE: total=%ldms (preprocess+fill=%ldms, copyToDevice=%ldms, infer=%ldms, postprocess=%ldms, nms=%ldms), faces=%zu, backend=%s",
         totalMs, totalMs - copyMs - inferMs - nmsMs, copyMs, inferMs, totalMs - copyMs - inferMs - nmsMs - (totalMs - copyMs - inferMs - nmsMs), nmsMs, result.size(),
         useGpu_ ? "Vulkan" : "CPU");

    LOGD("RetinaFace after NMS: %zu", result.size());
    return result;
}

void MnnFaceDetector::processRetinaFaceOutput(const float *score,
                                              const float *bbox,
                                              const float *landmark,
                                              int featureSize,
                                              int stride,
                                              int scoreChannels,
                                              std::vector<FaceBox> &faces,
                                              float threshold) {
    // MNN 输出格式：NCHW (或扁平化 [anchors, channels, 1, 1])
    // score: [anchors, C, 1, 1] 或 [1, C, H, W] -> C=1 (face only) 或 C=2 (bg+face)
    // bbox: [anchors, 4, 1, 1] 或 [1, 4, H, W] -> dx, dy, dw, dh
    //        [关键修复] MNN 模型从 ONNX 转换而来，bbox 输出格式与 ONNX 一致：
    //        dx = 从 anchor 中心到 bbox 左边界的距离（已除以 stride）
    //        dy = 从 anchor 中心到 bbox 上边界的距离（已除以 stride）
    //        dw = 从 anchor 中心到 bbox 右边界的距离（已除以 stride）
    //        dh = 从 anchor 中心到 bbox 下边界的距离（已除以 stride）
    // landmark: [anchors, 10, 1, 1] 或 [1, 10, H, W] -> 5 points * 2
    //
    // [关键修复] 每个位置有 2 个 anchor（不同 minSize），所以总 anchor 数 = spatialSize * 2
    // 输出数据按 [anchor0, anchor1, anchor0, anchor1, ...] 排列

    int spatialSize = featureSize * featureSize;
    int numAnchorsPerLocation = 2;
    int totalAnchors = spatialSize * numAnchorsPerLocation;

    // [对齐 ONNX] 使用与 InsightFaceDet10GDetector 相同的 minSizes
    float minSizes[2];
    if (stride == 8) {
        minSizes[0] = 16.0f;
        minSizes[1] = 32.0f;
    } else if (stride == 16) {
        minSizes[0] = 64.0f;
        minSizes[1] = 128.0f;
    } else { // stride == 32
        minSizes[0] = 256.0f;
        minSizes[1] = 512.0f;
    }

    for (int y = 0; y < featureSize; y++) {
        for (int x = 0; x < featureSize; x++) {
            int spatialIdx = y * featureSize + x;
            float cx = (x + 0.5f) * stride;
            float cy = (y + 0.5f) * stride;

            for (int a = 0; a < numAnchorsPerLocation; a++) {
                int anchorIdx = spatialIdx * numAnchorsPerLocation + a;

                // [关键修复] 根据实际的 scoreChannels 读取 face score
                float faceScore;
                if (scoreChannels == 1) {
                    // MNN 转换后的模型: 只有 face score
                    faceScore = score[anchorIdx];
                } else {
                    // 原始 ONNX 格式: channel 0=bg, channel 1=face
                    faceScore = score[totalAnchors + anchorIdx];
                }
                
                if (faceScore < threshold) continue;

                // [关键修复] MNN NCHW 布局: [totalAnchors, 4, 1, 1]
                // 线性索引 = anchorIdx * 4 + channel
                // dx = bbox[anchorIdx * 4 + 0], dy = bbox[anchorIdx * 4 + 1]
                // dw = bbox[anchorIdx * 4 + 2], dh = bbox[anchorIdx * 4 + 3]
                float dx = bbox[anchorIdx * 4 + 0];
                float dy = bbox[anchorIdx * 4 + 1];
                float dw = bbox[anchorIdx * 4 + 2];
                float dh = bbox[anchorIdx * 4 + 3];

                // [对齐 ONNX] x1 = cx - dx * stride, x2 = cx + dw * stride
                float x1 = cx - dx * stride;
                float y1 = cy - dy * stride;
                float x2 = cx + dw * stride;
                float y2 = cy + dh * stride;

                // [关键修复] 限制坐标在有效范围内
                float maxSize = static_cast<float>(inputSize_);
                x1 = std::max(0.0f, std::min(x1, maxSize));
                y1 = std::max(0.0f, std::min(y1, maxSize));
                x2 = std::max(0.0f, std::min(x2, maxSize));
                y2 = std::max(0.0f, std::min(y2, maxSize));

                if (x1 >= x2 || y1 >= y2) continue;

                FaceBox box;
                box.x1 = x1;
                box.y1 = y1;
                box.x2 = x2;
                box.y2 = y2;
                box.confidence = faceScore;
                            
                // [关键修复] landmark NCHW 布局: [totalAnchors, 10, 1, 1]
                // 线性索引 = anchorIdx * 10 + channel
                for (int i = 0; i < 5; i++) {
                    float lx = landmark[anchorIdx * 10 + i * 2];
                    float ly = landmark[anchorIdx * 10 + i * 2 + 1];
                    box.landmarks[i * 2] = cx + lx * stride;
                    box.landmarks[i * 2 + 1] = cy + ly * stride;
                }

                faces.push_back(box);
            }
        }
    }
}

float MnnFaceDetector::calculateIoU(const FaceBox &a, const FaceBox &b) {
    float x1 = std::max(a.x1, b.x1);
    float y1 = std::max(a.y1, b.y1);
    float x2 = std::min(a.x2, b.x2);
    float y2 = std::min(a.y2, b.y2);

    float interW = std::max(0.0f, x2 - x1);
    float interH = std::max(0.0f, y2 - y1);
    float interArea = interW * interH;

    float unionArea = a.area() + b.area() - interArea;
    return unionArea > 0 ? interArea / unionArea : 0.0f;
}

std::vector<FaceBox> MnnFaceDetector::applyNMS(std::vector<FaceBox> &faces, float threshold) {
    if (faces.empty()) return {};

    std::sort(faces.begin(), faces.end(), [](const FaceBox &a, const FaceBox &b) {
        return a.confidence > b.confidence;
    });

    std::vector<FaceBox> result;
    std::vector<bool> suppressed(faces.size(), false);

    for (size_t i = 0; i < faces.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(faces[i]);

        for (size_t j = i + 1; j < faces.size(); j++) {
            if (suppressed[j]) continue;
            if (calculateIoU(faces[i], faces[j]) > threshold) {
                suppressed[j] = true;
            }
        }
    }

    return result;
}

void MnnFaceDetector::release() {
    if (interpreter_ && session_) {
        interpreter_->releaseSession(session_);
        session_ = nullptr;
    }
    interpreter_.reset();
    inputTensor_ = nullptr;
    outputTensors_.clear();
    pretreat_.reset();
    loaded_ = false;
    useGpu_ = false;
}

} // namespace picme
