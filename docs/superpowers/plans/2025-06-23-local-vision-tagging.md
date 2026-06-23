# Local Vision Model Image Tagging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Qwen3.5-2B-MNN vision model to understand gallery images and generate Chinese tags, stored in the normalized TagEntity + MediaTagCrossRef tables.

**Architecture:** Extend the existing MNN-LLM JNI bridge to accept images via MultimodalPrompt, add Kotlin APIs in MnnLlmClient/LocalLlmEngine, then build a coroutine-based ImageTagIndexingWorker (following FaceClusteringWorker pattern) for batch background processing.

**Tech Stack:** C++17 (JNI), Kotlin coroutines, MNN-LLM C++ library, Room DB (TagEntity/MediaTagCrossRef)

**Design Spec:** `docs/superpowers/specs/2025-06-23-local-vision-tagging-design.md`

---

### Task 1: Update llm_models.json — add vision files to 2B entry

**Files:**
- Modify: `app/src/main/res/raw/llm_models.json:77-91`

- [ ] **Step 1: Add missing vision files to Qwen3.5-2B-MNN entry**

The ModelScope repo `budaoshou/Qwen3.5-2B-MNN` contains these files not listed locally:
`llm_config.json`, `llm.mnn.json`, `visual.mnn`, `visual.mnn.weight`

Update the 2B model entry's `"files"` array from:
```json
"files": [
  "config.json",
  "llm.mnn",
  "llm.mnn.weight",
  "tokenizer.txt"
],
```
to:
```json
"files": [
  "config.json",
  "llm_config.json",
  "llm.mnn",
  "llm.mnn.json",
  "llm.mnn.weight",
  "tokenizer.txt",
  "visual.mnn",
  "visual.mnn.weight"
],
```

Also add `"vision"` to the `"tags"` array:
```json
"tags": ["chat", "reasoning", "multilingual", "vision"]
```

And update description to reflect vision capability:
```json
"description": "阿里通义千问 3.5 2B 多模态版，支持图片理解与推理，适合高端设备本地运行",
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/raw/llm_models.json
git commit -m "feat(llm): add vision files to Qwen3.5-2B-MNN model config"
```

---

### Task 2: Add JNI native method for image generation

**Files:**
- Modify: `runtime-core/src/main/cpp/llm_jni_bridge.cpp` — add `nativeGenerateWithImage` function

**Key technical detail — Bitmap → MNN VARP conversion:**

The MNN-LLM C++ API expects `MultimodalPrompt` which contains `PromptImagePart { VARP image_data, int width, int height }`. We convert the Android Bitmap to an MNN VARP by:

1. `AndroidBitmap_lockPixels()` to get raw RGBA_8888 pixel data
2. Convert RGBA → RGB float (3 channels, normalized to [0,1] or as model expects)
3. Create MNN tensor via `MNN::Express::_Input({1, 3, height, width}, NC4HW4, ...)` and fill, or use `_Const(ptr, dims, format)`
4. Store in `PromptImagePart`, reference by key `"image"` in `MultimodalPrompt.images`

The prompt template for Qwen3-VL uses the format: `<|vision_start|><|image_pad|><|vision_end|>user text`

- [ ] **Step 1: Add `nativeGenerateWithImage` JNI function**

Append the following function to `llm_jni_bridge.cpp` (before the closing of `extern "C"` block):

