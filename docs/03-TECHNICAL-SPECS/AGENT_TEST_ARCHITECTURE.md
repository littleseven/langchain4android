# Agent Test 架构文档

> PC端主导的JSON数据驱动测试方案
> 最后更新: 2026-06-06

---

## 1. 架构概述

Agent Test 是一套**PC端主导**的自动化测试方案，通过 adb 命令驱动 Android 应用执行测试操作。PC 端负责测试编排、截屏、性能采集、报告生成，应用端仅负责接收命令并执行。

广播命令格式与 Agent 内部命令格式完全对齐：PC 端不再把 JSON 参数解析为 `key=value` 字符串，而是直接把 `{"method":"...","params":{...}}` 透传给应用端，由 `AgentCommandParser` 统一解析。这消除了两端重复解析的问题，也让测试命令和 LLM 命令使用同一套格式。

### 1.1 核心设计原则

| 原则 | 说明 |
|------|------|
| **PC端主导** | 测试编排、截屏、性能采集、报告生成全部由 PC 端完成 |
| **JSON驱动** | 测试用例以 JSON 文件形式存储，PC 端解析后逐条执行 |
| **adb通信** | PC 端通过 `adb shell am broadcast` 发送命令到应用 |
| **显式广播** | Android 12+ 必须使用显式组件发送广播 |
| **JSON 透传** | PC 端不解析命令，直接把 Agent 标准 JSON 透传给应用端 |
| **零手机存储** | 截屏和报告直接保存到 PC 端，不占用手机空间 |

### 1.2 职责划分

```
+------------------------------------------------------------------+
|                         PC端 (Mac/PC)                             |
|  +--------------+  +--------------+  +-----------------------+  |
|  |  JSON解析器   |  |  测试编排器   |  |  截屏/报告/性能采集    |  |
|  |  (python3)   |  |  (shell)     |  |  (adb命令)            |  |
|  +--------------+  +--------------+  +-----------------------+  |
+------------------------------------------------------------------+
                              |
                              v  adb shell am broadcast
+------------------------------------------------------------------+
|                      Android 设备                                 |
|  +----------------------+  +--------------------------------+  |
|  | AgentTestBroadcast   |  |  CapabilityRegistry            |  |
|  | Receiver (静态注册)   |->|  -> 分发到对应 Capability      |  |
|  +----------------------+  +--------------------------------+  |
+------------------------------------------------------------------+
```

---

## 2. 架构图

### 2.1 系统架构图

```
+-------------------+        +-------------------+        +-------------------+
|  JSON测试用例      |        |  agent-tester     |        |  adb命令层         |
|  scripts/tests/   |------->|  主控脚本          |------->|  adb shell am     |
|  *.json           |        |  (shell+python)   |        |  broadcast        |
+-------------------+        +-------------------+        +---------+---------+
                                                                   |
                                                                   v
+-------------------+        +-------------------+        +-------------------+
|  输出目录          |        |  Android设备       |        |  AgentTestBroadcast|
|  screenshots/     |<-------|  截屏/性能采集      |<-------|  Receiver          |
|  report.json      |        |  (adb screencap)  |        |  (静态注册)         |
+-------------------+        +-------------------+        +---------+---------+
                                                                   |
                                                                   v
                                                    +---------------------------+
                                                    |  CapabilityRegistry       |
                                                    |  命令分发中心              |
                                                    +-------------+-------------+
                                                                  |
                    +----------------+    +----------------+    +----------------+
                    | CameraCapability |    | NavigationCapability |    | 其他Capability... |
                    +----------------+    +----------------+    +----------------+
```

### 2.2 命令执行流程图

