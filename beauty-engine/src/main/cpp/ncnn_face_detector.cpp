#include "ncnn_face_detector.h"

#if NCNN_AVAILABLE

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <chrono>

#define LOG_TAG "PicMe:NcnnFaceDetect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace picme {

NcnnFaceDetector::NcnnFaceDetector()
    : inputSize_(0), useGpu_(false), loaded_(false) {
    // [修复 OpenMP 崩溃] 禁用线程亲和性，避免 SIGABRT
    setenv("KMP_AFFINITY", "disabled", 1);
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
    // [修复 OpenMP 崩溃] 禁用线程亲和性，避免 SIGABRT
    // [对齐 MNN] 启用标准优化选项，确保推理精度与 MNN 一致
    net_.opt.num_threads = 4;
    net_.opt.use_packing_layout = true;
    net_.opt.use_sgemm_convolution = true;
    net_.opt.use_winograd_convolution = true;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    net_.opt.use_fp16_arithmetic = true;
    net_.opt.use_int8_inference = true;

    if (useGpu_) {
        net_.opt.use_vulkan_compute = true;
        LOGI("Requesting Vulkan GPU backend...");
        // [诊断] 检查 Vulkan 是否实际可用
        int gpuCount = ncnn::get_gpu_count();
        if (gpuCount == 0) {
            LOGE("No Vulkan GPU available on this device! Falling back to CPU");
            net_.opt.use_vulkan_compute = false;
            useGpu_ = false;
        } else {
            LOGI("Vulkan GPU count: %d, device=%s", gpuCount, ncnn::get_gpu_info(0).device_name());
            // [关键修复] 必须显式设置 Vulkan 设备，否则 create_extractor 不会使用 Vulkan
            net_.set_vulkan_device(0);
            LOGI("Vulkan device set to index 0");
        }
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

    // [诊断] 尝试用 load_param_mem 加载，获取更详细的错误信息
    // [关键修复] 检测模型是否有内置归一化节点（如 _minusscalar0/_mulscalar0）
    {
        FILE* fp = fopen(paramPath.c_str(), "rb");
        if (fp) {
            fseek(fp, 0, SEEK_END);
            long size = ftell(fp);
            fseek(fp, 0, SEEK_SET);
            char* mem = (char*)malloc(size + 1);
            if (mem) {
                fread(mem, 1, size, fp);
                mem[size] = '\0';
                // 打印前 200 字节用于诊断
                char preview[201];
                int preview_len = size < 200 ? (int)size : 200;
                memcpy(preview, mem, preview_len);
                preview[preview_len] = '\0';
                // 将换行替换为 |
                for (int i = 0; i < preview_len; i++) {
                    if (preview[i] == '\n' || preview[i] == '\r') preview[i] = '|';
                }
                LOGI("Param file first 200 bytes: %s", preview);

                // 检测内置归一化节点
                bool hasMinus = strstr(mem, "_minusscalar0") != nullptr ||
                                strstr(mem, "_minus") != nullptr ||
                                strstr(mem, "bn_data") != nullptr;
                bool hasMul = strstr(mem, "_mulscalar0") != nullptr ||
                              strstr(mem, "_mul") != nullptr ||
                              strstr(mem, "bn_data") != nullptr;
                hasBuiltInNormalization_ = hasMinus && hasMul;
                LOGI("Model normalization check: hasMinus=%s, hasMul=%s, builtInNorm=%s",
                     hasMinus ? "true" : "false", hasMul ? "true" : "false",
                     hasBuiltInNormalization_ ? "true" : "false");

                free(mem);
            }
            fclose(fp);
        }
    }

    // 加载模型
    int ret = net_.load_param(paramPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load NCNN param: %s, ret=%d", paramPath.c_str(), ret);
        return false;
    }
    LOGI("NCNN param loaded successfully: %s", paramPath.c_str());

    ret = net_.load_model(binPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load NCNN bin: %s, ret=%d", binPath.c_str(), ret);
        return false;
    }
    LOGI("NCNN bin loaded successfully: %s", binPath.c_str());

    // [诊断] 打印输入输出 blob 名称
    LOGI("NCNN model inputs:");
    for (int i = 0; i < (int)net_.input_indexes().size(); i++) {
        int idx = net_.input_indexes()[i];
        const char* name = net_.blobs()[idx].name.c_str();
        LOGI("  input[%d] = %s", i, name);
    }
    LOGI("NCNN model outputs:");
    for (int i = 0; i < (int)net_.output_indexes().size(); i++) {
        int idx = net_.output_indexes()[i];
        const char* name = net_.blobs()[idx].name.c_str();
        LOGI("  output[%d] = %s", i, name);
    }

    LOGI("NCNN model loaded: %s + %s", paramPath.c_str(), binPath.c_str());

    loaded_ = true;
    LOGI("NCNN detector ready: inputSize=%d, useGpu=%s, vulkan=%s, outputs=%zu",
         inputSize_, useGpu_ ? "true" : "false",
         net_.opt.use_vulkan_compute ? "true" : "false", outputNames_.size());
    return true;
}

ncnn::Mat NcnnFaceDetector::preprocess(const unsigned char *imageData, int width, int height, int channels) {
    // [对齐 MNN] 使用与 MNN 相同的手动数据复制方式，避免 from_pixels 的潜在问题
    // 输入数据是 RGB ByteArray，需要转为 NCHW float 并归一化到 [-1, 1]

    // 创建 NCHW 格式的输入 Mat
    ncnn::Mat in = ncnn::Mat(inputSize_, inputSize_, 3);
    in.fill(0.0f);

    if (width == inputSize_ && height == inputSize_) {
        // 直接复制，无需 resize
        // [对齐 ONNX] 使用 RGB 顺序，与 InsightFaceDet10GDetector 一致
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIdx = (y * width + x) * 3;
                in.channel(0).row(y)[x] = static_cast<float>(imageData[srcIdx + 0]); // R
                in.channel(1).row(y)[x] = static_cast<float>(imageData[srcIdx + 1]); // G
                in.channel(2).row(y)[x] = static_cast<float>(imageData[srcIdx + 2]); // B
            }
        }
    } else {
        // [对齐 ONNX] 使用与 ONNX 相同的 letterbox resize + 双线性插值
        float scale = std::min((float)inputSize_ / width, (float)inputSize_ / height);
        int scaledW = static_cast<int>(width * scale);
        int scaledH = static_cast<int>(height * scale);
        int padLeft = (inputSize_ - scaledW) / 2;
        int padTop = (inputSize_ - scaledH) / 2;

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

                    // [对齐 ONNX] RGB 顺序：c=0→R(channel0), c=1→G(channel1), c=2→B(channel2)
                    in.channel(c).row(dstY)[dstX] = val;
                }
            }
        }
    }

    // 归一化: (x - 127.5) / 128.0
    // [关键修复] 如果模型有内置归一化节点（如 _minusscalar0/_mulscalar0），跳过外部归一化
    if (hasBuiltInNormalization_) {
        LOGD("Skipping external normalization (model has built-in normalization nodes)");
    } else {
        for (int c = 0; c < 3; c++) {
            float mean_val = 127.5f;
            float norm_val = 1.0f / 128.0f;
            float* ptr = in.channel(c);
            int total = in.w * in.h;
            for (int i = 0; i < total; i++) {
                ptr[i] = (ptr[i] - mean_val) * norm_val;
            }
        }
    }

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

    auto totalStart = std::chrono::high_resolution_clock::now();

    auto t1 = std::chrono::high_resolution_clock::now();
    ncnn::Mat in = preprocess(imageData, width, height, channels);
    auto t2 = std::chrono::high_resolution_clock::now();
    auto preprocessMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();

    // 创建提取器
    t1 = std::chrono::high_resolution_clock::now();
    ncnn::Extractor ex = net_.create_extractor();
    ex.input(inputName_.c_str(), in);
    t2 = std::chrono::high_resolution_clock::now();
    auto inputMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();

    LOGI("[Perf] NCNN RetinaFace inference START: input=%dx%d, vulkan=%s", inputSize_, inputSize_, net_.opt.use_vulkan_compute ? "true" : "false");

    // RetinaFace 输出：9 个 blob
    // 假设输出名称与 MNN 一致，按类型分组
    // score outputs: stride=8, 16, 32
    // bbox outputs: stride=8, 16, 32
    // landmark outputs: stride=8, 16, 32

    std::vector<FaceBox> allFaces;

    t1 = std::chrono::high_resolution_clock::now();

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
        // [关键修复] 根据实际输出维度确定 scoreChannels
        // NCNN 输出格式: [w=totalAnchors, h=channels, c=1]
        // 如果 h=1，表示只有 face score (scoreChannels=1)
        // 如果 h=2，表示 bg+face (scoreChannels=2)
        int scoreChannels = scoreOut.h > 1 ? scoreOut.h : 1;
        LOGD("  scoreChannels detected: %d (h=%d, c=%d)", scoreChannels, scoreOut.h, scoreOut.c);

        LOGD("Scale %d (stride=%d): featureSize=%d, score=[w=%d,h=%d,c=%d,dims=%d], bbox=[w=%d,h=%d,c=%d,dims=%d], landmark=[w=%d,h=%d,c=%d,dims=%d]",
             s + 1, scales[s].stride, featureSize,
             scoreOut.w, scoreOut.h, scoreOut.c, scoreOut.dims,
             bboxOut.w, bboxOut.h, bboxOut.c, bboxOut.dims,
             landmarkOut.w, landmarkOut.h, landmarkOut.c, landmarkOut.dims);

        // 将 ncnn::Mat 数据转为 float 数组
        int spatialSize = featureSize * featureSize;
        int totalAnchors = spatialSize * 2; // 每个位置 2 个 anchor
        
        std::vector<float> scoreData(totalAnchors * scoreChannels);
        std::vector<float> bboxData(totalAnchors * 4);
        std::vector<float> landmarkData(totalAnchors * 10);

        // [关键修复] 根据 NCNN 输出格式读取数据
        // NCNN Mat 数据布局：
        // - 1D Mat: [w=N] - 线性扁平化数据
        // - 2D Mat: [w=N, h=C] - N=anchors, C=channels
        // - 3D Mat: [w=featureSize, h=featureSize, c=C] - 空间特征图
        //
        // onnx2ncnn 转换后的 RetinaFace 输出通常是 1D 或 2D Mat：
        // - score: [w=totalAnchors*scoreChannels, h=1, c=1] (1D) 或 [w=totalAnchors, h=scoreChannels, c=1] (2D)
        // - bbox: [w=totalAnchors*4, h=1, c=1] (1D) 或 [w=totalAnchors, h=4, c=1] (2D)
        // - landmark: [w=totalAnchors*10, h=1, c=1] (1D) 或 [w=totalAnchors, h=10, c=1] (2D)

        // [调试] 打印输出维度信息
        LOGD("  Score output: dims=%d, w=%d, h=%d, c=%d, total=%zu", scoreOut.dims, scoreOut.w, scoreOut.h, scoreOut.c, scoreOut.total());
        LOGD("  BBox output: dims=%d, w=%d, h=%d, c=%d, total=%zu", bboxOut.dims, bboxOut.w, bboxOut.h, bboxOut.c, bboxOut.total());
        LOGD("  Landmark output: dims=%d, w=%d, h=%d, c=%d, total=%zu", landmarkOut.dims, landmarkOut.w, landmarkOut.h, landmarkOut.c, landmarkOut.total());

        // [关键修复] NCNN onnx2ncnn 转换后的 Permute + Reshape 产生的数据布局
        // 与 ONNX Transpose + Reshape 不同，需要重新排列数据。
        //
        // ONNX 布局 (期望):  for y: for x: for anchor -> index = y*F*2 + x*2 + a
        // NCNN 实际布局:     for y: for anchor: for x -> index = y*F*2 + a*F + x
        // 其中 F = featureSize
        //
        // 修复: 读取 NCNN 输出后，按正确顺序重新排列到 scoreData/bboxData/landmarkData

        auto reorderNcnnData = [&](const ncnn::Mat &mat, float *dst, int channelsPerAnchor) {
            // [关键修复] NCNN Permute(order=[0,2,1]) 对 [C,H,W] 输出 [C,W,H]
            // 然后 Reshape 成 2D Mat [w=spatial*channelsPerRow, h=numRows]
            // 其中 spatial = featureSize*featureSize, 每行包含 channelsPerRow 个 channel
            //
            // 原始内存顺序: for row: for channelInRow: for spatial
            //   spatial = x * featureSize + y (先遍历 y，再遍历 x)
            // 期望顺序 (ONNX): (y, x, a, c)
            int totalElements = mat.total();
            int expectedElements = totalAnchors * channelsPerAnchor;
            if (totalElements < expectedElements) {
                LOGD("  Reorder: insufficient data, expected %d, got %d", expectedElements, totalElements);
                return;
            }

            int spatialSize = featureSize * featureSize;

            if (mat.dims == 1) {
                // 1D Mat: 线性排列, 顺序为 (a, spatial) 即 globalChannel 先变, spatial 后变
                // NCNN Permute+Reshape 后的 spatial 顺序: (y, x) — 先遍历 x，再遍历 y
                for (int i = 0; i < totalElements; i++) {
                    int spatial = i % spatialSize;
                    int globalChannel = i / spatialSize;
                    int y = spatial / featureSize;
                    int x = spatial % featureSize;
                    int a = globalChannel / channelsPerAnchor;
                    int c = globalChannel % channelsPerAnchor;
                    int dstIdx = ((y * featureSize + x) * 2 + a) * channelsPerAnchor + c;
                    dst[dstIdx] = mat[i];
                }
            } else if (mat.dims == 2) {
                // 2D Mat [w=N, h=C]: 每行包含 channelsPerRow 个 channel
                // NCNN Permute+Reshape 后的 spatial 顺序: (y, x) — 先遍历 x，再遍历 y
                int channelsPerRow = mat.w / spatialSize;
                for (int row = 0; row < mat.h; row++) {
                    for (int col = 0; col < mat.w; col++) {
                        int spatial = col % spatialSize;
                        int channelInRow = col / spatialSize;
                        int globalChannel = row * channelsPerRow + channelInRow;
                        int y = spatial / featureSize;
                        int x = spatial % featureSize;
                        int a = globalChannel / channelsPerAnchor;
                        int c = globalChannel % channelsPerAnchor;
                        int dstIdx = ((y * featureSize + x) * 2 + a) * channelsPerAnchor + c;
                        dst[dstIdx] = mat.row(row)[col];
                    }
                }
            } else {
                // 3D Mat [w, h, c]: channel-major
                // spatial 顺序: (y, x) — 先遍历 x，再遍历 y
                int idx = 0;
                for (int c = 0; c < mat.c; c++) {
                    for (int h = 0; h < mat.h; h++) {
                        for (int w = 0; w < mat.w; w++) {
                            int spatial = w * mat.h + h;
                            int globalChannel = c;
                            int y = spatial / featureSize;
                            int x = spatial % featureSize;
                            int a = globalChannel / channelsPerAnchor;
                            int cc = globalChannel % channelsPerAnchor;
                            int dstIdx = ((y * featureSize + x) * 2 + a) * channelsPerAnchor + cc;
                            if (dstIdx < expectedElements) {
                                dst[dstIdx] = mat.channel(c).row(h)[w];
                            }
                            idx++;
                        }
                    }
                }
            }
        };

        // Score 数据重新排列
        if (scoreChannels == 1) {
            // 只有 face score，每个 anchor 1 个值
            reorderNcnnData(scoreOut, scoreData.data(), 1);
        } else {
            // bg + face score，每个 anchor 2 个值
            // NCNN 输出可能是 [totalAnchors, 2] 或扁平化的 [totalAnchors*2]
            reorderNcnnData(scoreOut, scoreData.data(), scoreChannels);
        }

        // BBox 数据重新排列: 每个 anchor 4 个值
        reorderNcnnData(bboxOut, bboxData.data(), 4);

        // Landmark 数据重新排列: 每个 anchor 10 个值
        reorderNcnnData(landmarkOut, landmarkData.data(), 10);

        // [调试] 打印该 scale 的最高 score
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
            int a = maxScoreIdx % 2;
            float cx = (x + 0.5f) * scales[s].stride;
            float cy = (y + 0.5f) * scales[s].stride;
            LOGD("  Scale %d max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                 s+1, maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
        }

        processRetinaFaceOutput(scoreData.data(), bboxData.data(), landmarkData.data(),
                                featureSize, scales[s].stride, scoreChannels,
                                allFaces, confidenceThreshold);
    }

    auto t3 = std::chrono::high_resolution_clock::now();
    auto extractMs = std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t1).count();

    LOGD("RetinaFace raw detections: %zu", allFaces.size());

    // NMS
    t1 = std::chrono::high_resolution_clock::now();
    auto nmsResult = applyNMS(allFaces, nmsThreshold);
    auto t4 = std::chrono::high_resolution_clock::now();
    auto nmsMs = std::chrono::duration_cast<std::chrono::milliseconds>(t4 - t1).count();

    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(t4 - totalStart).count();
    LOGI("[Perf] NCNN RetinaFace DONE: total=%ldms (preprocess=%ldms, input=%ldms, extract=%ldms, nms=%ldms), faces=%zu, vulkan=%s",
         totalMs, preprocessMs, inputMs, extractMs, nmsMs, nmsResult.size(),
         net_.opt.use_vulkan_compute ? "true" : "false");

    return nmsResult;
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

    // [对齐 MNN] 使用与 MNN 相同的 minSizes
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

                // [对齐 MNN] RetinaFace bbox 解码使用 stride
                float x1 = cx - dx * stride;
                float y1 = cy - dy * stride;
                float x2 = cx + dw * stride;
                float y2 = cy + dh * stride;

                // 限制坐标在有效范围内
                float maxSize = static_cast<float>(inputSize_);
                float clampedX1 = std::max(0.0f, std::min(x1, maxSize));
                float clampedY1 = std::max(0.0f, std::min(y1, maxSize));
                float clampedX2 = std::max(0.0f, std::min(x2, maxSize));
                float clampedY2 = std::max(0.0f, std::min(y2, maxSize));

                if (clampedX1 >= clampedX2 || clampedY1 >= clampedY2) {
                    continue;
                }

                FaceBox box;
                box.x1 = clampedX1;
                box.y1 = clampedY1;
                box.x2 = clampedX2;
                box.y2 = clampedY2;
                box.confidence = faceScore;

                // [对齐 MNN] landmark 解码使用 stride
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
