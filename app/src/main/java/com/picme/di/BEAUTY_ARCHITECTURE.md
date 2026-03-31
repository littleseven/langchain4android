# 美颜架构设计：双轨策略实施指南

## 1. 架构概览

### 1.1 设计原则

本架构遵循 **SOLID 原则**，实现短期目标与中长期目标的无缝切换：

- **单一职责**：每个类只负责一项功能
- **开闭原则**：对扩展开放，对修改关闭
- **依赖倒置**：依赖抽象接口，而非具体实现

### 1.2 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                        │
│              CameraScreen / CameraViewModel                  │
└────────────────────────┬────────────────────────────────────┘
                         │ 依赖
                         ↓
┌─────────────────────────────────────────────────────────────┐
│                   Domain Layer (接口)                        │
│            BeautyPreviewProvider (Interface)                 │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ + createPreviewSurface(): Surface                    │   │
│  │ + updateFilters(settings: BeautySettings): Unit      │   │
│  │ + release(): Unit                                    │   │
│  │ + isReady(): Boolean                                 │   │
│  └──────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │ 实现
            ┌────────────┴────────────┐
            │                         │
            ↓                         ↓
┌───────────────────────┐   ┌────────────────────────┐
│ PixelFree 实现（短期）│   │ R 计划实现（中长期）    │
├───────────────────────┤   ├────────────────────────┤
│ PixelFreeBeauty       │   │ RPlanBeauty            │
│ PreviewProvider       │   │ PreviewProvider        │
│                       │   │                        │
│ ✅ 快速上线           │   │ ⏳ 待实施（预留接口）   │
│ ✅ SDK 集成           │   │ ✅ 架构已设计          │
│ ✅ 实时预览           │   │ ✅ 可自由切换          │
└───────────────────────┘   └────────────────────────┘
            │                         │
            └────────────┬────────────┘
                         │ 创建
                         ↓
┌─────────────────────────────────────────────────────────────┐
│                 DI Layer (依赖注入)                          │
│          BeautyPreviewProviderFactory                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ enum BeautyStrategy {                                │   │
│  │     PIXEL_FREE,  // 短期方案                         │   │
│  │     R_PLAN       // 中长期方案                       │   │
│  │ }                                                    │   │
│  │                                                      │   │
│  │ fun create(strategy: BeautyStrategy): Provider      │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 核心接口：BeautyPreviewProvider

### 2.1 接口定义

```kotlin
interface BeautyPreviewProvider {
    /** 创建预览 Surface（供 CameraX 使用）*/
    fun createPreviewSurface(): Surface

    /** 更新美颜滤镜参数 */
    fun updateFilters(settings: BeautySettings)

    /** 释放资源 */
    fun release()

    /** 是否已准备好 */
    fun isReady(): Boolean
}
```

### 2.2 设计优势

1. **上层无感知**：CameraScreen 只依赖接口，不关心具体实现
2. **易于测试**：可以Mock接口进行单元测试
3. **自由切换**：只需修改 Factory 配置，无需改动业务代码

---

## 3. 实现策略：双轨并行

### 3.1 短期方案：PixelFreeEffects SDK

**文件**：`core/image/pixelfree/PixelFreeBeautyPreviewProvider.kt`

**特点**：
- ✅ **快速上线**：1-2 周完成集成
- ✅ **功能完整**：支持磨皮、美白、瘦脸、大眼等
- ✅ **实时预览**：60fps 流畅度
- ⚠️ **依赖第三方**：需要 SDK 授权

**核心实现**：
```kotlin
class PixelFreeBeautyPreviewProvider(context: Context) : BeautyPreviewProvider {
    private var pixelFreeView: PixelFreeGLSurfaceView? = null

    override fun createPreviewSurface(): Surface {
        return pixelFreeView!!.getSurfaceForCamera()
    }

    override fun updateFilters(settings: BeautySettings) {
        // 映射参数到 PixelFree SDK
        pixelFreeView?.setBeautyParam(
            PFBeautyFilterType.PFBeautyFilterTypeFaceBlurStrength,
            settings.smoothing / 100f
        )
    }
}
```

**技术文档**：`docs/PIXELFREE_INTEGRATION.md`

### 3.2 中长期方案：R 计划自主研发