```
PC端脚本                              Android设备
+-----------+                         +---------------------------+
| 读取JSON   |                         | AgentTestBroadcastReceiver |
| 测试用例   |                         +---------------------------+
+-----+-----+                                    |
      |                                          |
      v                                          v
+-----------+                         +---------------------------+
| 读取step. |                         | onReceive()               |
| action    |                         | 提取 json 字符串           |
| JSON      |                         +---------------------------+
+-----+-----+                                    |
      |                                          |
      v                                          v
+-----------+                         +---------------------------+
| json.dumps|                         | AgentCommandParser        |
| PC端不解析 |                         | .parseLlmResponse()       |
| 参数      |                         | -> AgentCommand           |
+-----+-----+                         +---------------------------+
      |                                          |
      |                                          v
      |                               +---------------------------+
      |                               | CapabilityRegistry        |
      |                               | .dispatch()               |
      |                               +---------------------------+
      |                                          |
      v                                          v
+-----------+                         +---------------------------+
| adb shell |                         | CameraCapability          |
| "am       |                         | .execute()                |
| broadcast |                         +---------------------------+
| --es json |                                    |
| '{...}'"  |                                    v
+-----+-----+                         +---------------------------+
      |                               | CameraScreen UI           |
      |                               | delegate.onFlipCamera()   |
      v                               +---------------------------+
+-----------+
| 发送显式   |
| 广播Intent |
+-----------+
```

### 2.3 参数解析流程图

```
JSON action
{"method":"switch_filter","params":{"filter":"leica_vibrant"}}
       |
       v
+------------------------+
| Python json.dumps()    |
| PC端不解析参数          |
+------------------------+
       |
       v
+------------------------+
| adb shell "am broadcast|
| -n <receiver>          |
| -a com.mamba.picme.AGENT_TEST|
| --es json '{...}'"     |
+------------------------+
       |
       v
+------------------------+
| BroadcastReceiver      |
| intent.getStringExtra  |
| ("json")               |
+------------------------+
       |
       v
+------------------------+
| AgentCommandParser     |
| .parseLlmResponse()    |
| 解析 method + params   |
| 复用LLM相同解析逻辑     |
+------------------------+
       |
       v
+------------------------+
| resolveFilterType()    |
| 支持别名映射            |
+------------------------+
       |
       v
+------------------------+
| AgentCommand.SwitchFilter(LEICA_VIBRANT) |
+------------------------+
```

---

## 3. JSON测试用例格式

### 3.1 文件结构

```
scripts/tests/
├── camera/
│   ├── tc-camera-01-startup.json
│   ├── tc-camera-02-flip.json
│   ├── tc-camera-03-capture.json
│   ├── tc-camera-04-ratio.json
│   └── tc-camera-05-filter.json
└── settings/
    └── ...
```

### 3.2 JSON Schema

```json
{
  "caseId": "TC-CAMERA-02",
  "name": "前后摄像头切换验证",
  "category": "CAMERA",
  "priority": "P0",
  "steps": [
    {
      "description": "等待相机页面就绪",
      "wait": {"condition": "scene == 'CAMERA'", "timeout": 5000},
      "assert": {"scene": "CAMERA"}
    },
    {
      "description": "切换到前置摄像头",
      "action": {"method": "flip_camera", "params": {}},
      "assert": {"commandResult": "success"},
      "delay": 1500
    },
    {
      "description": "截屏记录前置预览",
      "screenshot": "camera_front"
    }
  ]
}
```

### 3.3 Step字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `description` | string | 步骤描述（日志输出） |
| `action` | object | 执行命令，`method` + `params` |
| `wait` | object | 等待条件，`condition` + `timeout` |
| `assert` | object | 断言验证，key-value对 |
| `delay` | number | 步骤后延迟（毫秒） |
| `screenshot` | string | 截屏标记名称 |

### 3.4 支持的命令列表

