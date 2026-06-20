# LangChain4Android 技术规划 v3.0 —— Fork 源码直改方案

## 1. 项目概述

### 1.1 项目定位
在 PicMe 项目中直接 Fork LangChain4j 官方源码，物理删除所有 Android 不兼容代码，输出一个开箱即用的 Android AI Agent 开发框架。作为 PicMe 项目的内部模块存在，不独立拆库。

### 1.2 项目名称
**Mamba Agent**（包名：`com.mamba`）

### 1.3 核心理念
- **不妥协**：不存在"适配"、"兼容"、"workaround"——不兼容的代码直接删掉
- **零配置**：用户只需加一行依赖，无需 exclude、版本锁定、ProGuard 规则
- **原生体验**：完美融入 Android 生命周期、Logcat、OkHttp 网络栈
- **轻量**：移除 opennlp、JDK HttpClient、Jackson，APK 增量控制在 300KB 以内

---

## 2. Fork 基线

| 项目 | 值 |
|------|-----|
| **上游仓库** | https://github.com/langchain4j/langchain4j |
| **Fork 版本** | 1.16.3（2026-06-15 发布） |
| **包名** | `com.mamba`（原 `dev.langchain4j`） |
| **后续同步策略** | 定期 cherry-pick 上游 bugfix 和安全更新，功能更新评估后选择性合并 |
| **License** | Apache-2.0（保留原版权声明） |

---

## 3. 模块架构

> **设计原则**：所有代码合并到单一库 `mamba-agent` 中，无需拆分为多个子模块。Demo 直接使用 PicMe 现有 app 模块，无需额外创建 sample-app。

```
mamba-agent/                              # 单一库，合并所有模块
├── src/main/java/com/mamba/
│   ├── tool/                             # @Tool 注解 + ToolSpecification（保留）
│   ├── service/                          # AiServices（重写，移除 SPI）
│   ├── model/                            # ChatLanguageModel 接口 + OpenAiChatModel 实现
│   ├── memory/                           # ChatMemory（保留，新增 Room 实现）
│   ├── rag/                              # RAG 支持（保留，但移除 opennlp 依赖）
│   ├── json/                             # GsonJsonCodec（新增，替换 Jackson）
│   ├── internal/                         # 内部工具类（重写，移除 SPI）
│   └── android/                          # Android 专用封装（Kotlin）
│       ├── AgentFactory.kt               # 统一 Agent 构建入口
│       ├── AndroidToolRegistry.kt        # 工具注册中心
│       ├── ViewModelAgentDelegate.kt     # ViewModel 集成
│       ├── RoomChatMemoryStore.kt        # Room 持久化记忆
│       ├── AndroidLogger.kt              # Logcat 日志桥接
│       ├── NetworkMonitor.kt             # 网络状态监听
│       └── ExceptionHandler.kt           # 统一异常处理
│
├── src/test/java/                        # 单元测试
├── src/androidTest/java/                 # Instrumentation 测试
├── build.gradle.kts                      # 单一构建配置
├── consumer-rules.pro                    # 消费者 ProGuard 规则
└── README.md

# Demo：直接使用 PicMe 现有 app 模块
app/
├── src/main/java/com/mamba/picme/
│   └── ...                               # 现有 PicMe 业务代码，直接集成 mamba-agent
└── build.gradle.kts                      # 添加 implementation(project(":mamba-agent"))
```

---

## 4. 核心改动清单

### 4.1 必须删除的代码

| 文件/代码 | 原因 | 替换方案 |
|----------|------|---------|
| `ServiceLoader.load()` 所有调用（约 15 处） | Android SPI 阻塞 ANR | 强制构造函数注入 |
| `langchain4j-http-client-jdk` 整个模块 | 依赖 Java 11 `java.net.http.HttpClient` | 删除，只用 OkHttp |
| `opennlp-tools` 依赖 | Android 不兼容，大体积 | 从 POM 中移除 |
| `jackson-databind` / `jackson-core` / `jackson-annotations` | Java 17 `isSealed()` 崩溃，APK 体积 1.2MB | 替换为 Gson |
| `String.formatted()` 调用 | Android < API 34 无此方法 | 替换为 `String.format()` |
| `java.util.HexFormat` 调用 | Java 17 API | 替换为手动 hex 转换 |
| `META-INF/services/` 所有 SPI 注册文件 | 不再需要 SPI | 全部删除 |
| `InternalJsonCodec.java`（Jackson 实现的 JsonCodec） | 不再需要 | 删除 |

