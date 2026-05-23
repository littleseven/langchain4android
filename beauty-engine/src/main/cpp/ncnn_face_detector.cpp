#include "ncnn_face_detector.h"

#if NCNN_AVAILABLE

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#define LOG_TAG "PicMe:NcnnFaceDetect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace picme {

NcnnFaceDetector::NcnnFaceDetector()
    : inputSize_(0), useGpu_(false), loaded_(false) {
}

NcnnFaceDetector::~NcnnFaceDetector() {
    release();
}

bool NcnnFaceDetector::load(const std::string &paramPath,
                            const std::string &binPath,
                            int inputSize,
                            bool useGpu,
                            const std::string &inputName,
                            const std::vector<std::string> &outputNames) {
    release();

    inputSize_ = inputSize;
    useGpu_ = useGpu;
    inputName_ = inputName;
    outputNames_ = outputNames;

    // 配置 NCNN 选项
    net_.opt.num_threads = 4;

    if (useGpu_) {
        net_.opt.use_vulkan_compute = true;
        LOGI("Requesting Vulkan GPU backend...");
    } else {
        net_.opt.use_vulkan_compute = false;
        LOGI("Using CPU backend with %d threads", net_.opt.num_threads);
    }

    // 检查文件是否存在
    FILE *paramFp = fopen(paramPath.c_str(), "rb");
    if (!paramFp) {
        LOGE("NCNN param file not found: %s", paramPath.c_str());
        return false;
    }
    fclose(paramFp);

    FILE *binFp = fopen(binPath.c_str(), "rb");
    if (!binFp) {
        LOGE("NCNN bin file not found: %s", binPath.c_str());
        return false;
    }
    fclose(binFp);

    LOGI("NCNN model files exist: param=%s, bin=%s", paramPath.c_str(), binPath.c_str());

    // 加载模型
    int ret = net_.load_param(paramPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load NCNN param: %s, ret=%d", paramPath.c_str(), ret);
        return false;
    }

    ret = net_.load_model(binPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load NCNN bin: %s, ret=%d", binPath.c_str(), ret);
        return false;
    }

    LOGI("NCNN model loaded: %s + %s", paramPath.c_str(), binPath.c_str());

    loaded_ = true;
    LOGI("NCNN detector ready: inputSize=%d, useGpu=%s, outputs=%zu",
         inputSize_, useGpu_ ? "true" : "false", outputNames_.size());
    return true;
}