| 命令 | 参数示例 | 说明 |
|------|----------|------|
| `capture` | - | 拍照 |
| `flip_camera` | - | 切换前后摄像头 |
| `toggle_recording` | - | 开始/停止录像 |
| `navigate_to` | `{"destination":"settings"}` | 导航到指定页面 |
| `go_back` | - | 返回上一页 |
| `adjust_beauty` | `{"smoothing":80,"whitening":60}` | 调整美颜参数 |
| `switch_filter` | `{"filter":"leica_classic"}` | 切换滤镜 |
| `switch_style` | `{"style":"vintage"}` | 切换风格 |
| `switch_scene` | `{"scene":"portrait"}` | 切换场景 |
| `switch_ratio` | `{"ratio":"16_9"}` | 切换画幅比例 |
| `adjust_exposure` | `{"exposure":2}` | 调整曝光 |
| `adjust_zoom` | `{"zoom":2.0}` | 调整变焦 |
| `switch_mode` | `{"mode":"video"}` | 切换拍摄模式 |
| `switch_face_engine` | `{"engine":"mnn"}` | 切换人脸检测引擎 |
| `toggle_setting` | `{"key":"grid","enabled":true}` | 切换设置项 |
| `change_theme` | `{"theme":"dark"}` | 切换主题 |
| `change_language` | `{"language":"en"}` | 切换语言 |

---

## 4. PC端脚本详解

### 4.1 脚本位置

```
scripts/agent-tester
```

### 4.2 调用方式

```bash
# 运行测试套件
./scripts/agent-tester suite camera
./scripts/agent-tester suite settings

# 运行单个用例
./scripts/agent-tester case scripts/tests/camera/tc-camera-02-flip.json

# 直接发送 JSON 命令
./scripts/agent-tester cmd '{"method":"flip_camera","params":{}}'
./scripts/agent-tester cmd '{"method":"switch_ratio","params":{"ratio":"16_9"}}'
```

### 4.3 核心函数

#### send_json_cmd - 发送 JSON 命令到应用

```bash
send_json_cmd() {
    local json="$1"
    local receiver="com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver"
    
    # 注意：JSON 必须用单引号包裹，防止 adb shell 解释 {} 为 brace expansion
    adb shell "am broadcast -n '$receiver' -a com.mamba.picme.AGENT_TEST --es json '$json'"
}
```

**为什么用单引号包裹 JSON？**

设备端 shell（通常是 mksh/toybox）会对 `{}` 做 brace expansion。如果直接写：

```bash
adb shell am broadcast --es json '{"method":"flip_camera","params":{}}'
```

设备端 shell 会把它解释为：

```bash
am broadcast --es json "method":"flip_camera" "params":{}
```

结果 `json` extra 的值变成了 `method:flip_camera`，导致解析失败。

正确做法是把整个 `am broadcast` 命令用双引号包裹，JSON 用单引号包裹：

```bash
adb shell "am broadcast -n '...' --es json '{"method":"flip_camera","params":{}}'"
```

#### run_json_case - 解析并执行JSON用例

```bash
run_json_case() {
    local json_file="$1"
    
    # 使用python3解析JSON
    # 核心原则：PC端只透传 action JSON，不解析参数
    python3 << 'PYEOF'
    import json
    import subprocess
    
    with open(json_file) as f:
        case = json.load(f)
    
    receiver = 'com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver'
    
    for step in case['steps']:
        # 1. 执行action：直接透传原始JSON，不解析参数
        action = step.get('action')
        if action:
            action_json = json.dumps(action, separators=(',', ':'), ensure_ascii=False)
            
            # 必须用单引号包裹JSON，防止adb shell解释{}为brace expansion
            shell_cmd = (
                f"am broadcast -n '{receiver}' -a com.mamba.picme.AGENT_TEST "
                f"--es json '{action_json}'"
            )
            subprocess.run(['adb', 'shell', shell_cmd], capture_output=True)
        
        # 2. 延迟等待
        if 'delay' in step:
            time.sleep(step['delay'] / 1000)
        
        # 3. 截屏（PC端负责）
        if 'screenshot' in step:
            subprocess.run(['adb', 'shell', 'screencap', '-p', '/sdcard/temp.png'])
            subprocess.run(['adb', 'pull', '/sdcard/temp.png', f"{screenshot_dir}/{ss_name}.png"])
PYEOF
}
```

