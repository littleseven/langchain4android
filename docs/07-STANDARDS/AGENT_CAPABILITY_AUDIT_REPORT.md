# Agent 命令与 Capability 审计报告

> 生成时间：2026-05-31
> 审计范围：`app/src/main/java/com/picme/domain/agent/` 及相关 Capability 实现

---

## 1. 审计摘要

| 项目 | 结果 |
|------|------|
| 发现问题数 | 6 |
| 已修复数 | 6 |
| 遗留风险 | 0 |
| 状态 | 已闭环 |

---

## 2. 发现问题与修复详情

### 问题 1：CapabilityRegistry 与 CapabilityRegistryV2 重复实现

**风险等级**：中

**描述**：两个类逻辑完全一致（158 行代码逐行相同），仅类名和日志 tag 不同。存在维护分叉风险，修改时容易遗漏同步。

**修复**：删除 `CapabilityRegistryV2.kt`，保留 `CapabilityRegistry.kt` 作为唯一注册表。

**影响文件**：
- `app/src/main/java/com/picme/domain/agent/CapabilityRegistryV2.kt`（已删除）

---

### 问题 2：SceneManager 硬编码 EDITOR 场景但无对应页面路由

**风险等级**：低

**描述**：`SceneManager.Scene.EDITOR` 枚举存在，且 `getCapabilitiesForScene()` 和 `getSceneDescription()` 中均有对应分支，但 `MainActivity.kt` 的 NavHost 中无 `Screen.Editor.route` composable，NavigationCapability 的 Destination 枚举也包含 EDITOR。形成"有场景定义、无页面实现"的悬空状态。

**修复**：
1. 从 `SceneManager.Scene` 枚举中移除 `EDITOR`
2. 注释掉 `getCapabilitiesForScene()` 和 `getSceneDescription()` 中的 EDITOR 分支（保留为预留注释）
3. `NavigationCapability.Destination` 保留 EDITOR（远程解析可能仍引用，但导航时会返回错误提示）

**影响文件**：
- `app/src/main/java/com/picme/domain/agent/model/SceneManager.kt`

---

### 问题 3：编辑命令（ApplyEdit/SaveEdit/UndoEdit/RedoEdit）无 Capability 实现

**风险等级**：中

**描述**：`AgentCommand` 中定义了 4 个编辑命令，`AgentCommandParser` 支持解析这些命令，但不存在对应的 `EditCapability` 类，也没有在任意场景中注册。命令解析后会落入 `CapabilityRegistry.dispatch()` 的 "暂不支持此操作" 分支。

**修复**：
1. 从 `AgentCommand` 中移除 `ApplyEdit`、`SaveEdit`、`UndoEdit`、`RedoEdit` 数据类
2. 从 `AgentCommand.getActionName()` 中移除对应分支
3. 从 `AgentCommandParser.parseCommandByAction()` 中移除编辑命令解析逻辑
4. 从 `AgentCommandParser` 的 `extractJsonFloatMap` 调用处清理（该辅助方法保留，供未来使用）

**影响文件**：
- `app/src/main/java/com/picme/domain/agent/model/AgentCommands.kt`
- `app/src/main/java/com/picme/domain/agent/AgentCommandParser.kt`

---

### 问题 4：GalleryCapability 包含未使用的 onClearSelection 回调

**风险等级**：信息

**描述**：`GalleryCapability` 构造函数包含 `onClearSelection: (() -> Unit)? = null` 参数，但在 `supportedCommands()` 和 `execute()` 中均无对应命令。该回调在 `GalleryAgentIntegration.registerCapabilities()` 中未传入，处于悬空状态。

**修复**：从 `GalleryCapability` 构造函数中移除 `onClearSelection` 参数。

**影响文件**：
- `app/src/main/java/com/picme/domain/agent/capability/GalleryCapability.kt`

---

### 问题 5：text_reply 命令在多个 Capability 中重复声明

**风险等级**：信息

**描述**：`text_reply` 被同时声明在 `CameraCapability`、`GalleryCapability`、`SettingsCapability`、`NavigationCapability` 的 `supportedCommands()` 中。但 `CapabilityRegistry.dispatch()` 在分发前已优先处理 `TextReply`、`Unknown`、`Error` 三类命令，这些命令根本不会进入 `Capability.execute()`。重复声明造成 system prompt 冗余，且各 Capability 的 `execute()` 中均包含不会执行的 `TextReply` 分支。

**修复**：
1. 从所有 Capability 的 `supportedCommands()` 中移除 `"text_reply"`
2. 从所有 Capability 的 `getCommandDescription()` 中移除 `"text_reply"` 描述
3. 从所有 Capability 的 `execute()` 中移除 `is AgentCommand.TextReply` 分支
4. `CapabilityRegistry.dispatch()` 继续保留 `TextReply` 的顶层处理逻辑（作为唯一处理点）