ncnn::Mat NcnnFaceDetector::preprocess(const unsigned char *imageData, int width, int height, int channels) {
    // NCNN 使用 NCHW 格式，但通过 from_pixels_resize 可以直接处理 RGB 数据
    // 输入数据已经是 RGB ByteArray，需要归一化到 [-1, 1] (mean=127.5, std=128)

    ncnn::Mat in;

    if (width == inputSize_ && height == inputSize_) {
        // 直接转换，无需 resize
        in = ncnn::Mat::from_pixels(imageData, ncnn::Mat::PIXEL_RGB, width, height);
    } else {
        // 需要 letterbox resize
        float scale = std::min((float)inputSize_ / width, (float)inputSize_ / height);
        int scaledW = static_cast<int>(width * scale);
        int scaledH = static_cast<int>(height * scale);

        // 先缩放到目标尺寸
        ncnn::Mat scaled = ncnn::Mat::from_pixels_resize(imageData, ncnn::Mat::PIXEL_RGB,
                                                          width, height, scaledW, scaledH);

        // 创建固定尺寸的输入 Mat，填充黑色背景
        in = ncnn::Mat(inputSize_, inputSize_, 3);
        in.fill(0.0f);

        // 将缩放后的图像居中粘贴
        int padLeft = (inputSize_ - scaledW) / 2;
        int padTop = (inputSize_ - scaledH) / 2;

        for (int y = 0; y < scaledH; y++) {
            for (int x = 0; x < scaledW; x++) {
                for (int c = 0; c < 3; c++) {
                    in.channel(c).row(padTop + y)[padLeft + x] = scaled.channel(c).row(y)[x];
                }
            }
        }
    }

    // 归一化: (x - 127.5) / 128.0
    const float mean_vals[3] = {127.5f, 127.5f, 127.5f};
    const float norm_vals[3] = {1.0f / 128.0f, 1.0f / 128.0f, 1.0f / 128.0f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    return in;
}

std::vector<float> NcnnFaceDetector::detect(const unsigned char *imageData,
                                            int width,
                                            int height,
                                            int channels) {
    if (!loaded_) {
        return {};
    }

    ncnn::Mat in = preprocess(imageData, width, height, channels);

    // 创建提取器
    ncnn::Extractor ex = net_.create_extractor();
    ex.input(inputName_.c_str(), in);

    // 获取输出
    ncnn::Mat out;
    std::string outputName = outputNames_.empty() ? "output" : outputNames_[0];
    int ret = ex.extract(outputName.c_str(), out);
    if (ret != 0) {
        LOGE("Failed to extract output '%s', ret=%d", outputName.c_str(), ret);
        return {};
    }

    LOGD("NCNN detect output: w=%d, h=%d, c=%d, dims=%d",
         out.w, out.h, out.c, out.dims);

    // 转为 vector
    int totalElements = out.total();
    std::vector<float> result;
    result.reserve(totalElements);

    // NCNN 输出可能是 [w, h, c] 或 [w] 等格式
    // 对于 2D106，输出应该是 212 个 float (106*2)
    if (out.dims == 1) {
        // [w] 一维输出
        for (int i = 0; i < out.w; i++) {
            result.push_back(out[i]);
        }
    } else if (out.dims == 2) {
        // [w, h] 二维输出
        for (int y = 0; y < out.h; y++) {
            for (int x = 0; x < out.w; x++) {
                result.push_back(out.row(y)[x]);
            }
        }
    } else if (out.dims == 3) {
        // [w, h, c] 三维输出
        for (int c = 0; c < out.c; c++) {
            for (int y = 0; y < out.h; y++) {
                for (int x = 0; x < out.w; x++) {
                    result.push_back(out.channel(c).row(y)[x]);
                }
            }
        }
    }

    return result;
}

std::vector<FaceBox> NcnnFaceDetector::detectRetinaFace(const unsigned char *imageData,
                                                        int width,
                                                        int height,
                                                        int channels,
                                                        float confidenceThreshold,
                                                        float nmsThreshold) {
    if (!loaded_) {
        LOGE("Detector not ready");
        return {};
    }

    ncnn::Mat in = preprocess(imageData, width, height, channels);

    // 创建提取器
    ncnn::Extractor ex = net_.create_extractor();
    ex.input(inputName_.c_str(), in);

    // RetinaFace 输出：9 个 blob
    // 假设输出名称与 MNN 一致，按类型分组
    // score outputs: stride=8, 16, 32
    // bbox outputs: stride=8, 16, 32
    // landmark outputs: stride=8, 16, 32

    std::vector<FaceBox> allFaces;

    // 尝试使用常见的 NCNN RetinaFace 输出名
    // 如果 outputNames_ 已提供，使用提供的名称；否则使用默认名
    struct ScaleConfig {
        const char *scoreName;
        const char *bboxName;
        const char *landmarkName;
        int stride;
    };

    ScaleConfig scales[] = {
        {"448", "451", "454", 8},
        {"471", "474", "477", 16},
        {"494", "497", "500", 32}
    };

    for (int s = 0; s < 3; s++) {
        ncnn::Mat scoreOut, bboxOut, landmarkOut;

        int retScore = ex.extract(scales[s].scoreName, scoreOut);
        int retBBox = ex.extract(scales[s].bboxName, bboxOut);
        int retLandmark = ex.extract(scales[s].landmarkName, landmarkOut);

        if (retScore != 0 || retBBox != 0 || retLandmark != 0) {
            // 尝试使用数字索引作为输出名（NCNN 默认输出名）
            char defaultName[32];
            snprintf(defaultName, sizeof(defaultName), "output_%d", s * 3);
            if (retScore != 0) {
                retScore = ex.extract(defaultName, scoreOut);
            }
            if (retBBox != 0) {
                snprintf(defaultName, sizeof(defaultName), "output_%d", s * 3 + 1);
                retBBox = ex.extract(defaultName, bboxOut);
            }
            if (retLandmark != 0) {
                snprintf(defaultName, sizeof(defaultName), "output_%d", s * 3 + 2);
                retLandmark = ex.extract(defaultName, landmarkOut);
            }
        }

        if (retScore != 0 || retBBox != 0 || retLandmark != 0) {
            LOGE("Failed to get scale %d outputs (stride=%d)", s + 1, scales[s].stride);
            continue;
        }

        // 计算 featureSize
        int featureSize = inputSize_ / scales[s].stride;
        int scoreChannels = scoreOut.c > 1 ? scoreOut.c : 2;

        LOGD("Scale %d (stride=%d): featureSize=%d, score=[w=%d,h=%d,c=%d], bbox=[w=%d,h=%d,c=%d], landmark=[w=%d,h=%d,c=%d]",
             s + 1, scales[s].stride, featureSize,
             scoreOut.w, scoreOut.h, scoreOut.c,
             bboxOut.w, bboxOut.h, bboxOut.c,
             landmarkOut.w, landmarkOut.h, landmarkOut.c);

        // 将 ncnn::Mat 数据转为 float 数组
        int spatialSize = featureSize * featureSize;
        int totalAnchors = spatialSize * 2; // 每个位置 2 个 anchor

        std::vector<float> scoreData(totalAnchors * scoreChannels);
        std::vector<float> bboxData(totalAnchors * 4);
        std::vector<float> landmarkData(totalAnchors * 10);

        // 根据 NCNN 输出格式读取数据
        // 假设输出格式为 [w=featureSize, h=featureSize, c=channels]
        if (scoreOut.dims == 3 && scoreOut.w == featureSize && scoreOut.h == featureSize) {
            // [w, h, c] 格式
            for (int y = 0; y < featureSize; y++) {
                for (int x = 0; x < featureSize; x++) {
                    int spatialIdx = y * featureSize + x;
                    for (int a = 0; a < 2; a++) {
                        int anchorIdx = spatialIdx * 2 + a;
                        for (int c = 0; c < scoreChannels; c++) {
                            scoreData[anchorIdx * scoreChannels + c] = scoreOut.channel(c * 2 + a).row(y)[x];
                        }
                        for (int c = 0; c < 4; c++) {
                            bboxData[anchorIdx * 4 + c] = bboxOut.channel(c * 2 + a).row(y)[x];
                        }
                        for (int c = 0; c < 10; c++) {
                            landmarkData[anchorIdx * 10 + c] = landmarkOut.channel(c * 2 + a).row(y)[x];
                        }
                    }
                }
            }
        } else {
            // 扁平化输出，直接复制
            int scoreTotal = scoreOut.total();
            int bboxTotal = bboxOut.total();
            int landmarkTotal = landmarkOut.total();

            if (scoreTotal >= totalAnchors) {
                for (int i = 0; i < scoreTotal && i < (int)scoreData.size(); i++) {
                    scoreData[i] = scoreOut[i];
                }
            }
            if (bboxTotal >= totalAnchors * 4) {
                for (int i = 0; i < bboxTotal && i < (int)bboxData.size(); i++) {
                    bboxData[i] = bboxOut[i];
                }
            }
            if (landmarkTotal >= totalAnchors * 10) {
                for (int i = 0; i < landmarkTotal && i < (int)landmarkData.size(); i++) {
                    landmarkData[i] = landmarkOut[i];
                }
            }
        }

        processRetinaFaceOutput(scoreData.data(), bboxData.data(), landmarkData.data(),
                                featureSize, scales[s].stride, scoreChannels,
                                allFaces, confidenceThreshold);
    }

    LOGD("RetinaFace raw detections: %zu", allFaces.size());

    // NMS
    auto result = applyNMS(allFaces, nmsThreshold);
    LOGD("RetinaFace after NMS: %zu", result.size());
    return result;
}

void NcnnFaceDetector::processRetinaFaceOutput(const float *score,
                                               const float *bbox,
                                               const float *landmark,
                                               int featureSize,
                                               int stride,
                                               int scoreChannels,
                                               std::vector<FaceBox> &faces,
                                               float threshold) {
    int spatialSize = featureSize * featureSize;
    int numAnchorsPerLocation = 2;
    int totalAnchors = spatialSize * numAnchorsPerLocation;

    // [对齐 ONNX/MNN] 使用相同的 minSizes
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

                // 读取 face score
                float faceScore;
                if (scoreChannels == 1) {
                    faceScore = score[anchorIdx];
                } else {
                    faceScore = score[totalAnchors + anchorIdx];
                }

                if (faceScore < threshold) continue;

                // 读取 bbox
                float dx = bbox[anchorIdx * 4 + 0];
                float dy = bbox[anchorIdx * 4 + 1];
                float dw = bbox[anchorIdx * 4 + 2];
                float dh = bbox[anchorIdx * 4 + 3];

                // [对齐 ONNX/MNN]
                float x1 = cx - dx * stride;
                float y1 = cy - dy * stride;
                float x2 = cx + dw * stride;
                float y2 = cy + dh * stride;

                // 限制坐标在有效范围内
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

                // 读取 landmark
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

float NcnnFaceDetector::calculateIoU(const FaceBox &a, const FaceBox &b) {
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

std::vector<FaceBox> NcnnFaceDetector::applyNMS(std::vector<FaceBox> &faces, float threshold) {
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

void NcnnFaceDetector::release() {
    net_.clear();
    loaded_ = false;
    useGpu_ = false;
    inputSize_ = 0;
    outputNames_.clear();
}

} // namespace picme

#else // NCNN_AVAILABLE

namespace picme {

NcnnFaceDetector::NcnnFaceDetector()
    : inputSize_(0), useGpu_(false), loaded_(false) {
}

NcnnFaceDetector::~NcnnFaceDetector() {
}

bool NcnnFaceDetector::load(const std::string &paramPath,
                            const std::string &binPath,
                            int inputSize,
                            bool useGpu,
                            const std::string &inputName,
                            const std::vector<std::string> &outputNames) {
    (void)paramPath;
    (void)binPath;
    (void)inputSize;
    (void)useGpu;
    (void)inputName;
    (void)outputNames;
    return false;
}

std::vector<float> NcnnFaceDetector::detect(const unsigned char *imageData,
                                            int width,
                                            int height,
                                            int channels) {
    (void)imageData;
    (void)width;
    (void)height;
    (void)channels;
    return {};
}

std::vector<FaceBox> NcnnFaceDetector::detectRetinaFace(const unsigned char *imageData,
                                                        int width,
                                                        int height,
                                                        int channels,
                                                        float confidenceThreshold,
                                                        float nmsThreshold) {
    (void)imageData;
    (void)width;
    (void)height;
    (void)channels;
    (void)confidenceThreshold;
    (void)nmsThreshold;
    return {};
}

void NcnnFaceDetector::release() {
}

} // namespace picme

#endif // NCNN_AVAILABLE