### 4.4 输出目录结构

```
scripts/auto_test_output/agent_20260606_165436/
├── report.json              # 测试报告
├── test.log                 # 执行日志
├── screenshots/             # 截屏目录
│   ├── tc-camera-01-startup_camera_startup.png
│   ├── tc-camera-02-flip_camera_front.png
│   ├── tc-camera-02-flip_camera_back.png
│   └── ...
├── perf_fps.txt             # FPS数据
├── perf_memory.txt          # 内存数据
└── perf_temp.txt            # 温度数据
```

---

## 5. 应用端详解

### 5.1 BroadcastReceiver

**文件**: `app/src/main/java/com/picme/testing/agent/bridge/AgentTestBroadcastReceiver.kt`

**注册方式**: AndroidManifest.xml 静态注册

```xml

<receiver android:name=".testing.agent.bridge.AgentTestBroadcastReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.mamba.picme.AGENT_TEST" />
    </intent-filter>
</receiver>
```

**为什么必须静态注册？**
- 动态注册（DisposableEffect）只在特定 Compose Screen 显示时生效
- 静态注册确保无论应用处于什么状态都能接收广播
- Android 12+ 对隐式广播的限制要求使用显式组件

### 5.2 命令处理流程（JSON 格式）

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    // 1. 提取 JSON 字符串
    val json = intent.getStringExtra("json") ?: return
    
    // 2. 复用 AgentCommandParser 解析为 AgentCommand
    // 与 LLM 命令使用完全相同的解析路径
    val command = AgentCommandParser.parseLlmResponse(
        json,
        AgentContext(scene = AgentScene.CAMERA)
    )
    
    // 3. 通过 CapabilityRegistry 分发
    val result = CapabilityRegistry.getInstance().dispatch(
        command, 
        AgentContext(scene = AgentScene.CAMERA)
    )
    
    // 4. 返回执行结果
    sendResponse(context, result.toJson())
}
```

### 5.3 参数解析策略

所有参数解析复用 `AgentCommandParser.parseLlmResponse()`，与 LLM 命令走同一条解析路径：

```kotlin
// 旧方式（已废弃）：字符串参数需要手动解析
private fun parseCommand(cmd: String, param: String?): AgentCommand { ... }

// 新方式：直接复用 Agent 命令解析器
val command = AgentCommandParser.parseLlmResponse(json, context)
```

这样做的好处：
- 测试命令和 LLM 命令格式完全一致
- 自动享受 `AgentCommandParser` 的所有特性：别名映射、参数校验、默认值填充
- 消除 `parseCommand()` 与 `AgentCommandParser` 之间的重复逻辑和潜在不一致

---

## 6. 关键技术点

### 6.1 Android 12+ 广播限制

**问题**: Android 12（API 31）开始，系统对隐式广播进行了严格限制，未指定目标组件的广播可能无法被接收。

**解决**: 必须使用显式组件发送广播

```bash
# 错误（隐式广播）
adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es cmd "flip_camera"

# 正确（显式组件）
adb shell am broadcast \
    -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver \
    -a com.mamba.picme.AGENT_TEST \
    --es json '{"method":"flip_camera","params":{}}'
```

### 6.2 参数传递格式（透传）

PC 端不做任何转换，直接把 JSON 字符串传给应用端：

```json
// JSON 测试用例中的 action
{"method": "switch_filter", "params": {"filter": "leica_vibrant"}}

// PC 端直接透传（Python）
action_json = json.dumps(action)  # '{"method":"switch_filter","params":{"filter":"leica_vibrant"}}'

// adb 命令
adb shell "am broadcast -n '...' -a com.mamba.picme.AGENT_TEST --es json '{...}'"

// 应用端解析
AgentCommandParser.parseLlmResponse(json, context) → AgentCommand.SwitchFilter(LEICA_VIBRANT)
```

### 6.3 多参数传递

```json
{"method": "adjust_beauty", "params": {"smoothing": 80, "whitening": 60}}