### 4.2 必须修改的代码

| 文件 | 改动内容 |
|------|---------|
| `AiServices.java` | 构造函数改为强制传 `ChatLanguageModel`，移除 SPI fallback；若未传直接抛 `IllegalStateException` |
| `ChatLanguageModelProvider.java` 等所有 Provider | 删除整个类，不再需要 |
| `OpenAiClientBase.java` | HTTP 请求体拼 JSON 从 Jackson ObjectMapper 改为 Gson |
| `DocumentBySentenceSplitter.java` | 移除 opennlp 依赖，改为正则简单分句或直接删除该类 |
| `build.gradle.kts`（所有子模块） | 删除 opennlp / Jackson 依赖，新增 Gson |

### 4.3 必须新增的代码

| 文件 | 功能 | 所在目录 |
|------|------|---------|
| `GsonJsonCodec.java` | Gson 实现的 JsonCodec，替换 Jackson 的业务序列化 | `src/main/java/com/mamba/json/` |
| `AgentFactory.kt` | 统一 Agent 构建，自动配置 OkHttp、日志、生命周期 | `src/main/java/com/mamba/android/` |
| `AndroidToolRegistry.kt` | 集中注册和管理 @Tool，支持按条件过滤 | `src/main/java/com/mamba/android/` |
| `ViewModelAgentDelegate.kt` | ViewModel 生命周期感知，自动保存/恢复对话 | `src/main/java/com/mamba/android/` |
| `RoomChatMemoryStore.kt` | 使用 Room 持久化 ChatMemory | `src/main/java/com/mamba/android/` |
| `AndroidLogger.kt` | 桥接 SLF4J → Android Logcat | `src/main/java/com/mamba/android/` |
| `NetworkMonitor.kt` | 网络状态监听，断网自动暂停/恢复 | `src/main/java/com/mamba/android/` |
| `ExceptionHandler.kt` | 统一异常处理，区分网络/认证/限流错误 | `src/main/java/com/mamba/android/` |

---

## 5. 包名替换

### 5.1 新旧对照

| 原包名 | 新包名 |
|--------|--------|
| `dev.langchain4j` | `com.mamba` |
| `dev.langchain4j.service.AiServices` | `com.mamba.service.AiServices` |
| `dev.langchain4j.agent.tool.Tool` | `com.mamba.tool.Tool` |
| `dev.langchain4j.model.chat.ChatLanguageModel` | `com.mamba.model.chat.ChatLanguageModel` |

### 5.2 替换范围

| 文件类型 | 改动内容 |
|---------|---------|
| Java/Kotlin 源文件 | `package dev.langchain4j` → `package com.mamba` |
| 所有 `import` 语句 | `import dev.langchain4j.*` → `import com.mamba.*` |
| `build.gradle.kts` | `namespace = "com.mamba"` |
| ProGuard 规则 | `-keep class com.mamba.**` |
| 文档 | 示例代码里的 import |

> **注意**：包名替换范围仅限 `mamba-agent` 模块内部源码。PicMe app 模块中引用 `mamba-agent` 时使用新包名 `com.mamba.*`，不影响 PicMe 其他业务代码。

---

## 6. JSON 库替换方案

### 6.1 替换策略

| 层 | 原方案 | 新方案 |
|----|--------|--------|
| HTTP 请求体拼 JSON（`OpenAiClientBase.java`） | Jackson ObjectMapper | Gson |
| 业务层 JsonCodec（Tool 参数 / Message 序列化） | InternalJsonCodec（Jackson） | GsonJsonCodec（新增） |
| 测试代码 | Jackson | Gson |

### 6.2 GsonJsonCodec 实现

```
// mamba-agent/src/main/java/com/mamba/agent/json/GsonJsonCodec.java
package com.mamba.json;

import com.google.gson.Gson;
import com.mamba.json.JsonCodec;

public class GsonJsonCodec implements JsonCodec {

    private final Gson gson = new Gson();

    @Override
    public String toJson(Object obj) {
        return gson.toJson(obj);
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        return gson.fromJson(json, type);
    }
}
```

### 6.3 HTTP 层改动

```
// OpenAiClientBase.java（Fork 版）
// 原来（Jackson）
// ObjectMapper mapper = new ObjectMapper();
// RequestBody body = RequestBody.create(
//     mapper.writeValueAsString(request),
//     MediaType.get("application/json")
// );

// 改成（Gson）
Gson gson = new Gson();
RequestBody body = RequestBody.create(
    gson.toJson(request),
    MediaType.get("application/json")
);
```