```cpp
/**
 * 使用 system prompt + user prompt + 图片进行多模态生成。
 *
 * @param handle       LLM native handle
 * @param systemPrompt system 提示词
 * @param userPrompt   user 提示词（不含图片标记，此函数自动拼接模板）
 * @param bitmap       Android Bitmap 对象
 * @param maxNewTokens 最大生成 token 数
 * @return HashMap<String, Object> (response, prompt_len, decode_len, vision_time, ...)
 */
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithImage(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jobject bitmap,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    // 1. 获取 system / user prompt
    const char *systemCStr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *userCStr = env->GetStringUTFChars(userPrompt, nullptr);
    std::string systemStr(systemCStr);
    std::string userStr(userCStr);
    env->ReleaseStringUTFChars(systemPrompt, systemCStr);
    env->ReleaseStringUTFChars(userPrompt, userCStr);

    // 2. 从 Android Bitmap 提取像素数据并构建 MNN VARP
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
        LOGE("Failed to get bitmap info");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed to get bitmap info"));
        return hashMap;
    }

    void *bitmapPixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed to lock bitmap pixels"));
        return hashMap;
    }

    int width = bitmapInfo.width;
    int height = bitmapInfo.height;

    // 将 RGBA_8888 像素转换为 float32 RGB NCHW 格式
    // MNN Qwen-VL 模型期望输入: [1, 3, height, width] NCHW float32
    std::vector<float> rgbData(3 * height * width);
    uint8_t *pixels = static_cast<uint8_t *>(bitmapPixels);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int srcIdx = y * bitmapInfo.stride + x * 4;  // RGBA_8888 stride
            int dstR = 0 * height * width + y * width + x;
            int dstG = 1 * height * width + y * width + x;
            int dstB = 2 * height * width + y * width + x;
            rgbData[dstR] = pixels[srcIdx] / 255.0f;       // R
            rgbData[dstG] = pixels[srcIdx + 1] / 255.0f;   // G
            rgbData[dstB] = pixels[srcIdx + 2] / 255.0f;   // B
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    // 创建 MNN VARP: _Const(data_ptr, {1,3,H,W}, NCHW, halide_type<float>)
    std::vector<int> imageShape = {1, 3, height, width};
    auto imageVar = MNN::Express::_Const(
        rgbData.data(), imageShape, MNN::Express::NCHW, halide_type_of<float>());

    // 3. 构建 MultimodalPrompt
    MNN::Transformer::MultimodalPrompt multimodal;
    // 使用 Qwen-VL chat template 格式
    multimodal.prompt_template =
        "<|im_start|>system\n" + systemStr + "<|im_end|>\n"
        "<|im_start|>user\n"
        "<|vision_start|><|image_pad|><|vision_end|>" + userStr + "<|im_end|>\n"
        "<|im_start|>assistant\n";
    multimodal.images["image"] = {imageVar, width, height};

    LOGD("Generating with image: %dx%d, system=%zu chars, user=%zu chars",
         width, height, systemStr.size(), userStr.size());

    // 4. 调用 MNN-LLM multimodal response
    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(multimodal, &oss, nullptr, maxNewTokens);
    }
    auto end = std::chrono::high_resolution_clock::now();
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = oss.str();
    // 打印日志（分段）
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("Image generation response: %s", result.c_str());
    } else {
        LOGD("Image generation response (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t chunkLen = (i + LOG_CHUNK_SIZE <= result.length())
                ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, chunkLen);
            LOGD("  [chunk %zu]: %s", i / LOG_CHUNK_SIZE + 1, chunk.c_str());
        }
    }

    // 5. 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}
```

Also add the required JNI bitmap header include at the top of the file, after the existing includes:
```cpp
#include <android/bitmap.h>
```

- [ ] **Step 2: Commit**

```bash
git add runtime-core/src/main/cpp/llm_jni_bridge.cpp
git commit -m "feat(llm): add nativeGenerateWithImage JNI method for multimodal inference"
```

---

### Task 3: Add Kotlin API to MnnLlmClient

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt` — add `generateWithImage` method and native declaration

- [ ] **Step 1: Add native method declaration**

Add to the native methods section (after `nativeIsLoaded`):
```kotlin
private external fun nativeGenerateWithImage(
    handle: Long,
    systemPrompt: String,
    userPrompt: String,
    bitmap: Bitmap,
    maxNewTokens: Int
): HashMap<String, Any>
```

Also add the import at the top:
```kotlin
import android.graphics.Bitmap
```

- [ ] **Step 2: Add public `generateWithImage` method**

Add after `generateWithSystem`:
```kotlin
/**
 * 使用图片 + system prompt + user prompt 进行多模态生成（同步阻塞）。
 *
 * 图片被转换为 RGB float 数据，通过 MultimodalPrompt 传给 MNN-LLM vision encoder。
 *
 * **注意**：此方法应在专用线程上调用（由 [LocalLlmEngine] 统一调度）。
 *
 * @param systemPrompt 系统提示词
 * @param userPrompt   用户提示词（不含图片标记）
 * @param bitmap       输入图片，建议尺寸 ≤ 1024px
 * @param maxNewTokens 最大生成 token 数，默认 128
 * @return 包含完整回复和性能指标的 StreamResult
 */
