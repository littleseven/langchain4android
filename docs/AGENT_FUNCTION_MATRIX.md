# PicMe Agent 功能覆盖矩阵

> 本文档梳理当前 Agent 可控功能与待接入功能，作为架构改进的输入。

---

## 当前已接入 Agent 的功能（✅）

| 功能域 | 具体功能 | 命令类型 | Capability | 状态 |
|--------|----------|----------|------------|------|
| **相机控制** | 拍照 | `CapturePhoto` | CameraCapability | ✅ |
| | 开始/停止录像 | `ToggleRecording` | CameraCapability | ✅ |
| | 翻转摄像头 | `FlipCamera` | CameraCapability | ✅ |
| | 变焦调节 | `AdjustZoom` | CameraCapability | ✅ |
| | 曝光调节 | `AdjustExposure` | CameraCapability | ✅ |
| | 切换拍摄模式 | `SwitchMode` | CameraCapability | ✅ |
| **美颜** | 磨皮/美白调节 | `AdjustBeauty` | CameraCapability | ✅ |
| | 瘦脸/大眼调节 | `AdjustBeauty` | CameraCapability | ✅ |
| | 唇色/腮红调节 | `AdjustBeauty` | CameraCapability | ✅ |
| **滤镜/风格** | 切换滤镜 | `SwitchFilter` | CameraCapability | ✅ |
| | 切换风格特效 | `SwitchStyle` | CameraCapability | ✅ |
| | 切换场景模式 | `SwitchScene` | CameraCapability | ✅ |
| | 切换画幅比例 | `SwitchRatio` | CameraCapability | ✅ |
| **对话** | 文本回复/聊天 | `TextReply` | CameraCapability | ✅ |

---

## 未接入 Agent 的功能（❌）

### 1. Gallery 相册功能

| 功能 | 用户意图示例 | 优先级 |
|------|-------------|--------|
| 查看最近照片 | "看看刚拍的照片" | P0 |
| 删除照片 | "删除这张" / "清空最近" | P0 |
| 分享照片 | "分享这张照片到微信" | P1 |
| 收藏照片 | "收藏这张照片" | P2 |
| 照片搜索 | "找上周拍的照片" | P2 |
| 批量选择 | "选择前5张" | P2 |

### 2. 设置功能

| 功能 | 用户意图示例 | 优先级 |
|------|-------------|--------|
| 切换主题 | "切换到深色模式" | P1 |
| 切换语言 | "切换成英文" | P1 |
| 下载模型 | "下载 Qwen3 模型" | P1 |
| 切换人脸检测引擎 | "换成人脸检测引擎2" | P2 |
| 调整检测频率 | "提高检测频率" | P2 |
| 开启/关闭调试模式 | "打开调试信息" | P2 |

### 3. 照片编辑功能

| 功能 | 用户意图示例 | 优先级 |
|------|-------------|--------|
| 进入编辑模式 | "编辑这张照片" | P1 |
| 应用美颜 | "给这张照片磨皮" | P1 |
| 保存编辑 | "保存修改" | P1 |
| 撤销/重做 | "撤销上一步" | P2 |

### 4. 导航/系统功能

| 功能 | 用户意图示例 | 优先级 |
|------|-------------|--------|
| 切换页面 | "去相册" / "打开设置" | P0 |
| 返回上一页 | "返回" / "回到相机" | P0 |
| 退出应用 | "退出" | P2 |

---

## 架构问题诊断

### 当前架构问题

1. **单一场景限制**
   - AgentContext 仅支持 CAMERA/GALLERY/PHOTO_EDIT 三个场景
   - 没有 SETTINGS/EDITOR 等场景支持
   - 场景切换需要手动设置，Agent 无法自动感知

2. **Capability 单一**
   - 目前只有 CameraCapability
   - Gallery、Settings、Navigation 等功能域无对应 Capability
   - 所有回调都注册在 CameraCapability，耦合严重

3. **System Prompt 硬编码**
   - AgentOrchestrator 中硬编码 system prompt
   - 不同场景需要不同的 prompt，目前无法动态切换
   - 新增命令需要修改核心类

4. **命令解析局限**
   - 仅支持相机相关命令解析
   - Gallery/Settings 等域的命令无解析逻辑

5. **UI 与 Agent 绑定**
   - AiAgentPanel 仅在 CameraScreen 中
   - Gallery/Settings 等页面无 Agent 入口

### 改进方向

1. **多 Capability 架构**
   - 新增 GalleryCapability、SettingsCapability、NavigationCapability
   - 每个 Capability 自包含命令处理和执行逻辑

2. **动态场景感知**
   - Agent 自动感知当前页面（通过 Navigation 监听）
   - 根据场景动态加载对应的 system prompt

3. **全局 Agent 入口**
   - Agent Panel 作为全局组件，可在任意页面唤起
   - 支持悬浮球或手势触发

4. **分层 System Prompt**
   - 基础 prompt（通用能力）
   - 场景 prompt（特定页面能力）
   - 动态组合生成完整 prompt
