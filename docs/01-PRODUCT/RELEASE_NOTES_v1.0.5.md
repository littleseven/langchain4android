# PicMe 1.0.5 Release Notes

> **版本**: 1.0.5  
> **versionCode**: 10005（建议发布前在 `app/build.gradle.kts` 中确认并递增）  
> **发布日期**: 2026-06-30  
> **适用平台**: Google Play（Android arm64-v8a，minSdk 24）  
> **说明**: 本版本包含 v1.0.4 全部功能；版本号递增至 1.0.5 以符合 Google Play 发布要求。

---

## Google Play 商店文案

### 简体中文 (zh-CN)

```
PicMe 1.0.5 更新：
- 全新自然语言相册搜索：说“五月的照片”“海边的合照”即可找到照片。
- 搜索结果支持长按多选、批量删除与分享。
- TAG 生成增强：新增 ML Kit 标签 Pass，430+ 英文标签已映射中文，搜索更准。
- 修复搜索结果缩略图偶发崩溃、中文月份解析等稳定性问题。
- 设置页与模型中心界面重构，使用更顺畅。
```

### 英文 (en-US)

```
PicMe 1.0.5:
- New natural-language gallery search: find photos by saying "photos from May" or "group photo by the sea".
- Search results now support long-press multi-select, batch delete and share.
- Improved TAG generation with ML Kit label pass and 430+ EN→CN label mappings.
- Fixed recycled bitmap crash in search thumbnails and other stability issues.
- Refreshed Settings and Model Center UI.
```

### 繁体中文 (zh-TW)

```
PicMe 1.0.5 更新：
- 全新自然語言相簿搜尋：說「五月的照片」「海邊的合照」即可找到照片。
- 搜尋結果支援長按多選、批次刪除與分享。
- TAG 生成增強：新增 ML Kit 標籤 Pass，430+ 英文標籤已對應中文，搜尋更準。
- 修正搜尋結果縮圖偶發崩潰、中文月份解析等穩定性問題。
- 設定頁與模型中心介面重構，使用更順暢。
```

---

## 主要变更（RD 视角）

### 新增功能

- **自然语言相册搜索**
  - 新增 `QuerySegmenter`、`ExplicitFirstSearchPipeline`、`SemanticSearchEngine`。
  - 支持中文时间/地点/人物/TAG 等多维度显式召回，MobileCLIP 语义召回补充。
  - 搜索结果支持长按进入批量选择、全选、批量删除与分享。
- **TAG 生成增强**
  - ML Kit Image Labeler 作为独立 `TagScanPass` 接入，输出英文标签。
  - 新增 430+ 英文标签的中文翻译映射，改善中文搜索命中率。
  - `TagGenerationControlScreen` 重构，支持按类别/时间范围重新生成与增量扫描。
- **语音输入**
  - 聊天页新增语音输入能力（基于 sherpa-onnx）。
- **模型中心与设置页**
  - 设置页改为多级分类结构。
  - 模型管理中心按服务功能分类，支持新版 kimi / deepseek 模型配置。
- **远程推理**
  - 统一本地模型加载管理机制。
  - 远程推理架构与模型配置更新。

### 修复与优化

- 修复搜索结果缩略图因 Coil crossfade 导致的 recycled bitmap 崩溃。
- 修复搜索“五月”等中文月份未正确解析为时间过滤的问题。
- 修复语义搜索中偶发的 NaN 相似度计算问题。
- 修复 TAG 扫描统计口径不一致、扫描中统计不刷新的问题。
- 修复 OPUS-MT tokenizer vocab mapping 与 decoder 问题。
- 修复 CLIP 文本编码器的张量维度与数据类型问题。
- 放宽人脸检测阈值，降低漏检。
- 首页导航与聊天页界面调整。

### 技术文档

- 新增 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` 作为相册搜索唯一事实来源。
- 更新 `AUTO_TAG_GENERATION_SPEC.md`、`TAG_DATABASE_SCHEMA.md`、`TAG_I18N_DESIGN.md`。
- 清理冗余过时文档，修复 docs/ 内部大量失效链接。

---

## 已知问题

- 自然语言搜索的语义召回质量依赖 MobileCLIP-S2 ONNX 模型，部分抽象描述（如“大美女”）可能召回不够精准，后续会持续优化翻译映射与语义模型。
- TAG 全量扫描在低端设备上耗时较长，建议在充电 + Wi-Fi 环境下进行。
- 部分旧机型 OpenCL 推理可能触发超时降级，已加入 `OpenClGuardian` 自动处理。

---

## 发布检查清单

- [ ] 更新 `app/build.gradle.kts` 中 `versionCode` 为 `10005`，`versionName` 为 `"1.0.5"`
- [ ] 运行 `./gradlew :app:bundleRelease` 生成 AAB
- [ ] 在 Google Play Console 上传 AAB
- [ ] 填写 zh-CN / en-US / zh-TW 三语 Release Note
- [ ] 确认签名与环境变量 `PICME_RELEASE_STORE_FILE` 等已配置
- [ ] 执行冒烟测试：相机预览、拍照、相册搜索、TAG 扫描、删除/分享

---

> **维护者**: PM / CO  
> **最后更新**: 2026-06-30