fun generateWithImage(
    systemPrompt: String,
    userPrompt: String,
    bitmap: Bitmap,
    maxNewTokens: Int = 128
): StreamResult {
    if (!isLoaded) {
        Logger.w(tag, "LLM not loaded, cannot generate with image")
        return StreamResult(error = "LLM not loaded")
    }

    return try {
        val resultMap = MnnGlobalReleaseLock.withOperation {
            nativeGenerateWithImage(nativeHandle, systemPrompt, userPrompt, bitmap, maxNewTokens)
        }
        StreamResult.fromHashMap(resultMap)
    } catch (exception: Exception) {
        Logger.e(tag, "Image generation failed", exception)
        StreamResult(error = exception.message ?: "Unknown error")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt
git commit -m "feat(llm): add generateWithImage API to MnnLlmClient"
```

---

### Task 4: Add imageInference to LocalLlmEngine

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/LocalLlmEngine.kt` — add `imageInference` method

- [ ] **Step 1: Add `imageInference` method**

Add after the `chat` methods (before `unload`):
```kotlin
/**
 * 使用本地多模态模型对图片进行推理。
 *
 * 将 [systemPrompt] 和 [userPrompt] 与 [bitmap] 一起发送给 MNN-LLM 视觉编码器，
 * 返回模型生成的文本回复。
 *
 * **注意**：此方法在 [modelDispatcher] 上阻塞执行，调用方应在 IO 协程中调用。
 *
 * @param bitmap       输入图片
 * @param systemPrompt 系统提示词（定义任务，如 "简短描述图片内容"）
 * @param userPrompt   用户提示词（具体问题）
 * @param maxTokens    最大生成 token 数，默认 128
 * @return 模型生成的文本回复，失败时返回空字符串
 */
suspend fun imageInference(
    bitmap: Bitmap,
    systemPrompt: String,
    userPrompt: String = "请描述这张图片",
    maxTokens: Int = 128
): String = withContext(modelDispatcher) {
    engineMutex.withLock {
        if (!client.isLoaded) {
            Logger.w(tag, "LLM not loaded, cannot do image inference")
            return@withLock ""
        }

        try {
            val result = client.generateWithImage(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                bitmap = bitmap,
                maxNewTokens = maxTokens
            )
            if (result.error != null) {
                Logger.w(tag, "Image inference error: ${result.error}")
                return@withLock ""
            }
            lastGenerationMetrics = LlmGenerationMetrics(
                promptLen = result.promptLen,
                decodeLen = result.decodeLen,
                prefillTime = result.prefillTime,
                decodeTime = result.decodeTime,
                prefillSpeed = result.prefillSpeed,
                decodeSpeed = result.decodeSpeed
            )
            result.response
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Logger.e(tag, "Image inference failed", exception)
            ""
        }
    }
}
```

Add required import:
```kotlin
import android.graphics.Bitmap
```

- [ ] **Step 2: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/LocalLlmEngine.kt
git commit -m "feat(llm): add imageInference method to LocalLlmEngine"
```

---

### Task 5: Create ImageTagIndexingWorker

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/indexing/ImageTagIndexingWorker.kt`

This worker follows the same coroutine pattern as `FaceClusteringWorker`:
- `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- `start()` / `forceReTag()` methods
- Processes media where `labels IS NULL` (not yet tagged)
- Calls `localLlmEngine.imageInference()` for each image
- Parses the response into a tag list, writes to DB

- [ ] **Step 1: Create the worker file**

```kotlin
package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 图片 AI 标签索引 Worker
 *
 * 使用本地多模态 LLM (Qwen3.5-2B-MNN) 理解相册图片内容，
 * 生成中文标签并写入 TagEntity + MediaTagCrossRef 规范化表。
 *
 * 流程:
 * 1. 查询 labels IS NULL 的未标记媒体
 * 2. 逐张加载 Bitmap → resize (最长边 512px)
 * 3. 调用 LocalLlmEngine.imageInference(bitmap, prompt)
 * 4. 解析模型返回的标签文本 → 写入 DB
 * 5. 同时更新 MediaEntity.labels（兼容旧搜索路径）
 *
 * 触发时机: Gallery 首次加载、下拉刷新
 * 约束: 需 Vision 模型已加载、节流 200ms/张
 */
class ImageTagIndexingWorker(
    private val context: Context,
    private val localLlmEngine: LocalLlmEngine
) {

    companion object {
        private const val TAG = "PicMe:ImageTag"
        private const val MAX_IMAGE_SIZE = 512
        private const val THROTTLE_MS = 200L

        /** 图片标签生成的 system prompt */
        val TAGGING_SYSTEM_PROMPT = """
你是一个图像内容分析助手。你的任务是用中文简短描述图片的内容，输出逗号分隔的标签。

规则：
1. 只输出标签，用中文逗号或英文逗号分隔
2. 标签应简短（2-5个字），例如：猫、户外、阳光、草地、食物、建筑
3. 描述图片中的主要物体、场景、氛围
4. 不超过 10 个标签
5. 不要输出任何解释或其他文字
        """.trimIndent()

        /** 标签生成的 user prompt */
        const val TAGGING_USER_PROMPT = "请用中文标签描述这张图片的内容"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    val isRunning: Boolean
        get() = currentJob?.isActive == true

    /**
     * 启动批量标签索引（如已有任务运行则忽略）。
     */
    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Tag indexing already in progress")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "AI tag indexing started")
            doBatchTagging()
            Logger.i(TAG, "AI tag indexing completed")
        }
    }

    /**
     * 强制重新标记所有媒体（清空已有标签后全量重标）。
     */
    fun forceReTag() {
        if (currentJob?.isActive == true) {
            Logger.w(TAG, "Tag indexing in progress, cancelling and restarting in force mode")
            currentJob?.cancel()
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Force re-tag: resetting all labels")
            val db = AppDatabase.getDatabase(context)
            db.mediaDao().resetAllLabels()
            doBatchTagging()
            Logger.i(TAG, "Force re-tag completed")
        }
    }

    private suspend fun doBatchTagging() {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()
        val tagDao = db.tagDao()
        val tagIndexUpdater = TagIndexUpdater(tagDao)

        val unlabeledMedia = dao.getUnlabeledMedia()
        if (unlabeledMedia.isEmpty()) {
            Logger.i(TAG, "All media already tagged")
            return
        }

        Logger.i(TAG, "Found ${unlabeledMedia.size} media without AI tags")

        var taggedCount = 0
        for (entity in unlabeledMedia) {
            if (!currentJob?.isActive!!) {
                Logger.i(TAG, "Tag indexing cancelled after $taggedCount items")
                break
            }

            try {
                val bitmap = loadBitmapForVision(entity.uri)
                if (bitmap == null) {
                    Logger.w(TAG, "Failed to load bitmap: ${entity.uri}")
                    continue
                }

                val response = try {
                    localLlmEngine.imageInference(
                        bitmap = bitmap,
                        systemPrompt = TAGGING_SYSTEM_PROMPT,
                        userPrompt = TAGGING_USER_PROMPT,
                        maxTokens = 64
                    )
                } finally {
                    bitmap.recycle()
                }

                if (response.isBlank()) {
                    Logger.w(TAG, "Empty response for media ${entity.id}")
                    continue
                }

                // 解析标签: "猫, 户外, 阳光, 草地" → ["猫","户外","阳光","草地"]
                val labels = parseLabels(response)
                if (labels.isEmpty()) {
                    Logger.d(TAG, "No valid labels parsed from: $response")
                    continue
                }

                // 写入规范化标签表
                val labelsJson = JSONArray(labels.toList()).toString()
                tagIndexUpdater.updateIndex(entity.id, labelsJson)

                // 同时更新 MediaEntity.labels（兼容旧 LIKE 搜索）
                dao.updateLabels(entity.id, labelsJson)

                taggedCount++
                Logger.d(TAG, "Tagged media ${entity.id}: $labels (${taggedCount}/${unlabeledMedia.size})")

                // 节流，防止连续推理导致过热
                delay(THROTTLE_MS)
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to tag media ${entity.id}: ${e.message}")
            }
        }

        Logger.i(TAG, "Tag indexing done: $taggedCount/${unlabeledMedia.size} tagged")
    }

    /**
     * 加载 Bitmap 并缩放到适合 vision encoder 的尺寸（最长边 MAX_IMAGE_SIZE px）。
     *
     * 两次打开 stream：第一次仅解码尺寸计算 sampleSize，第二次实际解码。
     */
    private fun loadBitmapForVision(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            // 第一次：仅解码尺寸
            val dimensions = context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                opts.outWidth to opts.outHeight
            } ?: return null

            val (rawWidth, rawHeight) = dimensions
            if (rawWidth <= 0 || rawHeight <= 0) return null
            val sampleSize = calculateInSampleSize(rawWidth, rawHeight, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)

            // 第二次：实际解码
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load bitmap: $uriString", e)
            null
        }
    }

    /**
     * 计算 BitmapFactory 的 inSampleSize（必须是 2 的幂）。
     */
    private fun calculateInSampleSize(
        rawWidth: Int, rawHeight: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var sampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / sampleSize) >= reqHeight &&
                   (halfWidth / sampleSize) >= reqWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * 解析模型返回的标签文本。
     *
     * 输入示例: "猫, 户外, 阳光, 草地" 或 "猫，户外，阳光，草地"
     * 输出: ["猫", "户外", "阳光", "草地"]
     */
    internal fun parseLabels(response: String): List<String> {
        return response
            .replace("，", ",")       // 统一分隔符
            .replace("\n", ",")       // 换行转逗号
            .replace("、", ",")       // 顿号转逗号
            .split(",")
            .map { label ->
                label.trim()
                    .removePrefix("\"")   // 去引号
                    .removeSuffix("\"")
                    .removePrefix("「")
                    .removeSuffix("」")
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .replace(Regex("^\\d+[.、．]\\s*"), "")  // 去序号 "1. 猫" → "猫"
            }
            .filter { label ->
                label.isNotEmpty() &&
                label.length <= 10 &&                     // 跳过过长的片段
                !label.startsWith("标签") &&               // 跳过废话
                !label.contains("输出") &&
                !label.contains("以下") &&
                !label.matches(Regex("^[\\d.]+$"))        // 跳过纯数字
            }
            .take(10)  // 最多 10 个标签
    }
}
```

Actually, the bitmap loading code above has a bug — it opens the stream three times instead of once with proper sizing. Let me fix the `loadBitmapForVision` method:

```kotlin
/**
 * 加载 Bitmap 并缩放到适合 vision encoder 的尺寸（最长边 MAX_IMAGE_SIZE px）。
 */
