# Settings 模块技术实现规范 (Settings Technical Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：确保 PicMe 的设置系统使用 DataStore 安全、高效地存储用户配置。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[LOCAL] 零云端存储**：所有设置本地化，不申请网络权限
- **[I18N] 多语言支持**：英文、简体中文、繁体中文三语齐全
- **[PRIVACY] 权限透明**：明确告知用途，提供降级方案
- **[TYPE_SAFE] 类型安全**：使用 DataStore 和 Sealed Class 确保编译期检查

## 2. 技术实现规范 (Technical Implementation)

### 2.1 DataStore 定义规范
- **Preference Key 命名**：使用 `intPreferencesKey`、`booleanPreferencesKey` 等类型安全 API
- **Flow 暴露数据**：通过 `userPreferences.data.map{}`将偏好设置转换为 Flow 流
- **Repository 封装**：所有读写操作必须经过 Repository 层，ViewModel 不直接调用 DataStore

### 1.2 设置项数据模型
**使用 Sealed Class 区分三种设置类型**：
- **SwitchSetting**：开关型设置（如水印开关），包含标题、描述、默认值
- **SliderSetting**：滑块型设置（如美颜程度），包含最小值、最大值、默认值、步长
- **SelectorSetting**：选择器型设置（如滤镜风格），包含选项列表、默认索引

### 2.2 设置 UI 组件实现

### 2.1 通用设置项布局
**使用 when 表达式根据类型渲染不同组件**：
- SwitchSetting → 调用 SwitchSettingItem，显示标题和开关
- SliderSetting → 调用 SliderSettingItem，显示滑块和数值
- SelectorSetting → 调用 SelectorSettingItem，显示下拉选择器

### 2.2 实时预览支持
**设置变更立即生效机制**：
1. ViewModel 使用 `combine`操作符合并多个 Flow（美颜程度、水印开关、滤镜强度）
2. 通过 `stateIn`将合并后的 Flow 转换为 StateFlow，自动通知 UI 更新
3. 用户修改设置时调用 Repository 的 suspend 函数，无需手动刷新 UI

### 2.3 权限管理实现

### 3.1 Android 13+ 权限适配
**动态申请策略**：
- **Android 13+ (API 33+)**：使用 `READ_MEDIA_IMAGES`替代废弃的`READ_EXTERNAL_STORAGE`
- **Android 12 及以下**：继续使用 `READ_EXTERNAL_STORAGE`和`WRITE_EXTERNAL_STORAGE`
- **相机权限**：所有版本统一使用 `CAMERA` 权限

### 3.2 权限降级策略
**根据权限拒绝情况提供不同降级方案**：
- **相机权限被拒**：显示说明对话框，引导用户前往系统设置开启
- **存储权限被拒**：进入受限模式，允许拍照但不允许保存（或保存到应用私有目录）
- **部分权限被拒**：仅启用已授权功能，未授权功能隐藏或禁用

### 2.4 I18N 多语言支持

### 4.1 字符串资源组织规范
**文件结构**：
- `res/values/strings.xml` - 英文（默认）
- `res/values-zh-rCN/strings.xml` - 简体中文
- `res/values-zh-rTW/strings.xml` - 繁体中文

**命名规则**：采用 `[feature]_[description]`格式，如`settings_title`、`ocr_copy_success`

**更新流程**：新增功能时必须同步更新三个语言文件，确保文案对齐

### 4.2 动态语言切换（可选功能）
**实现方式**：通过 `Context.createConfigurationContext()` 创建带特定 Locale 的 Context，支持应用内独立于系统设置的语言切换

## 3. Agent 执行规约 (Execution Rules)

- **DataStore 操作**：必须使用协程（edit 是 suspend 函数）
- **Flow 生命周期**：正确处理 Flow 的生命周期，使用 `stateIn` 管理
- **权限请求时机**：首次使用时再请求，避免启动时全部请求
- **默认值设置**：所有设置项必须有默认值，避免首次读取为 null
- **多语言同步**：新增功能时必须同步更新三个语言文件
- **深色模式**：必须使用 MaterialTheme.colorScheme 支持深色模式
- **实时生效**：设置变更通过 Flow 自动通知订阅者，无需手动刷新
- **选择器设置项**：人脸检测引擎等枚举配置应以 `SelectorSetting` / 选项 Chip 暴露，默认值必须可回退到 `AUTO`，并通过 Repository 持久化到 DataStore。

## 4. 常见陷阱检查清单 (Checklist)

- [ ] DataStore 操作是否使用了协程？（edit 是 suspend 函数）
- [ ] 是否正确处理了 Flow 的生命周期？（使用 `stateIn`）
- [ ] 权限请求是否在合适的时机？（首次使用时再请求）
- [ ] 设置项是否有默认值？（避免首次读取为 null）
- [ ] 多语言文案是否同步更新？（新增功能时检查三个语言文件）
- [ ] 是否支持深色模式？（使用 MaterialTheme.colorScheme）
- [ ] 设置变更是否实时生效？（通过 Flow 自动通知订阅者）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 零云端 → 所有设置本地存储，不申请网络权限
- ✅ 多语言支持 → 英文、简体中文、繁体中文三语齐全
- ✅ 权限透明 → 明确告知用途，提供降级方案

**技术决策记录**：
- 选择 DataStore 而非 SharedPreferences：类型安全、支持 Flow、无主线程阻塞
- 使用 Sealed Class 表示设置项：编译器检查 exhaustive when，避免遗漏
- Combine 多个 Flow：减少订阅次数，提升性能
