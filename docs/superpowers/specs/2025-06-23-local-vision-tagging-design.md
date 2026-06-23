# Local Vision Model Image Tagging

**Date**: 2025-06-23
**Status**: Approved
**Priority**: Phase 1 (batch indexing) → Phase 2 (agent dialog)

## Background

Qwen3.5-2B-MNN model on ModelScope includes vision encoder files (`visual.mnn` + `visual.mnn.weight`). The MNN-LLM C++ library fully supports multimodal input via `MultimodalPrompt`. However:

1. Local `llm_models.json` is missing the vision file entries for the 2B model
2. JNI bridge only exposes text-only `nativeGenerateWithSystem`
3. No Kotlin API exists for image inference
4. No image tagging capability exists

## Design

### Architecture

```
Model Download (existing) → JNI Bridge (extend) → Kotlin API (new) → Workers (new) → DB (reuse)
```

### Phase 1: Batch Background Tag Indexing (Priority)

```
ImageTagIndexingWorker (coroutine-based, like FaceClusteringWorker)
  ├─ Iterate unlabeled media (labels IS NULL)
  ├─ Load Bitmap from URI → resize
  ├─ Call LocalLlmEngine.imageInference(bitmap, prompt)
  ├─ Parse model response → tag list
  ├─ Write TagEntity + MediaTagCrossRef (new normalized tables)
  └─ Write MediaEntity.labels (backward compat)
```

Trigger: GalleryScreen first load, pull-to-refresh, new photos added
Constraints: Vision model downloaded, WiFi only, throttle 200ms/image
Prompt template: "用中文简短描述这张图片的内容，输出逗号分隔的标签，不超过10个词"

### Phase 2: Agent Dialog Image Understanding

New `DescribeImage` capability — user asks "分析这张照片" in agent chat, agent retrieves current/selected image, calls same inference API, returns description.

### Files Changed

| Layer | File | Change |
|-------|------|--------|
| Config | `llm_models.json` | Add `visual.mnn`, `visual.mnn.weight`, `llm_config.json`, `llm.mnn.json` to 2B entry |
| JNI C++ | `llm_jni_bridge.cpp` | New `nativeGenerateWithImage()` — construct `MultimodalPrompt`, call `llm->response(multimodal, ...)` |
| Kotlin | `MnnLlmClient.kt` | New `generateWithImage(systemPrompt, userPrompt, bitmap, maxTokens)` |
| Kotlin | `LocalLlmEngine.kt` | New `imageInference(bitmap, prompt): String` |
| Worker | `ImageTagIndexingWorker.kt` (new) | Batch background tag generation |
| UI | `GalleryScreen.kt` | Trigger worker on load |
| Phase 2 | `DescribeImage` capability | Agent dialog integration |

### Tag Storage

- `TagEntity` + `MediaTagCrossRef`: normalized tables (existing schema, currently unused for AI tags)
- `MediaEntity.labels`: JSON string for backward compat with `searchByLabel`
- `MediaSearchEngine`: no changes needed, existing logic covers both paths
