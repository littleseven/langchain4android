# 大美丽（BIG_BEAUTY）与 GPUPixel 融合路线图：现状评估与理想态演进

## 1. 核心目标
构建 PicMe 统一视觉引擎（Unified Beauty Engine），实现：
- **极致性能**：全链路零拷贝 GPU 管线。
- **算法领先**：引入引导滤波（Guided Filter）与 3D LUT。
- **一致体验**：预览与拍照效果 100% 同步（方案 A）。
- **模块架构**：算法算子化、渲染管线化。

---

## 2. 现状与理想态差异评估 (Gap Analysis)

### 2.1 渲染管线对比分析（含拷贝环节标注）

#### 当前渲染管线 (Current Status)
目前 GPUPixel 路径由于历史集成原因，在数据传递过程中存在 **4 处显著的 CPU 拷贝与同步阻塞**，属于典型的非零拷贝方案。

```text
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ [大美丽路径：真正的零拷贝]                                                               │
│ CameraX Preview ────> SurfaceTexture(OES) ────> GPU Texture ID ────> GLSL/Pass Pipeline ────> SurfaceView │
│                       (显存内自动转换)            (无像素搬运)         (基础主 Shader + 按需多 Pass) (硬件合成) │
└──────────────────────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [GPUPixel 路径：多重拷贝链路]                                                                         │
│ CameraX ──> ImageProxy ──> [CPU: YUV2RGBA] ──> [CPU: 旋转] ──> [JNI: 传输] ──> [GPU: 上传] ──> FilterChain ──> TextureView │
│ (Analysis)    (YUV)         (第 1 次 COPY)     (第 2 次 COPY)  (第 3 次 COPY)  (第 4 次 COPY)               │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

#### 理想渲染管线 (Ideal State)
理想态下，GPUPixel 应直接消费大美丽的 OES 纹理，将所有像素处理保留在 GPU 内部，实现全链路零拷贝。

```text
┌───────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [理想渲染管线 (Ideal State)]                                                                      │
│                                                                                                   │
│ CameraX Preview (OES) ─┐                                                                          │
│                        ├─> [Shared EGL Context] ─> [Unified Renderer Pipeline] ─┬─> SurfaceView (预览) │
│ CameraX Capture (FBO) ─┘           (零拷贝)           (插件化算子链：磨皮/LUT/形变)  └─> Image File  (保存) │
│                                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 GPUPixel 现状 4 次拷贝深度解析

| 环节 | 原因 (Why) | 原理 (How) | 是否可消除 | 消除方案 |
| :--- | :--- | :--- | :--- | :--- |
| **① CPU 格式转换** | `ImageAnalysis` 仅能提供 YUV 格式，而渲染链路需 RGBA。 | 在 CPU 线程遍历像素阵列，执行 YUV420P 到 RGBA8888 的数学换算。 | **是** | 改用 `Preview` UseCase 的 **OES 纹理路径**。GPU/ISP 硬件自动完成格式转换，对 CPU 零负载。 |
| **② CPU 旋转处理** | 传感器物理布局为横向，竖屏预览需 90°/270° 旋转。 | 创建新的 `ByteArray`，通过 CPU 计算坐标映射关系并搬运像素。 | **是** | 使用 **Vertex Shader 坐标变换**。仅需修改 4 个顶点的纹理坐标映射，像素在内存中位置保持不变。 |
| **③ JNI 边界传输** | 需要将 Java 层的像素数据传递给 C++ 层算法库。 | `nativeProcessData` 访问 `byte[]`。为保证 C++ 处理时 Java 数组不被 GC 移动，需进行内存锁定或数据备份。 | **是** | 跨层仅传递 **Texture ID (int)**。纹理本身保留在显存，不经过 JNI 的像素级数据搬运。 |
| **④ GPU 纹理上传** | 像素数据在 CPU 内存，渲染需在 GPU 显存执行。 | 执行 `glTexImage2D`。驱动程序将系统内存数据通过总线同步拷贝到 GPU VRAM。 | **是** | 利用 **SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)**。数据由摄像头直接写入显存纹理，无需手动上传。 |

### 2.3 内存损耗评估 (Memory Waste Analysis)
以 1080P (1920x1080) 分辨率为例，一个 RGBA 帧占用约 **8.29 MB**。

| 路径 | CPU 内存占用 (每帧) | 内存浪费详情 | GC 压力 |
| :--- | :--- | :--- | :--- |
| **大美丽 (BIG_BEAUTY)** | **0 MB** | 像素数据始终在显存中（OES 纹理），不占用 Dalvik/Native 堆。 | 极低 |
| **GPUPixel (当前)** | **16.6 MB ~ 24.8 MB** | 1. YUV2RGBA 中转 (8.29MB)<br>2. CPU 旋转副本 (8.29MB)<br>3. JNI 传输备份 (约 8.29MB) | **高** (引发频繁 GC 导致掉帧) |

---

## 3. 预览与拍照效果一致性方案 (Consistency Strategy)