### 6.4 AiServices 默认注入

```
// AiServices.java（Fork 版）
if (this.jsonCodec == null) {
    this.jsonCodec = new GsonJsonCodec();
}
```

---

## 7. 构建配置

### 7.1 PicMe 根 build.gradle.kts（在现有项目新增 mamba-agent 模块）

```
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("com.android.library") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
```

### 7.4 settings.gradle.kts（注册模块）

```
pluginManagement {
    // ... 现有配置
}

dependencyResolutionManagement {
    // ... 现有配置
}

rootProject.name = "PicMe"

// 现有模块
include(":app")
include(":beauty-api")
include(":beauty-engine")
include(":agent-core")

// 新增：mamba-agent 单一库
include(":mamba-agent")
```

### 7.2 mamba-agent/build.gradle.kts（单一库配置）

```
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "2.0.21-1.0.27"
}

android {
    namespace = "com.mamba"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // ─── 核心依赖（原 mamba-agent-core + open-ai + http-client-okhttp 合并） ───

    // JSON 序列化（替换 Jackson）
    api("com.google.code.gson:gson:2.11.0")

    // HTTP 客户端（OkHttp）
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    api("com.squareup.okhttp3:sse:4.12.0")

    // 日志（保留 SLF4J API，由调用方选择桥接实现）
    api("org.slf4j:slf4j-api:2.0.16")

    // ─── Android 生态（原 mamba-agent-android 合并） ───

    // Lifecycle
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    api("androidx.lifecycle:lifecycle-common:2.8.7")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Room（可选，用于 ChatMemory 持久化）
    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ─── 测试 ───
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("com.google.truth:truth:1.4.4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // ─── 已删除的依赖 ───
    // ❌ jackson-databind / jackson-core / jackson-annotations
    // ❌ opennlp-tools
    // ❌ java.net.http.HttpClient（JDK 11+）
    // ❌ ServiceLoader SPI 相关
}
```

### 7.3 PicMe app/build.gradle.kts（使用 mamba-agent）

```
dependencies {
    // 直接引用单一库
    implementation(project(":mamba-agent"))

    // PicMe 现有依赖保持不变...
}
```

### 7.5 ProGuard 规则

```
# mamba-agent-proguard.pro
# Gson
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers class com.mamba.** {
    <fields>;
}

# LangChain4j Fork
-keep class com.mamba.** { *; }
-dontwarn com.mamba.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# SLF4J
-dontwarn org.slf4j.**
```

---

## 8. 开发路线图

### Phase 1：Fork 与清理（1 周）

- [ ] 在 PicMe 项目中创建 `mamba-agent` 模块目录
- [ ] 复制 langchain4j 官方源码（core + open-ai + http-client-okhttp）到单一库
- [ ] 全局替换包名 `dev.langchain4j` → `com.mamba`
- [ ] 删除 `langchain4j-http-client-jdk` 相关代码
- [ ] 删除所有 `ServiceLoader.load()` 调用
- [ ] 删除所有 SPI Provider 类
- [ ] 删除 `opennlp-tools` 依赖及相关代码
- [ ] 删除 Jackson 所有依赖
- [ ] 新增 Gson 依赖
- [ ] 替换 `String.formatted()` 为 `String.format()`
- [ ] 删除 `META-INF/services/` 目录

### Phase 2：核心重写（1 周）

- [ ] 重写 `AiServices.java`：强制构造函数注入，移除 SPI fallback
- [ ] 实现 `GsonJsonCodec.java`
- [ ] 修改 `OpenAiClientBase.java`：HTTP 层改用 Gson
- [ ] 修改 `DocumentBySentenceSplitter.java`：移除 opennlp，改用正则
- [ ] 确保所有单元测试通过

### Phase 3：Android 封装（1 周）

- [ ] 实现 `AgentFactory.kt`
- [ ] 实现 `AndroidToolRegistry.kt`
- [ ] 实现 `ViewModelAgentDelegate.kt`
- [ ] 实现 `RoomChatMemoryStore.kt`
- [ ] 实现 `AndroidLogger.kt`
- [ ] 实现 `NetworkMonitor.kt`
- [ ] 实现 `ExceptionHandler.kt`

### Phase 4：PicMe 集成（1 周）