private fun loadBitmapForVision(uriString: String): Bitmap? {
    return try {
        val uri = Uri.parse(uriString)
        // 第一次：仅解码尺寸
        val dimensions = context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            opts.outWidth to opts.outHeight
        } ?: return null

        val (rawWidth, rawHeight) = dimensions
        val sampleSize = calculateInSampleSize(rawWidth, rawHeight, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)

        // 第二次：实际解码
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to load bitmap: $uriString", e)
        null
    }
}
```

- [ ] **Step 2: Add DAO query methods**

Add to `MediaDao`:

```kotlin
@Query("SELECT * FROM media_assets WHERE labels IS NULL OR labels = '' ORDER BY captureDate DESC")
suspend fun getUnlabeledMedia(): List<MediaEntity>

@Query("UPDATE media_assets SET labels = :labels WHERE id = :mediaId")
suspend fun updateLabels(mediaId: Long, labels: String)

@Query("UPDATE media_assets SET labels = NULL")
suspend fun resetAllLabels()
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/ImageTagIndexingWorker.kt
git add app/src/main/java/com/mamba/picme/data/local/MediaDao.kt
git commit -m "feat(indexing): add ImageTagIndexingWorker for AI vision tagging"
```

---

### Task 6: Expose localLlmEngine from AgentOrchestrator

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt`