**文件**：`core/image/rplan/RPlanBeautyPreviewProvider.kt`

**特点**：
- ✅ **完全自主**：技术可控，零授权成本
- ✅ **定制化**：可根据产品需求定制特殊效果
- ✅ **技术积累**：团队核心能力提升
- ⏳ **周期较长**：预计 2-3 个月

**当前状态**：预留接口框架，待实施

**核心实现**（预留）：
```kotlin
class RPlanBeautyPreviewProvider(context: Context) : BeautyPreviewProvider {
    // [TODO] 中长期实施
    // private var beautyPreviewView: BeautyPreviewView? = null
    // private var renderer: CameraPreviewRenderer? = null

    override fun createPreviewSurface(): Surface {
        throw NotImplementedError("R Plan is not implemented yet")
        // [TODO] 返回自研渲染器的 Surface
    }

    override fun updateFilters(settings: BeautySettings) {
        // [TODO] 更新自研 Shader 参数
    }
}
```

**技术文档**：`docs/R_PLAN_GUIDE.md`

---

## 4. 切换机制：BeautyPreviewProviderFactory

### 4.1 Factory 配置

**文件**：`di/BeautyPreviewProviderFactory.kt`

**核心代码**：
```kotlin
object BeautyPreviewProviderFactory {
    enum class BeautyStrategy {
        PIXEL_FREE,  // 短期方案
        R_PLAN       // 中长期方案
    }

    // 配置默认策略（一键切换）
    private const val DEFAULT_STRATEGY = "PIXEL_FREE"

    fun create(context: Context, strategy: BeautyStrategy? = null): BeautyPreviewProvider {
        return when (strategy ?: getCurrentStrategy()) {
            BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyPreviewProvider(context)
            BeautyStrategy.R_PLAN -> RPlanBeautyPreviewProvider(context)
        }
    }
}
```

### 4.2 切换方式

#### 方式 1：用户设置切换（推荐）

**入口**：设置 → 美颜引擎

**实现**：
- `UserPreferencesRepository` 持久化保存用户选择
- `SettingsScreen` 提供 RadioButton 切换界面
- `BeautyPreviewProviderFactory` 自动读取配置

```kotlin
// 1. 用户在设置界面选择策略（自动保存到 DataStore）
SettingsSection(title = "美颜引擎") {
    BeautyStrategySelection(
        currentStrategy = beautyStrategy,
        onStrategySelected = { viewModel.setBeautyStrategy(it) }
    )
}

// 2. Factory 自动从设置中读取
fun create(context: Context): BeautyPreviewProvider {
    val repository = UserPreferencesRepository(context)
    val userStrategy = repository.getBeautyStrategyBlocking()

    return when (userStrategy) {
        BeautyStrategy.PIXEL_FREE -> PixelFreeBeautyPreviewProvider(context)
        BeautyStrategy.R_PLAN -> RPlanBeautyPreviewProvider(context)
    }
}
```

**优势**：
- ✅ 用户自主控制
- ✅ 持久化保存
- ✅ 实时生效
- ✅ 无需重新编译

#### 方式 2：动态切换（A/B 测试）
```kotlin
// 在特定场景强制使用某个策略
val provider = BeautyPreviewProviderFactory.create(
    context,
    strategy = BeautyPreviewProviderFactory.BeautyStrategy.R_PLAN
)
```

#### 方式 3：降级策略（容错机制）
```kotlin
val provider = try {
    BeautyPreviewProviderFactory.create(context, BeautyStrategy.R_PLAN)
} catch (e: NotImplementedError) {
    Log.w(TAG, "R Plan not ready, fallback to PixelFree")
    BeautyPreviewProviderFactory.create(context, BeautyStrategy.PIXEL_FREE)
}
```

---

## 5. 使用示例

### 5.1 在 ViewModel 中使用

```kotlin
class CameraViewModel(
    private val context: Context
) : ViewModel() {

    // 创建美颜预览提供者（依赖接口，不依赖具体实现）
    private val beautyProvider = BeautyPreviewProviderFactory.create(context)

    fun setupCamera(cameraProvider: ProcessCameraProvider) {
        // 1. 创建预览 Surface
        val previewSurface = beautyProvider.createPreviewSurface()

        // 2. 配置 CameraX Preview
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider { request ->
                request.provideSurface(previewSurface, ...)
            }
        }

        // 3. 绑定到生命周期
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
    }

    fun updateBeautySettings(settings: BeautySettings) {
        // 更新美颜参数（无论哪种实现，调用方式一致）
        beautyProvider.updateFilters(settings)
    }

    override fun onCleared() {
        beautyProvider.release()
    }
}
```