- [ ] 在 PicMe app 模块中集成 mamba-agent（`implementation(project(":mamba-agent"))`）
- [ ] 替换现有远程推理实现为 mamba-agent 的 OpenAiChatModel
- [ ] 验证 Tool Calling 端到端流程（DeepSeek / OpenAI）
- [ ] 验证流式对话功能
- [ ] 验证对话历史持久化
- [ ] 性能对比：mamba-agent vs 现有实现

### Phase 5：测试与发布（1 周）

- [ ] 单元测试覆盖核心逻辑
- [ ] Instrumentation 测试覆盖 Android 特性
- [ ] 真机测试（3 台以上不同 Android 版本）
- [ ] 性能基准测试
- [ ] README 与文档
- [ ] GitHub Actions CI/CD
- [ ] 发布 1.0.0-alpha

---

## 9. 测试策略

### 9.1 单元测试（JUnit 4 + Mockito）

> **注意**：使用 JUnit 4 而非 JUnit 5，因为 Android 官方测试框架对 JUnit 5 支持有限。

| 测试范围 | 目标覆盖率 |
|---------|-----------|
| AiServices 强制注入逻辑 | 95%+ |
| Tool Registry 注册/发现 | 90%+ |
| ChatMemory 持久化 | 85%+ |
| GsonJsonCodec 编解码 | 90%+ |

### 9.2 Instrumentation 测试

| 测试场景 | 说明 |
|---------|------|
| Agent 构建（主线程/后台线程） | 验证无 ANR |
| Tool Calling 完整循环 | 验证端到端 |
| 流式对话 | 验证 SSE 解析 |
| Activity 重建恢复 | 验证对话不丢 |
| 网络切换 | 验证断网重连 |

### 9.3 性能基准

| 指标 | 目标 |
|------|------|
| 首次 Agent 构建时间 | < 50ms（排除网络延迟） |
| Tool Calling 额外开销 | < 30ms |
| APK 大小增量 | < 300KB |
| 空闲内存占用 | < 3MB |

---

## 10. 发布计划

### 10.1 版本命名

```
mamba-agent-1.16.3.0
             ↑ ↑ ↑ ↑
             │ │ │ └─ 补丁号（bugfix）
             │ │ └─── 小版本（新功能）
             │ └───── 大版本（breaking change）
             └─────── 上游 LangChain4j 版本号
```

> 版本号仅用于 PicMe 内部模块管理，不对外发布。

### 10.2 发布策略

> **注意**：Mamba Agent 当前作为 PicMe 项目的内部模块存在，不独立发布到 Maven Central。如需外部使用，可提取为独立仓库。

| 场景 | 策略 |
|------|------|
| PicMe 内部使用 | 作为 `project(":mamba-agent")` 本地模块依赖 |
| 未来独立发布 | 评估后提取为独立仓库，发布到 Maven Central（groupId: `com.mamba`） |
| 上游 bugfix | cherry-pick 到 `mamba-agent` 模块 |

### 10.3 发布检查清单

- [ ] 所有单元测试通过
- [ ] Instrumentation 测试通过
- [ ] 3 台以上真机验证
- [ ] 性能基准达标
- [ ] 模块 README 更新
- [ ] License 头检查（保留 Apache-2.0 原版权声明）
- [ ] 上游同步检查（是否有重要 bugfix 未合并）

---

## 11. 上游同步策略

| 场景 | 策略 |
|------|------|
| 上游 bugfix | 立即 cherry-pick，发布补丁版本 |
| 上游新功能 | 评估兼容性后选择性合并 |
| 上游 breaking change | 评估影响，必要时延迟合并 |
| 上游引入新的 Java 17+ API | 拒绝合并，自行实现替代方案 |

---

## 12. 成功指标

### 12.1 技术指标

- [ ] Android 端 Tool Calling 端到端延迟 < 模型推理时间 + 100ms
- [ ] 首次构建无 ANR / 无 Crash
- [ ] 覆盖 3 种以上模型提供商（DeepSeek / OpenAI / Ollama）
- [ ] 单元测试覆盖率 > 80%
- [ ] APK 增量 < 300KB

### 12.2 社区指标（未来独立发布后 6 个月内）

> 以下指标仅在 Mamba Agent 提取为独立开源仓库后适用：

- [ ] GitHub Stars > 500
- [ ] 至少 5 个外部贡献者
- [ ] 至少 3 个生产级使用案例
- [ ] Issues 平均响应时间 < 48h

---

## 13. 用户使用示例

### 13.1 添加依赖