**影响文件**：
- `app/src/main/java/com/picme/domain/agent/capability/CameraCapability.kt`
- `app/src/main/java/com/picme/domain/agent/capability/GalleryCapability.kt`
- `app/src/main/java/com/picme/domain/agent/capability/SettingsCapability.kt`
- `app/src/main/java/com/picme/domain/agent/capability/NavigationCapability.kt`

---

### 问题 6：RemoteOrchestrator 中未闭合的编辑命令解析

**风险等级**：低

**描述**：`RemoteOrchestrator.parseAgentCommand()` 中未包含编辑命令解析，但 `else` 分支返回 `"未知命令: $action"` 的 TextReply，语气生硬。与本地解析兜底文案不统一。

**修复**：将 `else` 分支文案统一为 `"收到，有什么其他需要帮忙的吗？"`。

**影响文件**：
- `app/src/main/java/com/picme/domain/agent/remote/RemoteOrchestrator.kt`

---

## 3. 修复后架构

### 3.1 场景定义（SceneManager.Scene）

```kotlin
enum class Scene {
    CAMERA,      // 相机拍摄页
    GALLERY,     // 相册浏览页
    SETTINGS,    // 设置页
    DEBUG,       // 调试页
    UNKNOWN      // 未知/初始状态
}
```

### 3.2 场景-Capability 映射

| 场景 | 可用 Capability |
|------|----------------|
| CAMERA | `camera`, `navigation` |
| GALLERY | `gallery`, `navigation` |
| SETTINGS | `settings`, `navigation` |
| DEBUG | `navigation` |
| UNKNOWN | `navigation` |

### 3.3 命令统一处理层级

```
AgentCommand
├── TextReply / Unknown / Error  → CapabilityRegistry.dispatch() 顶层处理
└── 其他业务命令                  → 路由到对应 Capability.execute()
```

`text_reply` 不再出现在任何 Capability 的 `supportedCommands()` 中，避免 system prompt 冗余。

---

## 4. LLM 可执行操作汇总（修复后）

### 4.1 相机页（Camera Screen）

| 命令 | 参数 | 说明 |
|------|------|------|
| `adjust_beauty` | smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow | 调整美颜参数 |
| `switch_filter` | filter | 切换滤镜 |
| `switch_style` | style | 切换风格特效 |
| `switch_scene` | scene | 切换场景模式 |
| `switch_ratio` | ratio | 切换画幅比例 |
| `adjust_exposure` | exposure | 调整曝光 |
| `adjust_zoom` | zoom | 调整变焦 |
| `flip_camera` | - | 翻转摄像头 |
| `capture` / `photo` | - | 拍照 |
| `toggle_recording` | - | 开始/停止录像 |
| `switch_mode` | mode | 切换拍摄模式 |

### 4.2 相册页（Gallery Screen）

| 命令 | 参数 | 说明 |
|------|------|------|
| `view_media` | media_id | 查看照片 |
| `delete_media` | media_ids | 删除照片 |
| `share_media` | media_ids | 分享照片 |
| `select_media` | media_id, selected | 选择/取消选择 |
| `search_media` | query | 搜索照片 |
| `switch_view_mode` | mode | 切换视图模式 |
| `favorite_media` | media_id, favorite | 收藏/取消收藏 |

### 4.3 设置页（Settings Screen）

| 命令 | 参数 | 说明 |
|------|------|------|
| `change_theme` | theme | 切换主题 |
| `change_language` | language | 切换语言 |
| `download_model` | model_id | 下载模型 |
| `switch_face_engine` | engine | 切换人脸检测引擎 |
| `toggle_setting` | key, enabled | 开关设置项 |

### 4.4 全场景通用（NavigationCapability）

| 命令 | 参数 | 说明 |
|------|------|------|
| `navigate_to` | destination | 导航到指定页面 |
| `go_back` | - | 返回上一页 |

---

## 5. 验证清单

- [x] `CapabilityRegistryV2.kt` 已删除
- [x] `SceneManager` 中 EDITOR 场景已移除
- [x] `AgentCommand` 中编辑命令已移除
- [x] `AgentCommandParser` 中编辑命令解析已移除
- [x] `GalleryCapability.onClearSelection` 已移除
- [x] 所有 Capability 中 `text_reply` 重复声明已清理
- [x] `CapabilityRegistry.dispatch()` 仍保留 TextReply/Unknown/Error 的顶层处理
- [x] `RemoteOrchestrator` else 分支文案已优化

---

## 6. 附录：变更文件清单

| 文件 | 变更类型 |
|------|----------|
| `app/src/main/java/com/picme/domain/agent/CapabilityRegistryV2.kt` | 删除 |
| `app/src/main/java/com/picme/domain/agent/model/SceneManager.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/model/AgentCommands.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/AgentCommandParser.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/capability/CameraCapability.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/capability/GalleryCapability.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/capability/SettingsCapability.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/capability/NavigationCapability.kt` | 修改 |
| `app/src/main/java/com/picme/domain/agent/remote/RemoteOrchestrator.kt` | 修改 |
