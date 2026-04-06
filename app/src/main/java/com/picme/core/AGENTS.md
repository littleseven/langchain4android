# Core 模块技术实现规范 (Core Infrastructure)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：提供 PicMe 的核心组件（DesignSystem、图像处理、工具类）统一、高效的基础能力。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[DESIGN_CONSISTENCY] 设计一致性**：所有 UI 组件必须遵循 HyperOS 视觉风格（大圆角、毛玻璃、流体动效）
- **[PERF] 性能约束**：滤镜处理时间 < 200ms，图片加载流畅无卡顿
- **[I18N] 国际化支持**：所有用户可见字符串必须使用 StringResource，支持多语言

## 2. 技术实现规范 (Technical Implementation)

### 2.1 Design System 实现

**主题系统规范**：
- **PicMeTheme 颜色方案**：
  - 深色模式：primary 使用科技蓝 (#00E5FF)，surface 使用深灰 (#1E1E1E)
  - 浅色模式：对应调整明度，保持对比度符合 WCAG 标准
- **毛玻璃效果**：通过 `Modifier.blur(20.dp)`配合半透明白色背景，圆角统一为 28dp

**通用组件库**：
- **BlurCard 组件**：统一卡片样式，圆角 24dp，支持阴影高度可调，背景色使用 surface.copy(alpha = 0.8f)
- **FluidButton 组件**：带按压缩放反馈的按钮，按下时缩放至 0.95，使用 spring 动画（stiffness: Medium）

### 2.2 图像处理核心

**滤镜基类设计**：
- **FilterBase 抽象类要求**：
  - 必须定义 `name`（StringResource）和`thumbnail`（Drawable）
  - 必须实现 `apply(bitmap: Bitmap)` suspend 方法
  - 性能约束：单个滤镜处理时间 < 200ms
- **LEICA_CLASSIC 示例**：使用 ColorMatrix 调整，饱和度设为 0.9，对比度微调 +0.05

**图片加载优化**：
- **Coil 全局配置**：
  - 内存缓存：占可用内存 25%，使用 MemoryCache.Builder
  - 磁盘缓存：占磁盘空间 2%，目录为 `cache/image_cache`
  - 忽略 HTTP 缓存头：本地应用设置`respectCacheHeaders(false)`

### 2.3 工具类与扩展函数

**Logger 统一日志规范**：
- **结构化 Tag 设计**：基础 Tag 为 "PicMe"，模块 Tag 格式为`PicMe:[Module]`
- **日志缓冲机制**：维护最多 500 条内存缓存（FIFO），支持调试浮窗实时检索
- **使用示例**：
  - `Logger.d("Camera", "Capture triggered")` - 记录相机快门触发
  - `Logger.e("Gallery", "Failed to load image", exception)` - 记录图片加载失败

**Kotlin 扩展函数**：
- **URI 扩展**：`getMetadata(context)` - 从 URI 读取元数据（拍摄日期、宽度等）
- **Bitmap 扩展**：`applyColorFilter(matrix)` - 应用颜色矩阵滤镜，在 Dispatchers.Default 线程执行
- **Flow 扩展**：`debounceIf(condition, duration)` - 条件防抖，仅在满足条件时启用 debounce

### 2.4 依赖注入配置

**Hilt 模块定义规范**：
- **AppModule 提供的全局单例**：
  - **Database**：Room 数据库，使用 `@Singleton`标注
  - **ImageLoader**：Coil 全局图片加载器，配置内存和磁盘缓存
  - **OcrEngine**：离线 OCR 引擎（ML Kit）

## 3. Agent 执行规约 (Execution Rules)

- **DesignSystem 组件**：必须支持深色模式，使用 colorScheme 动态适配
- **滤镜处理**：必须在后台线程执行（Dispatchers.Default），避免阻塞 UI
- **Bitmap 管理**：必须及时回收（recycle() 或在 using 块中），避免 OOM
- **Logger 使用**：必须使用正确的 Tag 格式（PicMe:Module），便于检索
- **扩展函数**：必须使用明确的前缀，避免命名冲突
- **Hilt 依赖**：必须标注正确的生命周期（@Singleton / @ViewModelScoped）
- **Coil 缓存**：大小必须合理（避免 OOM），占可用内存 25% 以内

## 4. 常见陷阱检查清单 (Checklist)

- [ ] DesignSystem 组件是否支持深色模式？（使用 colorScheme）
- [ ] 滤镜处理是否在后台线程？（Dispatchers.Default）
- [ ] Bitmap 是否正确回收？（recycle() 或在 using 块中）
- [ ] Logger 是否使用了正确的 Tag 格式？（PicMe:Module）
- [ ] 扩展函数是否避免了命名冲突？（使用明确的前缀）
- [ ] Hilt 依赖是否标注了正确的生命周期？（@Singleton / @ViewModelScoped）
- [ ] Coil 缓存大小是否合理？（避免 OOM）
- [ ] 所有用户可见字符串是否已提取到 strings.xml？（支持 I18N）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ HyperOS 视觉风格 → 大圆角 28dp+、毛玻璃 blur(20.dp)、流体动画
- ✅ 滤镜处理 < 200ms → 使用 ColorMatrix 或 GPU Image，后台线程执行
- ✅ 120fps 滚动 → Coil 缓存优化，懒加载大图

**技术决策记录**：
- 选择 Coil 而非 Glide：原生支持 Compose（`AsyncImage` API 简洁）、支持 Kotlin 协程、包体积更小
- 选择 RenderScript 替代方案：RenderScript 已废弃，使用 GPU Image 或自研矩阵运算
- 日志系统设计：结构化 Tag 便于 grep 检索，内存缓冲支持调试浮窗实时查看