// PC 端直接透传
'{"method":"adjust_beauty","params":{"smoothing":80,"whitening":60}}'

// 应用端解析（复用 AgentCommandParser）
// smoothing=80, whitening=60（Float 类型，非字符串）
```

---

## 7. 历史方案对比

| 特性 | 旧方案（应用端主导） | 当前方案（PC端主导） |
|------|---------------------|---------------------|
| **测试编排** | 应用端解析JSON | PC端解析JSON |
| **截屏** | 应用内Canvas（无预览） | adb screencap（完整屏幕） |
| **报告存储** | 手机外部存储 | PC本地目录 |
| **通信方式** | 广播触发→应用自主执行 | 广播透传JSON命令 |
| **命令格式** | 应用端自定义 | 与 Agent LLM 命令格式一致 |
| **参数解析** | 应用端解析JSON | 复用 AgentCommandParser |
| **观测性** | 依赖应用日志 | PC端实时输出 |
| **Android 12+** | 需要动态注册receiver | 静态注册+显式广播 |
| **灵活性** | 应用端控制流程 | PC端完全控制 |

---

## 8. 使用示例

### 8.1 运行相机测试套件

```bash
$ ./scripts/agent-tester suite camera

[INFO] 设备: 51912a5c
[INFO] 开始执行相机测试套件（JSON驱动）
[INFO] 发现 5 个测试用例
[INFO] 执行用例: tc-camera-01-startup
[INFO] 步骤数: 3
[STEP] 等待相机页面就绪
  -> wait: scene == 'CAMERA'
  -> assert: scene == CAMERA
[STEP] 验证相机预览已启动
  -> assert: canCapture == true
[STEP] 截屏记录启动画面
  -> screenshot: tc-camera-01-startup_camera_startup.png
[PASS] 用例执行完成: 3/3 步骤
...
[PASS] 测试套件执行完成: 5/5 通过
```

### 8.2 直接发送 JSON 命令

```bash
# 切换摄像头
$ ./scripts/agent-tester cmd '{"method":"flip_camera","params":{}}'

# 切换滤镜
$ ./scripts/agent-tester cmd '{"method":"switch_filter","params":{"filter":"leica_classic"}}'

# 调整美颜
$ ./scripts/agent-tester cmd '{"method":"adjust_beauty","params":{"smoothing":80,"whitening":60}}'
```

---

## 9. 扩展指南

### 9.1 添加新命令

1. **应用端**: 如果 `AgentCommandParser` 已支持该命令，则无需修改；否则在 `AgentCommandParser.parseCommandByMethod()` 中添加解析逻辑
2. **PC端**: JSON 中直接使用新的 `method` 和 `params`，PC 端自动透传

### 9.2 添加新测试用例

1. 在 `scripts/tests/camera/` 或 `scripts/tests/settings/` 创建 JSON 文件
2. 按照 Schema 定义步骤
3. 运行 `./scripts/agent-tester suite camera`

### 9.3 添加新套件

1. 在 `scripts/tests/` 下创建新目录
2. 在 `agent-tester` 的 `run_*_suite()` 中添加套件执行逻辑
3. 在 `case` 分支中添加路由

---

## 10. 故障排查

| 问题 | 原因 | 解决 |
|------|------|------|
| 广播发送成功但无响应 | 隐式广播被Android 12+拦截 | 使用 `-n` 显式组件 |
| JSON 被截断为 `method:xxx` | adb shell 把 `{}` 解释为 brace expansion | 用单引号包裹 JSON：`--es json '{...}'` |
| `No enum constant` 错误 | 参数格式错误 | 使用 JSON 透传，不再手动拼接字符串 |
| 应用退到后台 | receiver未静态注册 | 在AndroidManifest.xml中注册 |
| 截图无相机预览 | 应用内截屏无法捕获SurfaceView | 使用 `adb shell screencap` |
| 命令执行但UI无变化 | delegate未绑定 | 确保目标页面已显示 |