### 5.2 在 Compose UI 中使用

```kotlin
@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    var smoothing by remember { mutableStateOf(35f) }
    var whitening by remember { mutableStateOf(25f) }

    // 美颜参数调节 UI
    Column {
        Slider(
            value = smoothing,
            onValueChange = { value ->
                smoothing = value
                viewModel.updateBeautySettings(
                    BeautySettings(smoothing = value, whitening = whitening)
                )
            },
            valueRange = 0f..100f
        )

        Slider(
            value = whitening,
            onValueChange = { value ->
                whitening = value
                viewModel.updateBeautySettings(
                    BeautySettings(smoothing = smoothing, whitening = value)
                )
            },
            valueRange = 0f..100f
        )
    }
}
```

---

## 6. 实施时间线

### Phase 1：短期方案（1-2 周）✅ 当前阶段
- [x] 定义 BeautyPreviewProvider 接口
- [x] 实现 PixelFreeBeautyPreviewProvider
- [x] 创建 BeautyPreviewProviderFactory
- [x] 集成到 CameraScreen
- [ ] 完成功能测试
- [ ] 发布到生产环境

### Phase 2：数据积累（1-2 月）
- [ ] 收集性能数据（帧率、延迟、内存）
- [ ] 收集用户反馈（美颜效果、参数范围）
- [ ] 研究 PixelFree SDK 源码（借鉴算法）
- [ ] 优化 Shader 代码

### Phase 3：中长期方案（2-3 月）
- [ ] 实现 RPlanBeautyPreviewProvider
- [ ] 完成 EGL 上下文管理
- [ ] 实现自研美颜 Shader
- [ ] 性能优化（60fps 稳定）
- [ ] 灰度测试（小范围切换）
- [ ] 全量切换到 R 计划

---

## 7. 风险与应对

### 7.1 短期风险：PixelFree SDK 问题

**风险**：
- SDK 授权到期
- SDK 性能不稳定
- SDK 功能不满足需求

**应对**：
- 尽快实施 R 计划
- 保留 SDK 作为备选方案
- 降级策略：SDK 失败时关闭美颜预览，仅拍照后处理

### 7.2 中长期风险：R 计划实施失败

**风险**：
- 技术难度超预期
- 性能无法达到 60fps
- 设备兼容性问题

**应对**：
- 继续使用 PixelFree SDK
- 降级为离线美颜（仅拍照后处理）
- 分阶段实施（先实现基础功能）

---

## 8. 参考文档

### 8.1 产品文档
- `PRODUCT.md` - 产品需求规格说明书
- `docs/FEATURES.md` - 功能交互规范

### 8.2 技术文档
- `docs/CAMERA_PREVIEW_GUIDE.md` - 相机预览完整指南
- `docs/PIXELFREE_INTEGRATION.md` - PixelFree SDK 集成文档
- `docs/R_PLAN_GUIDE.md` - R 计划实时美颜完整指南

### 8.3 代码规范
- `AGENTS.md` - AI Agent 操作规范
- `di/AGENTS.md` - 依赖注入规范

---

## 9. 总结

### 9.1 架构优势

✅ **灵活切换**：修改一行配置即可切换实现
✅ **风险可控**：短期方案快速验证，中长期方案稳步推进
✅ **代码整洁**：依赖抽象接口，符合 SOLID 原则
✅ **易于测试**：接口便于 Mock，提升测试覆盖率
✅ **团队成长**：积累核心技术能力

### 9.2 关键成功因素

1. **严格遵守接口契约**：所有实现必须符合 BeautyPreviewProvider 规范
2. **充分文档化**：每次技术决策都更新文档
3. **性能监控**：实时监控帧率、延迟、内存
4. **用户反馈**：快速迭代优化美颜效果
5. **降级策略**：任何方案失败时都有备选方案

---

**[RD] 设计者**：PicMe 全栈工程师团队
**最后更新**：2026-03-31
**版本**：1.0