`AgentOrchestrator` holds `localLlmEngine` privately (via `configurator.localLlmEngine`). We need to expose it for non-agent consumers like `ImageTagIndexingWorker`.

- [ ] **Step 1: Add public getter**

Add after the existing `isModelLoaded` getter:
```kotlin
/**
 * 获取本地 LLM 推理引擎。
 *
 * 供非 Agent 消费者（如后台标签索引）直接使用模型进行推理。
 * **注意**：调用方应确保模型已加载后再使用。
 */
fun getLocalLlmEngine(): LocalLlmEngine = localLlmEngine
```

Also add the import (should already exist since `localLlmEngine` is used internally):
```kotlin
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
```

- [ ] **Step 2: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt
git commit -m "feat(agent): expose localLlmEngine getter on AgentOrchestrator"
```

---

### Task 7: Wire up in AppContainer

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1: Add ImageTagIndexingWorker to AppContainer**

Add import:
```kotlin
import com.mamba.picme.data.indexing.ImageTagIndexingWorker
import com.mamba.picme.agent.core.facade.AgentOrchestrator
```

Add to the `AppContainer` interface:
```kotlin
val imageTagIndexingWorker: ImageTagIndexingWorker
```

Add to `AppContainerImpl`:
```kotlin
override val imageTagIndexingWorker: ImageTagIndexingWorker by lazy {
    val llmEngine = AgentOrchestrator.getInstance(context).getLocalLlmEngine()
    ImageTagIndexingWorker(context, llmEngine)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(di): wire ImageTagIndexingWorker into AppContainer"
```

---

### Task 8: Trigger worker in GalleryScreen

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`

- [ ] **Step 1: Add image tag worker trigger**

In GalleryScreen, after the existing `faceClusteringWorker` trigger (around line 213-215), add:

```kotlin
// 图片 AI 标签索引 — 在媒体加载完成后触发
// Worker 内部自动跳过已标记的媒体，只处理增量
// 需要 Vision 模型已加载（由 AgentOrchestrator 管理）
val imageTagWorker = remember { app.container.imageTagIndexingWorker }
LaunchedEffect(allFlatMedia.size) {
    if (hasMediaPermission && allFlatMedia.isNotEmpty()) {
        imageTagWorker.start()
    }
}
```

Note: The worker itself checks `localLlmEngine.isLoaded` internally and will skip if the model isn't ready.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt
git commit -m "feat(gallery): trigger AI image tag indexing on gallery load"
```

---

### Task 8: Build, test, verify

- [ ] **Step 1: Build the project**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install to device**

```bash
adb install -r app/build/outputs/apk/debug/picme-debug.apk
```

- [ ] **Step 3: Manual verification**

1. Ensure Qwen3.5-2B-MNN model is downloaded (with visual.mnn files — may need re-download if old version was cached without vision files)
2. Open Gallery — observe logcat for `PicMe:ImageTag` messages
3. Wait for tags to be generated on unlabeled photos
4. Search for a generated tag (e.g. "猫") in gallery search — verify results appear
5. Scroll gallery — verify no crash

```bash
adb logcat -s "PicMe:ImageTag" "PicMe:LocalLlmEngine" "PicMe:LlmJNI"
```
