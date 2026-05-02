# ADR-001: 大美丽与 GPUPixel 分层架构重构

**状态**: 已接受 (Accepted)  
**日期**: 2026-04-17  
**决策**: RD  
**PM Review**: 已完成

---

## 1. 背景与问题陈述

### 重构前架构
```
App Layer → 大美丽模块 (混合业务逻辑+GPU实现) → GPUPixel (直接引用)
```

**核心问题**:
1. **层次越界**: 大美丽直接包含 GPU 渲染代码，违反 Clean Architecture
2. **强耦合**: App 层通过大美丽间接依赖 GPUPixel 内部类，无法 Mock 测试
3. **复用阻塞**: GPUPixel 被业务逻辑污染，无法独立作为视觉能力库输出
4. **性能风险**: EGL Context 管理分散，线程模型混乱

---

## 2. 决策目标架构

```
┌─────────────────────────────────────────────────────────┐
│                    App Layer                            │
│              ↓ 依赖 beauty-engine:api                   │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Domain Layer: beauty-engine:api                        │
│  ├─ BeautyEngine (Interface)                          │
│  ├─ FilterType / BeautyParams                         │
│  └─ BeautyCallback                                    │
└────────────────────┬──────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────┐
│  Data Layer: beauty-engine:egl + gpupixel             │
│  ├─ BeautyEngineImpl                                  │
│  ├─ GPUPixelAdapter (EGL/GL 封装)                    │
│  └─ BigBeautyEngine (自研 OpenGL ES 管线)            │
└────────────────────┬──────────────────────────────────┘
                     │
    ┌────────────────┴────────────────┐
    ▼                                 ▼
┌──────────────┐            ┌──────────────────┐
│  BIG_BEAUTY  │            │    GPUPIXEL      │
│ (自研引擎)   │            │ (Apache 2.0)     │
└──────────────┘            └──────────────────┘
```

---

## 3. 关键决策

| 决策项 | 方案 |
|--------|------|
| **分层策略** | Domain(api) / Data(egl+gpupixel) / External 三层分离 |
| **依赖方向** | App → api → egl/gpupixel → GPUPixel (单向) |
| **接口定义** | `BeautyEngine` 接口类，对外唯一出口 |
| **GPUPixel封装** | Adapter模式，`GPUPixelAdapter` 统一封装所有GL调用；自研引擎走 `egl/` 包 |
| **线程模型** | HandlerThread GL线程，egl层管理独立EGLContext |

---

## 4. 技术实现

### 4.1 模块结构
```
beauty-engine/
├── api/                    # Domain Layer - 公开接口
│   └── BeautyEngine.kt     # 核心接口
├── egl/                    # Data Layer - 自研引擎 GL 渲染实现
│   ├── BeautyEngineImpl.kt # 接口实现
│   ├── BigBeautyEngine.kt  # 自研 OpenGL ES 管线
│   └── EGLContextManager.kt # EGL 管理
└── gpupixel/               # Data Layer - GPUPixel 适配实现
    └── GPUPixelAdapter.kt  # GPUPixel C++ JNI 适配
```

### 4.2 依赖规则 (Gradle)
```groovy
// App 层（只允许依赖 api）
dependencies {
    implementation project(':beauty-engine:api')
    // 禁止: implementation project(':beauty-engine:egl')
    // 禁止: implementation project(':beauty-engine:gpupixel')
}

// egl 模块（内部实现，可依赖 GPUPixel）
dependencies {
    api project(':beauty-engine:api')
    // GPUPixel 为内部实现细节，不传递
}
```

### 4.3 ArchUnit 依赖检查
```kotlin
// 规则: App 层严禁直接依赖 GPUPixel
noClasses()
    .that().resideInAPackage("..app..")
    .should().dependOnClassesThat()
    .resideInAPackage("..gpupixel..")
```

---

## 5. 后果分析

### 正面影响
- ✅ 接口契约稳定，支持版本化管理
- ✅ 单元测试可 Mock BeautyEngine 接口
- ✅ GPUPixel 完全隔离，可替换其他 GPU 库
- ✅ GL 线程独立，不阻塞主线程
- ✅ 4K 大图分块处理，无 OOM 风险

### 负面影响
- ⚠️ 新增 Adapter 层带来 ~5ms 延迟开销
- ⚠️ EGL Context 初始化约 50-100ms
- ⚠️ Shader 编译首次加载约 100-200ms

---

## 6. 状态

| 阶段 | 状态 | 日期 |
|------|------|------|
| 接口提取 (Phase 1) | ✅ 完成 | 2026-04-17 |
| 实现迁移 (Phase 2) | ✅ 完成 | 2026-04-17 |
| App层适配 (Phase 3) | ✅ 完成 | 2026-04-17 |
| 编译验证 | ✅ 通过 | 2026-04-17 |
| 安装运行 | ✅ 成功 | 2026-04-17 |

---

## 7. 相关文档

- `README.md` - 项目总览与架构图
- `beauty-engine/AGENTS.md` - 模块详细规范
