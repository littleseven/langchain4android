---
name: rd-reflection
description: RD 工程师任务复盘与流程优化模板。用于记录开发过程中的陷阱、根因分析和预防措施，形成可复用的经验资产。
---

# RD 开发流程反省与优化

## 本次任务：Gallery adb 测试命令扩展

### 一、问题清单（按严重程度排序）

#### 🔴 P0: 前置调研严重不足
**问题**: 没有先阅读项目已有的 `adb-bot` skill，其中已明确记载了：
- 动态注册 `BroadcastReceiver` 的必要性（解决 Android 8.0+ 后台广播限制）
- `Background execution not allowed` 的故障排除方法
- `CameraScreen` 通过 `DisposableEffect` 注册/注销 receiver 的机制

**根因**: 急于编码，跳过了"读文档 → 画架构 → 写伪代码"的前置步骤。
**时间浪费**: ~25 分钟在调试为什么 `open_photo` 广播被"吞掉"。

#### 🔴 P0: 架构分层理解断层
**问题**: 没有意识到 `CameraScreen` 和 `GalleryScreen` 是两个独立的 Composable，各自需要独立的动态 receiver。以为静态注册的 `CameraTestCommandReceiver` 足够。

**根因**: 没有画出命令流向图（Broadcast → Receiver → Dispatcher → Screen → Flow → Handler）。
**时间浪费**: ~15 分钟在确认 receiver 注册状态。

#### 🟠 P1: Compose 闭包捕获陷阱
**问题**: `GalleryScreen` 的 `LaunchedEffect(Unit)` 闭包捕获了初始值为空的 `allFlatMedia`，导致 `open_photo` 命令处理时始终认为没有照片。

**根因**: 对 Compose 的 `LaunchedEffect` 闭包语义理解不深，没有意识到 `val allFlatMedia = remember(...)` 在 lambda 中被捕获的是挂载时刻的值。
**时间浪费**: ~20 分钟在反复发送 `open_photo` 并困惑为什么 `size=0`。
**正确做法**: 在 `when` 分支内使用 `viewModel.allMedia.value` 直接读取最新 StateFlow 值。

#### 🟠 P1: 调试方法原始且低效
**问题**: 
- 没有使用 `adb logcat -c` 清除日志，导致新旧进程日志混杂
- 没有使用 `adb shell pidof com.picme` 确认进程号，grep 过滤经常抓到旧进程
- 修改代码后只运行 `compileDebugKotlin`，没有重新打包 APK，导致安装的是旧代码
- 日志过滤标签不准确，多次遗漏关键日志

**时间浪费**: ~20 分钟在"修改代码→编译→安装→测试→发现没变化→困惑"的循环中。

#### 🟡 P2: 批量修改导致编译错误
**问题**: 一次性在 `CameraTestCommand` 中添加了 16 个子类，导致 `CameraScreen` 的 exhaustive `when` 编译失败。事后用 `else` 分支补救。

**根因**: 没有增量开发——应该先添加命令定义 + 一个处理分支，验证编译通过后再扩展。
**时间浪费**: ~5 分钟修复编译错误。

---

### 二、优化措施（已落地或待执行）

#### ✅ 措施 1: 强制前置调研 Checklist
任何涉及现有子系统的任务，编码前必须完成：
1. [ ] 检索项目 skills（`adb-bot`, `av-gl-expert`, `coordinate-system-standard` 等）
2. [ ] 阅读相关模块 `AGENTS.md`
3. [ ] 画出核心数据流/命令流图（哪怕只是文字描述）
4. [ ] 确认需要修改的文件清单和影响范围

#### ✅ 措施 2: Compose 状态读取规范
在 `LaunchedEffect(Unit)` 的 `collect` lambda 中：
- **禁止**直接读取 `remember` 计算的局部变量（会被闭包捕获旧值）
- **必须**使用 `viewModel.xxxStateFlow.value` 或 `snapshotFlow { xxx }.first()` 获取最新值
- 文档已更新到 `gallery/AGENTS.md` §3

#### ✅ 措施 3: adb 调试标准化流程
每次设备调试必须遵循：
```bash
# 1. 确认进程
adb shell pidof com.picme

# 2. 清除日志
adb logcat -c

# 3. 修改代码后必须重新打包
./gradlew :app:assembleDebug

# 4. 强制停止后安装（避免热更新残留）
adb shell am force-stop com.picme
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 5. 发送命令
adb shell am broadcast -a com.picme.TEST_COMMAND --es action "xxx"

# 6. 按进程号过滤日志
adb logcat -d | grep "PID"
```

#### ✅ 措施 4: 增量开发原则
扩展命令体系时：
1. 先添加 1 个命令定义
2. 在 Dispatcher 中添加 describe
3. 在目标 Screen 中添加处理分支
4. 编译 → 安装 → 验证
5. 通过后再批量添加剩余命令

---

### 三、已更新的项目资产

| 资产 | 更新内容 |
|------|----------|
| `adb-bot/SKILL.md` | 新增 Gallery 命令列表、故障排除 "命令无响应" 章节 |
| `gallery/AGENTS.md` | 新增 §3 adb 测试命令完整文档 |
| `CameraTestCommand.kt` | 新增 16 个 Gallery 命令 |
| `CameraTestCommandDispatcher.kt` | 新增 describe 映射 |
| `GalleryScreen.kt` | 新增动态 receiver + 命令收集器 |
| `MediaPager.kt` | 新增命令收集器 + 编辑参数控制 |
| `CameraScreen.kt` | 新增 `EnterGallery` 支持 + `else` 分支容错 |

---

### 四、经验沉淀（一句话原则）

> **"先读 skill 再画流图，闭包捕获用 StateFlow，调试必清日志查 pid，增量验证再批量扩。"**
