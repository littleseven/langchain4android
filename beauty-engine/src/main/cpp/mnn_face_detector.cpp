#include "mnn_face_detector.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#define LOG_TAG "PicMe:MnnFaceDetect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace picme {

MnnFaceDetector::MnnFaceDetector()
    : session_(nullptr), inputTensor_(nullptr), inputSize_(0), useGpu_(false), loaded_(false) {
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

    loaded_ = true;
    LOGI("MNN detector ready: inputSize=%d, useGpu=%s, outputs=%zu",
         inputSize_, useGpu_ ? "true" : "false", outputTensors_.size());
    return true;
}

std::vector<float> MnnFaceDetector::detect(const unsigned char *imageData,
                                           int width,
                                           int height,
                                           int channels) {
    if (!loaded_ || !inputTensor_ || outputTensors_.empty()) {
        return {};
    }

    // 创建临时输入张量（从用户数据）
    MNN::Tensor tmpInput(inputTensor_, MNN::Tensor::DimensionType::CAFFE);

    // 使用 ImageProcess 进行 resize + normalize
    // 源数据格式：RGB 连续存储
    MNN::CV::Matrix transform;
    transform.setScale((float)width / inputSize_, (float)height / inputSize_);
    pretreat_->setMatrix(transform);

    pretreat_->convert(imageData, width, height, 0, &tmpInput);

    // 拷贝到会话输入
    inputTensor_->copyFromHostTensor(&tmpInput);

    // 运行推理
    interpreter_->runSession(session_);

    // 获取输出
    MNN::Tensor *output = outputTensors_[0];
    MNN::Tensor tmpOutput(output, MNN::Tensor::DimensionType::CAFFE);
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

    // 预处理
    MNN::Tensor tmpInput(inputTensor_, MNN::Tensor::DimensionType::CAFFE);
    MNN::CV::Matrix transform;
    transform.setScale((float)width / inputSize_, (float)height / inputSize_);
    pretreat_->setMatrix(transform);
    pretreat_->convert(imageData, width, height, 0, &tmpInput);
    inputTensor_->copyFromHostTensor(&tmpInput);

    // 推理
    interpreter_->runSession(session_);

    // RetinaFace 输出：9 个张量（3 个尺度 × 3 个分支）
    // 根据 MNNConvert 输出顺序：
    // outputNames = {"448", "471", "494", "451", "474", "497", "454", "477", "500"}
    // 对应：score1, bbox1, landmark1, score2, bbox2, landmark2, score3, bbox3, landmark3

    std::vector<FaceBox> allFaces;

    // 尺度 1: stride=8, featureSize 从张量形状动态获取
    // [兼容处理] MNN 可能输出扁平化形状如 [12800, 1, 1, 1] 而非 [1, 2, 80, 80]
    {
        MNN::Tensor *scoreOut = interpreter_->getSessionOutput(session_, "448");
        MNN::Tensor *bboxOut = interpreter_->getSessionOutput(session_, "471");
        MNN::Tensor *landmarkOut = interpreter_->getSessionOutput(session_, "494");
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 1 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            if (featH > 1 && featW > 1) {
                // 标准 NCHW 形状: [1, C, H, W]
                featureSize = featH;
            } else {
                // 扁平化形状: 从总元素数推导 featureSize
                // score 有 2 个通道 (bg + face)
                int totalElements = scoreOut->elementSize();
                featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                if (featureSize <= 0) featureSize = inputSize_ / 8;
            }
            LOGD("Scale 1 (stride=8): featureSize=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreOut->batch(), scoreOut->channel(),
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
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 8, allFaces, confidenceThreshold);
            }
        }
    }

    // 尺度 2: stride=16
    {
        MNN::Tensor *scoreOut = interpreter_->getSessionOutput(session_, "451");
        MNN::Tensor *bboxOut = interpreter_->getSessionOutput(session_, "474");
        MNN::Tensor *landmarkOut = interpreter_->getSessionOutput(session_, "497");
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 2 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            if (featH > 1 && featW > 1) {
                featureSize = featH;
            } else {
                int totalElements = scoreOut->elementSize();
                featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                if (featureSize <= 0) featureSize = inputSize_ / 16;
            }
            LOGD("Scale 2 (stride=16): featureSize=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreOut->batch(), scoreOut->channel(),
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
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 16, allFaces, confidenceThreshold);
            }
        }
    }

    // 尺度 3: stride=32
    {
        MNN::Tensor *scoreOut = interpreter_->getSessionOutput(session_, "454");
        MNN::Tensor *bboxOut = interpreter_->getSessionOutput(session_, "477");
        MNN::Tensor *landmarkOut = interpreter_->getSessionOutput(session_, "500");
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 3 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            if (featH > 1 && featW > 1) {
                featureSize = featH;
            } else {
                int totalElements = scoreOut->elementSize();
                featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                if (featureSize <= 0) featureSize = inputSize_ / 32;
            }
            LOGD("Scale 3 (stride=32): featureSize=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreOut->batch(), scoreOut->channel(),
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
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 32, allFaces, confidenceThreshold);
            }
        }
    }

    LOGD("RetinaFace raw detections: %zu", allFaces.size());

    // NMS
    auto result = applyNMS(allFaces, nmsThreshold);
    LOGD("RetinaFace after NMS: %zu", result.size());
    return result;
}

void MnnFaceDetector::processRetinaFaceOutput(const float *score,
                                              const float *bbox,
                                              const float *landmark,
                                              int featureSize,
                                              int stride,
                                              std::vector<FaceBox> &faces,
                                              float threshold) {
    // MNN 输出格式：NCHW
    // score: [1, 2, H, W] -> channel 0=bg, channel 1=face
    // bbox: [1, 4, H, W] -> dx, dy, dw, dh
    // landmark: [1, 10, H, W] -> 5 points * 2

    int spatialSize = featureSize * featureSize;

    for (int y = 0; y < featureSize; y++) {
        for (int x = 0; x < featureSize; x++) {
            int idx = y * featureSize + x;

            // face score 在 channel 1
            float faceScore = score[spatialSize + idx];
            if (faceScore < threshold) continue;

            // 解码 bbox (channel first)
            float dx = bbox[idx];
            float dy = bbox[spatialSize + idx];
            float dw = bbox[spatialSize * 2 + idx];
            float dh = bbox[spatialSize * 3 + idx];

            float cx = (x + 0.5f) * stride;
            float cy = (y + 0.5f) * stride;

            float boxW = std::exp(dw) * stride;
            float boxH = std::exp(dh) * stride;

            FaceBox box;
            box.x1 = cx - boxW * 0.5f;
            box.y1 = cy - boxH * 0.5f;
            box.x2 = cx + boxW * 0.5f;
            box.y2 = cy + boxH * 0.5f;
            box.confidence = faceScore;

            // 解码 5 点关键点
            for (int i = 0; i < 5; i++) {
                float lx = landmark[spatialSize * (i * 2) + idx];
                float ly = landmark[spatialSize * (i * 2 + 1) + idx];
                box.landmarks[i * 2] = cx + lx * stride;
                box.landmarks[i * 2 + 1] = cy + ly * stride;
            }

            faces.push_back(box);
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
