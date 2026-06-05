<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/OpenGL-ES%203.0-5586A4?logo=opengl&logoColor=white" alt="OpenGL ES">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

<h1 align="center">觅影相机 (PicMe)</h1>

<p align="center">
  <b>会说话的智能相机</b><br>
  <i>动动嘴就能拍出好照片</i>
</p>

<p align="center">
  <a href="#它有什么不一样">亮点</a> ·
  <a href="#试试这些口令">玩法</a> ·
  <a href="#动手跑起来">开发</a> ·
  <a href="#文档地图">文档</a>
</p>

---

## 它有什么不一样

传统相机 App 把按钮塞满屏幕，调个参数要翻三层菜单。PicMe 想试试另一种可能：**你说，它做**。

> 「调高美颜」—— 磨皮、美白一起升。<br>
> 「换个胶片滤镜」—— 色调瞬间变暖。<br>
> 「拍一张」—— 快门应声落下。

不用记按钮在哪，不用学专业术语，就像跟摄影师朋友聊天一样自然。

### 三大核心体验

**自然语言操控**
- 端侧运行 Qwen3-1.7B 大模型，说话就能调参数
- 多轮对话记住上下文，「再亮一点」它懂你指的是曝光
- 语音、文字随时切换，嘈杂环境也能用

**自研实时美颜**
- 从磨皮到唇色，全链路 GPU 渲染，跟手感 < 100ms
- 预览和成片用同一套 Shader，所见即所得
- 帧同步技术解决「妆容甩飞」，转头也不尴尬

**隐私绝对本地**
- 人脸检测、AI 推理、美颜渲染，全部在手机上完成
- 照片从不出设备，连 WiFi 都不用开

---

## 试试这些口令

| 你说 | 它做 |
|------|------|
| 「拍张照」 | 立即拍摄并保存到相册 |
| 「磨皮 50」 | 把磨皮强度调到 50 |
| 「再白一点」 | 在现有基础上增加美白 |
| 「换个冷调」 | 切换到冷色调滤镜 |
| 「打开前置」 | 翻转至前置摄像头 |
| 「有什么滤镜？」 | 列出所有可用滤镜 |

---

## 技术一览

PicMe 不仅是一款应用，也是一场关于「AI 能否主导软件开发」的实验。我们把它开源出来，希望更多人一起探索。

```
Jetpack Compose 界面
    ↓
Agent Runtime（端侧 LLM + 意图解析 + 多轮记忆）
    ↓
Capability 系统（相机 / 相册 / 设置 / 导航）
    ↓
自研 beauty-engine（OpenGL ES + EGL 实时渲染）
```

**架构理念**
- 显式优于隐式 —— 构造函数即文档，AI 也能读懂
- 枚举优于条件 —— 状态空间一目了然，不会遗漏边界
- 自描述优于注释 —— 类型系统就是契约

---

## 动手跑起来

```bash
# 克隆仓库
git clone https://github.com/littleseven/PicMe.git
cd PicMe

# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 一键开发闭环（编译 → 安装 → 截屏 → 日志 → 报告）
./scripts/auto-dev-loop.sh
```

**常用命令速查**

| 命令 | 作用 |
|------|------|
| `./gradlew test` | 运行单元测试 |
| `./gradlew lint` | 代码风格检查 |
| `./scripts/ai-gate.sh` | 质量门禁 |
| `adb logcat -s "PicMe:*"` | 查看日志 |

---

## 文档地图

PicMe 的文档像一本分层手册，从「为什么做」到「怎么做」层层展开：

| 层级 | 文档 | 适合谁 | 内容 |
|------|------|--------|------|
| **导航** | [`docs/00-INDEX.md`](./00-INDEX.md) | 所有人 | 完整文档索引 |
| **产品** | [`PRODUCT.md`](../../PRODUCT.md) | 产品经理 / 开发者 | 产品定义与验收标准 |
| | [`docs/01-PRODUCT/FEATURES.md`](./01-PRODUCT/FEATURES.md) | 产品经理 / 开发者 | 交互流程与体验规则 |
| | [`docs/01-PRODUCT/NFR_SPEC.md`](./01-PRODUCT/NFR_SPEC.md) | QA / 开发者 | 性能与稳定性指标 |
| **架构** | [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](./02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | 开发者 | Agent 运行时架构 |
| | [`docs/02-ARCHITECTURE/ADR/`](./02-ARCHITECTURE/ADR/) | 开发者 / 审查者 | 架构决策记录 |
| **技术规范** | [`docs/03-TECHNICAL-SPECS/`](./03-TECHNICAL-SPECS/) | 开发者 | 美颜引擎、帧同步、人脸检测、Chat UI 改造 |
| **Agent 能力** | [`docs/04-AGENT-CAPABILITIES/`](./04-AGENT-CAPABILITIES/) | 开发者 / 产品经理 | Capability 注册表与命令参考 |
| **开发规范** | [`docs/05-DEVELOPMENT/`](./05-DEVELOPMENT/) | 开发者 / 协调者 | 工作流与 CR 检查清单 |
| **质量标准** | [`docs/06-QA/`](./06-QA/) | QA | 验收测试清单 |
| **标准词典** | [`docs/07-STANDARDS/`](./07-STANDARDS/) | 所有人 | 坐标系标准与术语词典 |
| **容灾** | [`docs/08-FALLBACK/`](./08-FALLBACK/) | 开发者 / QA | 引擎降级策略 |

---

## Agent First 研发范式

PicMe 的隐藏主线是验证「Agent 主导研发」的可行性。我们把基础设施拆成原子化 Tools，让 AI Agent 像搭积木一样完成开发任务。

**已验证的假设**
- 显式架构可被 Agent 高效理解
- 文档驱动开发减少沟通损耗
- Tools 化支持 Self-Heal 闭环（编译 → 修复 → 再编译）
- Capability 系统支持热插拔扩展

**待探索的问题**
- Agent 能高效处理的代码库规模上限是多少？
- Agent 能否主导跨模块架构级重构？
- 学到的模式能否迁移到其他项目？

---

## 许可

MIT License — 研究、学习、二次开发均可自由使用。

---

<p align="center">
  <i>觅影相机 PicMe — 让相机听懂你说的话</i>
</p>
