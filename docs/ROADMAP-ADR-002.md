# ADR-002 实施路线图: OpenGL 离屏渲染统一美颜管线

**文档**: [ADR-002-opengl-offscreen-unified-pipeline.md](../ADR-002-opengl-offscreen-unified-pipeline.md)  
**状态**: Phase 1 准备中  
**预计总工期**: 11-15 周

---

## 里程碑概览

```
Week  1  2  3  4  5  6  7  8  9  10 11 12 13 14 15
      |--Phase 1--|
                  |----Phase 2----|
                                  |--Phase 3--|
                                             |----Phase 4----|

M1: 基础设施就绪 (Week 3)
M2: Shader 迁移完成 (Week 7)
M3: 拍照路径切换 (Week 10)
M4: 库化发布 (Week 15)
```

---

## Phase 1: 基础设施 (Week 1-3)

### 目标
建立 OpenGL 离屏渲染的基础框架，支持 Bitmap → Texture → FBO → Bitmap 的完整链路。

### 任务拆解

#### Week 1: OffscreenRenderer 核心框架
- [ ] `OffscreenRenderer` 类设计与实现
  - [ ] EGL Context 管理与离屏 Surface 创建
  - [ ] FBO (Frame Buffer Object) 封装
  - [ ] `bitmapToTexture()` 实现
  - [ ] `readPixelsToBitmap()` 实现
- [ ] 资源管理器：`TexturePool`, `FBOPool`
- [ ] 单元测试框架（无设备测试）

**输出**: `OffscreenRenderer.kt` + 单元测试

#### Week 2: 性能优化与稳定性
- [ ] PBO (Pixel Buffer Object) 异步读取实现
  - [ ] `glMapBufferRange()` 封装
  - [ ] 双缓冲 PBO 轮转
- [ ] 4K 大图分块处理（避免 OOM）
- [ ] 内存泄漏检测与修复
- [ ] 异常处理：GL 错误恢复机制

**输出**: `PBOReader.kt`, `TileProcessor.kt`

#### Week 3: 集成测试与文档
- [ ] 与现有 `beauty-engine` 模块集成
- [ ] 性能基准测试：
  - [ ] 1080p 处理时间目标: < 500ms
  - [ ] 4K 处理时间目标: < 1500ms
  - [ ] 内存峰值目标: < 100MB
- [ ] 技术文档更新
- [ ] **M1 里程碑评审**

**验收标准**:
- [ ] 独立单元测试通过率 100%
- [ ] 连续处理 100 张 1080p 图片无崩溃/泄漏
- [ ] 性能指标达到目标 80%

---

## Phase 2: Shader 迁移 (Week 4-7)

### 目标
将现有 `BeautyPreviewView` 内嵌的 Shader 代码提取为独立、可复用的 Shader 类。

### Shader 迁移清单

| Shader | 当前位置 | 提取难度 | 负责人 | Week |
|--------|---------|---------|--------|------|
| 双边滤波（磨皮） | BeautyPreviewView | 中 | RD | 4 |
| YUV 美白 | BeautyPreviewView | 低 | RD | 4 |
| FaceWarp（瘦脸） | BeautyPreviewView | 高（需 face data） | RD | 5 |
| 径向放大（大眼） | BeautyPreviewView | 高（需 eye landmarks） | RD | 5 |
| HSV 唇色 | BeautyPreviewView | 中（需 lip mask） | RD | 6 |
| 椭圆腮红 | BeautyPreviewView | 中 | RD | 6 |
| ColorMatrix 滤镜 | 已独立 | 低（复用） | - | - |

### 关键设计

#### Shader 抽象基类
```kotlin
abstract class BeautyShader {
    abstract fun init()
    abstract fun render(inputTexture: Int, outputTexture: Int, params: ShaderParams)
    abstract fun release()
}
```

#### ShaderChain 动态组合
```kotlin
class ShaderChain(
    private val shaders: List<BeautyShader>
) {
    fun render(input: Int, output: Int, params: BeautyParams) {
        var current = input
        shaders.forEachIndexed { index, shader ->
            val out = if (index == shaders.lastIndex) output else getTempTexture()
            shader.render(current, out, params)
            if (index > 0) recycleTempTexture(current)
            current = out
        }
    }
}
```

### 验收标准 (M2 - Week 7)
- [ ] 所有 Shader 提取为独立类
- [ ] ShaderChain 支持动态组合
- [ ] 预览效果与之前 100% 一致（人工对比）
- [ ] 单 Shader 单元测试覆盖率 > 80%

---

## Phase 3: 拍照路径切换 (Week 8-10)

### 目标
将拍照后处理从 CPU (Canvas) 切换到 GPU (OpenGL 离屏渲染)。

