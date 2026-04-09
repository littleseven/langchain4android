# R Plan QA 执行清单

**适用范围**：R Plan 主引擎 + PixelFree 兜底链路
**最后更新**：2026-04
**关联文档**：`R_PLAN_TECH_SPEC.md`、`CAMERA_PREVIEW_TECH_SPEC.md`、`PIXELFREE_FALLBACK_TECH_SPEC.md`、`FEATURES.md`

---

## 0. 术语与状态定义（执行前必读）

- **引擎策略枚举**：`R_PLAN`（主引擎）/ `PIXEL_FREE`（兜底引擎）。
- **统一回退入口**：`onGlWarmUpFallback(reason)`。
- **策略持久化键**：`beauty_strategy`、`gl_engine_recovery_available_at_ms`。
- **自动恢复触发**：`triggerManualGlEngineRecovery()`。
- **Provider 视图切换状态**：`useProviderRenderView=true` 表示使用 R Plan Provider View；`false` 表示回落 `PreviewView`。

---

## 1. 测试基线

### 1.1 功能测试

| 测试项 | 预期结果 |
|--------|----------|
| 打开相机 | 预览画面正常显示，无黑屏 |
| 调节磨皮 | 画面实时变化，延迟 < 100ms |
| 调节美白 | 画面实时变化，延迟 < 100ms |
| 调节瘦脸 | 画面实时变化，延迟 < 100ms |
| 调节大眼 | 画面实时变化，延迟 < 100ms |
| 拍照保存 | 照片包含美颜效果 |

### 1.2 性能测试

| 测试项 | 目标值 | 测试机型 |
|--------|--------|----------|
| 预览帧率 | ≥ 30fps | 低端（骁龙 660） |
| 预览帧率 | ≥ 50fps | 中端（骁龙 778G） |
| 预览帧率 | ≥ 55fps | 高端（骁龙 8 Gen2） |
| 内存占用 | < 30MB | 所有机型 |
| 启动时间 | < 500ms | 所有机型 |

### 1.3 兼容性测试

- Android 8.0+（API 26+）
- 主流品牌：小米、华为、OPPO、vivo、三星
- 不同分辨率：720p、1080p、2K
- 不同摄像头：前置、后置、广角

---

## 2. RD 技术验收清单（按类/函数）

### 2.1 `BeautyPreviewView`
- [ ] `ensureOffscreenReady()` 在预览绑定前调用，且不会重复初始化。
- [ ] `getSurfaceForCamera()` 返回缓存 Surface，不重复创建对象。
- [ ] `bindDisplaySurface()` 仅在 `surface.isValid` 时绑定输出。
- [ ] `setCameraInputBufferSize()` 变更后同步更新 `SurfaceTexture` buffer size。

### 2.2 `CameraPreviewRenderer`
- [ ] `init()` 只做 EGL/纹理/SurfaceTexture 初始化，不提前启动渲染线程。
- [ ] `setRenderSurface()` 后 render thread 启动且可持续消费相机帧。
- [ ] `applyViewport()` 在 4:3 / 16:9 / FULL 下无拉伸变形。
- [ ] `updateFaceWarpParams()` 与 `uTextureTransform` 坐标空间一致。
- [ ] `getPerfStats()` 连续输出 `fps/processingMs/delayMs/cpuUsage/nullFrames`。

### 2.3 `GlBeautyPreviewProvider`
- [ ] `createPreviewSurface()` 在重试窗口内拿到有效 Surface。
- [ ] `updateFilters()` 参数范围映射正确（如 `slimFace / 50f * 1.35f`）。
- [ ] `isReady()` 在 `SurfaceTexture` 可用后返回 true。

### 2.4 `CameraScreen` 容灾链路
- [ ] warm-up 失败时统一走 `onGlWarmUpFallback(reason)`，并最终持久化到 `PIXEL_FREE`。
- [ ] 冷却到期后触发 `triggerManualGlEngineRecovery()` 自动重试。
- [ ] `PROVIDER_VIEW_BIND_TIMEOUT_MS` 超时后回落 `PreviewView` 并请求重绑。
- [ ] `useProviderRenderView` 状态与实际渲染容器一致（Provider View / PreviewView）。

---

## 3. 容灾回归步骤（手工）

1. 设置策略为 `R_PLAN`，进入相机，确认 R Plan 预览可见。
2. 人工注入初始化失败（例如抛异常），确认回退到 `PIXEL_FREE`。
3. 验证持久化写入：`beauty_strategy=PIXEL_FREE` 且有 `gl_engine_recovery_available_at_ms`。
4. 验证 `useProviderRenderView=false`，确认当前展示容器为 `PreviewView`。
5. 等待冷却窗口结束，确认自动触发 `triggerManualGlEngineRecovery()`。
6. 重试成功时恢复到 `R_PLAN`；重试失败时再次回退且可继续拍照。
7. 全程观察调试浮层与日志：`PerfStats`、fallback reason、剩余冷却秒数。

---

