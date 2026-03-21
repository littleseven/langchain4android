# 相册模块智能代理指南 (Gallery Module Agents)

本文件详细说明了相册模块的设计模式和技术实现，指导 AI 代理进行功能扩展和维护，所有文字必须支持多语言（中文、英文、繁体中文）。

## 目录结构 (符合 AGENTS.md)
根据全局 `AGENTS.md` 规范，相册模块位于 `features/gallery/`：
- `components/`: 包含 `MediaGrid.kt`, `MediaGroupHeader.kt`, `MediaPager.kt` 等组件。
- `GalleryScreen.kt`: 顶层 Compose 页面，处理相册网格显示与全屏预览的逻辑切换。
- `MediaViewModel.kt`: 核心状态持有者，负责媒体数据的流式读取、智能分组逻辑。

## 核心功能
- **智能分组**: 默认为全部（All），支持按日期 (Date)、人脸 (Face) 、风景(Landscape) 以及特定人物 (Person) 进行聚类展示并显示个数，在某一聚类下，不满足聚类要求的图片不展示，例如风景不在人脸和特定人物聚类下展示，反之亦然。在聚类下默认按照最新时间排序。
- **批量管理**: 长按进入选择模式，支持多选、全选及批量删除。
- **无缝预览**: 整合 Pager 模式，支持图片缩放和视频自动播放，确保预览与网格排序逻辑一致，从缩略图到全屏预览的切换 seamlessly,可用动画过度。

## 技术实现
- **数据流**: `MediaViewModel` 通过 `combine` 操作符实时生成 `MediaGroup` 列表。
- **列表性能**: 使用 `LazyVerticalGrid` 实现分组布局。
- **媒体渲染**: 使用 Coil 渲染缩略图，集成 Media3 ExoPlayer 播放视频。

## 代理工作准则
1. **分组一致性**: 新增分组模式时，必须在 `MediaViewModel` 中实现 `GetGroupedMediaUseCase` 的调用。
2. **选择模式**: 处理系统的返回键以退出选择模式，并在 UI 上提供清晰的反馈。
3. **Pager 同步**: 确保全屏预览的顺序与网格排序逻辑完全一致。
4. **资源清理**: 视频播放组件必须正确管理生命周期，避免内存泄漏。