### Week 8: 双路径并存
- [ ] `GpuBeautyProcessor` 调用 `OffscreenRenderer`
- [ ] 添加 Feature Flag：`useGpuPath` (默认 false)
- [ ] A/B 测试框架
  - [ ] 效果对比工具（像素级 diff）
  - [ ] 性能对比日志

### Week 9: A/B 测试与调优
- [ ] 内部测试：50 组预览/拍照对比
- [ ] 效果调优：参数微调使一致性 > 95%
- [ ] 性能调优：优化 Shader 执行顺序
- [ ] Bug 修复

### Week 10: 灰度与全量
- [ ] 灰度发布：50% 用户使用 GPU 路径
- [ ] 监控指标：
  - [ ] 崩溃率 < 0.1%
  - [ ] ANR 率 < 0.05%
  - [ ] 平均处理时间 < 300ms (1080p)
- [ ] 全量切换：100% GPU 路径
- [ ] 废弃 CPU 路径代码（标记 `@Deprecated`）
- [ ] **M3 里程碑评审**

### 验收标准 (M3)
- [ ] 拍照效果与预览 99%+ 一致
- [ ] 性能：1080p < 300ms, 4K < 800ms
- [ ] 线上崩溃率 < 0.1%

---

## Phase 4: 库化准备 (Week 11-15)

### 目标
将美颜能力演进为独立发布的视觉能力库。

### Week 11-12: 模块抽离
- [ ] 创建 `beauty-core` 模块（纯 Kotlin 接口）
  - [ ] `BeautyEngine` 接口
  - [ ] `BeautyParams` 数据类
  - [ ] `ProcessCallback` 回调
- [ ] 重构 `beauty-engine` 模块
  - [ ] 仅保留 OpenGL 实现
  - [ ] 依赖 `beauty-core`
- [ ] 依赖方向验证（ArchUnit）

### Week 13: API 设计与版本策略
- [ ] 定义公开 API 集合
- [ ] 语义化版本规范（SemVer）
- [ ] 向后兼容策略
- [ ] 文档：API 参考手册

### Week 14: 内部发布与接入
- [ ] 发布 `beauty-core` v0.1.0 (alpha)
- [ ] 发布 `beauty-engine` v0.1.0 (alpha)
- [ ] PicMe App 接入验证
- [ ] 编写接入指南

### Week 15: 总结与规划
- [ ] 性能/效果最终评估
- [ ] 技术债务清理
- [ ] 下一阶段规划（v0.2.0）
- [ ] **M4 里程碑评审**

### 验收标准 (M4)
- [ ] `beauty-core` 独立发布
- [ ] `beauty-engine` 独立发布
- [ ] PicMe 完全接入新模块
- [ ] 接入文档完整

---

## 资源需求

### 人力
| 角色 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| RD (Android/GL) | 1.0 FTE | 1.0 FTE | 0.8 FTE | 0.6 FTE |
| RD (Kotlin) | 0.2 FTE | 0.2 FTE | 0.2 FTE | 0.8 FTE |
| QA | 0.2 FTE | 0.3 FTE | 0.5 FTE | 0.3 FTE |
| CR | 0.1 FTE | 0.2 FTE | 0.2 FTE | 0.2 FTE |

### 设备
- [ ] 高端测试机（Snapdragon 8 Gen 2/3）
- [ ] 中端测试机（Snapdragon 7 Gen 1）
- [ ] 低端测试机（Snapdragon 6 系列）
- [ ] 特殊设备（华为麒麟、联发科天玑）

### 工具
- [ ] GPU Profiler（Android Studio Profiler）
- [ ] 像素对比工具（Python ImageDiff）
- [ ] 性能基准测试框架

---

## 风险与应对

| 风险 | 概率 | 应对策略 |
|------|------|---------|
| PBO 兼容性（旧设备） | 中 | 检测 API 级别，回退到 glReadPixels |
| 4K 内存 OOM | 中 | 强制分块处理，单块 2048x2048 |
| Shader 精度问题 | 低 | 浮点精度统一为 highp |
| 开发延期 | 低 | 每 Phase 可独立交付，允许延期 1 周 |

---

## 关联文档

- [ADR-002](../ADR-002-opengl-offscreen-unified-pipeline.md) - 完整架构决策
- [beauty-engine/AGENTS.md](../../beauty-engine/AGENTS.md) - 模块规范
- [docs/BIG_BEAUTY_TECH_SPEC.md](../BIG_BEAUTY_TECH_SPEC.md) - 渲染链路技术规范

---

*路线图版本: 1.0*  
*更新日期: 2026-04-17*