## 4. QA 执行版（P0/P1）

### 4.1 P0（发布阻断）
- [ ] `P0-01` 冷启动进入相机后，5s 内有可见预览，无黑屏无卡死。
- [ ] `P0-02` 美颜参数滑杆调整后 100ms 内体感可见变化。
- [ ] `P0-03` R Plan warm-up 失败可自动回退 PixelFree，且拍照功能可用。
- [ ] `P0-04` 冷却结束后可自动重试 R Plan，不出现循环崩溃。
- [ ] `P0-05` 调试浮层关键指标可读：`fps/processingMs/delayMs/cpuUsage/nullFrames`。

### 4.2 P1（质量增强）
- [ ] `P1-01` 4:3 / 16:9 / FULL 比例下人像无明显拉伸。
- [ ] `P1-02` 前后摄切换 10 次无预览挂死、无显著内存上涨。
- [ ] `P1-03` 连续拍照 30 次，回退状态下仍可稳定保存。
- [ ] `P1-04` 低端机 5 分钟连续预览，FPS 与空帧波动可接受。
- [ ] `P1-05` fallback reason 与 cooldown 剩余时间日志完整。

### 4.3 执行记录模板
- 设备型号 / 系统版本：
- 引擎策略：`R_PLAN` / `PIXEL_FREE`
- `useProviderRenderView`：true / false
- 持久化状态：`beauty_strategy` / `gl_engine_recovery_available_at_ms`
- 测试用例：`P0-01` ~ `P1-05`
- 结果：Pass / Fail
- 失败现象：
- 关键日志：
- 是否阻断发布：是 / 否

---

## 5. 全自动执行命令（CI / 本地）

### 5.1 统一门禁命令

```bash
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

### 5.2 分阶段命令（排障用）

```bash
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest
```

### 5.3 推荐 CI 步骤

1. 启动 Android 模拟器（API 34+，固定机型）。
2. 等待设备 ready（`adb wait-for-device`）。
3. 执行 `:app:testDebugUnitTest`。
4. 执行 `:app:connectedDebugAndroidTest`。
5. 收集报告：
   - `app/build/reports/tests/testDebugUnitTest/`
   - `app/build/reports/androidTests/connected/`
6. 用例失败即阻断发布。

---

## 6. P0/P1 自动化用例映射（Agent 执行）

| 用例 ID | 自动化层级 | 测试类（当前/目标） | 断言要点 | 状态 |
|--------|------------|---------------------|----------|------|
| P0-01 | androidTest(UI) | `CameraP0AutomationSkeletonTest` / `CameraScreenStartupTest` | 5 秒内预览容器可见，应用无崩溃 | 已建骨架 |
| P0-02 | androidTest(UI) | `CameraP0AutomationSkeletonTest` / `CameraBeautySliderLatencyTest` | 参数变更触发 UI 状态与 Provider 更新 | 已建骨架 |
| P0-03 | unit + androidTest | `CameraP0AutomationSkeletonTest` + `BeautyPreviewProviderFactoryTest` | R Plan init 失败后落到 `PIXEL_FREE`，且 `useProviderRenderView=false` | 已建骨架 |
| P0-04 | unit + androidTest | `CameraP0AutomationSkeletonTest` + `UserPreferencesRepositoryRecoveryInstrumentedTest` | 冷却到期触发恢复，失败后再次回退并更新持久化状态 | 已建骨架 |
| P0-05 | androidTest(UI) | `CameraP0AutomationSkeletonTest` / `CameraDebugOverlayMetricsTest` | 调试浮层可读到 `PerfStats` 五个字段 | 已建骨架 |
| P1-01 | unit | `CameraPreviewRendererViewportTest` | 4:3 / 16:9 / FULL 视口比例正确 | 待实现 |
| P1-02 | androidTest(UI) | `CameraLensSwitchStabilityTest` | 切前后摄 10 次无挂死 | 待实现 |
| P1-03 | androidTest(E2E) | `CameraCaptureFallbackStabilityTest` | 回退状态下连续拍照成功 | 待实现 |
| P1-04 | androidTest(perf smoke) | `CameraLowEndPerfSmokeTest` | 连续预览期间 FPS/空帧在阈值内 | 待实现 |
| P1-05 | unit + androidTest | `BeautyEngineRuntimeStateTest` + `CameraFallbackReasonTest` | reason 与 cooldown 日志完整 | 部分完成 |

> 说明：`当前`列对应仓库中已落地测试类，`目标`列为后续 Agent 持续细化的最终测试类。

---

## 7. Agent 团队执行顺序（无人值守）

1. RD Agent：补齐缺失测试类与 fake provider/fake clock。
2. QA Agent：将 `P0-01` ~ `P1-05` 写入流水线执行列表。
3. CR Agent：校验测试覆盖是否匹配本清单与 `R_PLAN_TECH_SPEC.md`。
4. PM Agent：仅验收结果，不介入手工测试。
5. 发布门禁：P0 全通过 + 无崩溃日志 + 报告归档完整。