### 3.1 现状问题：方案 B 变种的局限性
当前采用的是**方案 B 变种**：预览使用 GPU 处理，拍照使用 CPU 后处理。
- **差异根源**：Shader（GLSL）与 CPU 算法（Canvas/Bitmap）的数学实现不可能完全对齐。
- **检测差异**：预览使用 ML Kit，拍照可能需要重新检测，导致人脸变形点位偏移。

### 3.2 终极目标：方案 A (Full GPU Pipeline)
实现“**所见即所得**”的唯一途径是让拍照任务在 GPU 内部执行与预览完全相同的算子链。

#### 核心实现：离屏渲染与 FBO 复用
```text
[方案 A 流程]
1. ImageCapture 捕获原始高清帧 (JPEG/RAW)
2. 解码至 GPU 纹理 (FBO)
3. 挂载 Unified Renderer Pipeline (与预览共用同一套 Shader)
4. 设置渲染目标为 Pbuffer Surface (离屏 Surface)
5. 执行渲染 -> 提取结果 -> 保存
```

#### 关键优势
1. **像素级同步**：预览是什么样，保存就是什么样。
2. **算子复用**：无需为拍照单独编写 CPU 算法，极大降低开发成本。
3. **性能释放**：利用 GPU 并行计算，即使处理 4K 照片也能在 200ms 内完成。

---

## 4. 演进路线图 (Evolution Roadmap)

> **状态更新（2026-05-02）**：Phase 1 已完成（`OffscreenRenderer` 已落地于 `beauty-engine/impl`），详见 `ROADMAP-ADR-002.md`（已合并到本文档）。

### Phase 1: 零拷贝管线迁移 (1-4 周) ✅ 已完成
**核心动作：消除 4 处拷贝。**
- [x] **OES 接入**：修改 GPUPixel C++ 层接收 `GL_TEXTURE_EXTERNAL_OES`，直接在 GPU 内部进行采样。
- [x] **Shader 旋转**：将旋转逻辑移至 GPU Vertex Shader，通过顶点坐标变换实现，像素数据在物理内存中不动。
- [x] **OffscreenRenderer 框架**：`bitmapToTexture()` / `readPixelsToBitmap()` / FBO 封装已落地。
- [x] **PBO 异步读取**：双缓冲 PBO 轮转实现。
- [x] **4K 分块处理**：避免 OOM 的 `TileProcessor` 已落地。

**验收标准**：
- [x] 独立单元测试通过率 100%
- [x] 连续处理 100 张 1080p 图片无崩溃/泄漏
- [x] 性能指标达到目标 80%

### Phase 2: Shader 迁移与算子升级 (4-8 周) ⏳ 规划中
- **Shader 提取**：将 `BeautyPreviewView` 内嵌 Shader 提取为独立、可复用的 Shader 类。
- **ShaderChain 动态组合**：支持磨皮/美白/瘦脸/大眼/唇色/腮红的链式渲染。
- **引导滤波落地**：提取 GPUPixel 的引导滤波实现，作为 PicMe 通用磨皮算子。
- **3D LUT 引擎化**：建立统一的 LUT 资源加载协议，支持 32x32/64x64 规格。

### Phase 3: 拍照路径切换 (8-10 周) ⏳ 待启动
- **双路径并存**：`GpuBeautyProcessor` 调用 `OffscreenRenderer`，添加 `useGpuPath` Feature Flag。
- **A/B 测试**：50 组预览/拍照对比，效果一致性 > 95%。
- **灰度发布**：50% 用户使用 GPU 路径，监控崩溃率 < 0.1%。
- **全量切换**：100% GPU 路径，废弃 CPU 路径。

### Phase 4: 库化准备 (11-15 周) 🔭 长期目标
- **模块抽离**：创建 `beauty-core` 模块（纯 Kotlin 接口）。
- **API 设计**：语义化版本规范（SemVer），向后兼容策略。
- **内部发布**：`beauty-core` v0.1.0 (alpha) + `beauty-engine` v0.1.0 (alpha)。
- **PicMe 接入验证**：完整接入指南。

---

## 5. 资源需求与风险

### 人力
| 角色 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| RD (Android/GL) | 1.0 FTE | 1.0 FTE | 0.8 FTE | 0.6 FTE |
| RD (Kotlin) | 0.2 FTE | 0.2 FTE | 0.2 FTE | 0.8 FTE |
| QA | 0.2 FTE | 0.3 FTE | 0.5 FTE | 0.3 FTE |
| CR | 0.1 FTE | 0.2 FTE | 0.2 FTE | 0.2 FTE |

### 风险与应对
| 风险 | 概率 | 应对策略 |
|------|------|---------|
| PBO 兼容性（旧设备） | 中 | 检测 API 级别，回退到 glReadPixels |
| 4K 内存 OOM | 中 | 强制分块处理，单块 2048x2048 |
| Shader 精度问题 | 低 | 浮点精度统一为 highp |
| 开发延期 | 低 | 每 Phase 可独立交付，允许延期 1 周 |

---

*路线图版本: 2.0*  
*更新日期: 2026-05-02（合并 ROADMAP-ADR-002.md，标记 Phase 1 完成）*