```
// PicMe app/build.gradle.kts
dependencies {
    implementation(project(":mamba-agent"))
}
```

### 13.2 定义 Tool

```
import com.mamba.tool.Tool
import com.mamba.tool.P

class WeatherTool {
    @Tool("获取指定城市的当前天气")
    fun getWeather(@P("城市名称，如 杭州") city: String): String {
        return "$city：晴，26°C"
    }
}
```

### 13.3 定义 Agent 接口

```
import com.mamba.service.SystemMessage
import com.mamba.service.UserMessage

@SystemMessage("你是一个有用的天气助手")
interface WeatherAgent {
    @UserMessage("{{it}}")
    fun chat(userInput: String): String
}
```

### 13.4 构建并使用

```
import com.mamba.android.AgentFactory

class ChatViewModel : ViewModel() {
    private lateinit var agent: WeatherAgent

    fun init(apiKey: String) = viewModelScope.launch(Dispatchers.IO) {
        agent = AgentFactory.createDeepSeekAgent(
            apiKey = apiKey,
            tools = listOf(WeatherTool())
        )
    }

    fun ask(question: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val answer = agent.chat(question)
            withContext(Dispatchers.Main) { onResult(answer) }
        }
    }
}
```

---

## 14. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| LangChain4j 引入新的 Java 17+ API | 中 | 高 | 建立自动化兼容性 CI 检测 |
| DeepSeek API 不兼容更新 | 低 | 中 | 抽象模型层，支持多 Provider |
| Android 新版本破坏兼容性 | 低 | 中 | 及时跟进 Android 版本更新 |
| 维护精力不足 | 中 | 高 | 清晰的模块文档 + 自动化测试 |

---

## 15. 附录：关键类设计草案

### AiServices.java（Fork 版核心改动）

```
package com.mamba.service;

import com.mamba.json.GsonJsonCodec;
import com.mamba.json.JsonCodec;
import com.mamba.model.chat.ChatLanguageModel;
// ... 其他 import

public class AiServices<T> {

    private final ChatLanguageModel chatLanguageModel;
    private final Object[] tools;
    private final ChatMemory chatMemory;
    private final JsonCodec jsonCodec;

    private AiServices(Builder<T> builder) {
        // 强制检查：所有组件必须显式传入
        if (builder.chatLanguageModel == null) {
            throw new IllegalStateException(
                    "chatLanguageModel must be explicitly provided"
            );
        }
        this.chatLanguageModel = builder.chatLanguageModel;
        this.tools = builder.tools != null ? builder.tools : new Object[0];
        this.chatMemory = builder.chatMemory != null ?
                builder.chatMemory : MessageWindowChatMemory.withMaxMessages(20);
        this.jsonCodec = builder.jsonCodec != null ?
                builder.jsonCodec : new GsonJsonCodec();
    }

    public static <T> Builder<T> builder(Class<T> assistantClass) {
        return new Builder<>(assistantClass);
    }

    public static class Builder<T> {
        private ChatLanguageModel chatLanguageModel;
        private Object[] tools;
        private ChatMemory chatMemory;
        private JsonCodec jsonCodec;

        public Builder<T> chatLanguageModel(ChatLanguageModel model) {
            this.chatLanguageModel = model;
            return this;
        }

        public Builder<T> tools(Object... tools) {
            this.tools = tools;
            return this;
        }

        public Builder<T> chatMemory(ChatMemory memory) {
            this.chatMemory = memory;
            return this;
        }

        public Builder<T> jsonCodec(JsonCodec codec) {
            this.jsonCodec = codec;
            return this;
        }

        public T build() {
            return new AiServices<>(this).createProxy();
        }
    }

    // ... 代理创建逻辑
}
```

### AgentFactory.kt

```
package com.mamba.android

import com.mamba.model.openai.OpenAiChatModel
import com.mamba.service.AiServices
import com.mamba.memory.chat.MessageWindowChatMemory

object AgentFactory {

    inline fun <reified T : Any> createDeepSeekAgent(
        apiKey: String,
        tools: List<Any>,
        modelName: String = "deepseek-chat",
        baseUrl: String = "https://api.deepseek.com/v1"
    ): T {
        val model = OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.1)
            .build()

        return AiServices.builder(T::class.java)
            .chatLanguageModel(model)
            .tools(*tools.toTypedArray())
            .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
            .build()
    }
}
```

---

*本文档为 Mamba Agent 项目的最终技术规划，将随项目进展持续更新。*