# Gallery 模块开发指令 (Module-Specific Instructions)

你是媒体管理与相册专家。在处理 `features/gallery/` 目录下的任务时，必须遵守以下指令。

## 1. 核心产品逻辑 (Gallery Product Logic)

### A. 智能分组与过滤 (Smart Grouping)
- **[EXCLUSIVITY] 分组排他性**：当用户选择特定分组（如“风景”）时，ViewModel 必须过滤掉所有不含 `Landscape` 标签的媒体，严禁混合显示。
- **[AUTO_SORT] 自动排序**：新增拍摄的媒体必须立即出现在“全部”和相应分组的最前端（`DATE_TAKEN DESC`）。
- **[COUNT_SYNC] 计数同步**：分组标题旁的数字必须实时反映过滤后的结果数量。
- **[DUPLICATE_DETECT] 重复检测**：必须提供基于 MD5 和感知哈希的双重检测机制，识别完全重复和相似图片。

### B. 交互行为规范 (UX Rules)
- **[ZOOM_TRANSITION] 缩放过渡**：从网格点击到 Pager 必须使用基于 `Modifier.onGloballyPositioned` 获取的位置进行缩放动画。
- **[MULTI_SELECT] 批量管理**：
  - 长按触发：震动反馈 + 选中当前项 + 进入选择模式。
  - 选择模式下：点击项仅切换选中状态，不打开预览。
- **[DELETE_CONFIRM] 删除防呆**：执行删除前必须弹出对话框，并明确告知“此操作将从磁盘删除文件”。
- **[DUPLICATE_UI] 重复管理界面**：
  - 必须提供独立的重复管理器界面，通过顶部栏的图标入口访问。
  - 每组重复图片必须显示缩略图预览（最多 3 张）。
  - 必须支持单组快速删除（保留第一张，删除其余）。
  - 必须支持批量删除所有重复项。

## 2. 模块 SOP (标准作业程序)
1. 修改 Pager 特性前，必须确认索引逻辑与 `MediaGrid` 点击位置的 `allFlatMedia` 索引完全一致。
2. 任何涉及数据库的操作，必须通过 `MediaViewModel` 调用 `repository`，严禁在 UI 层直接操作 DAO。

## 3. 技术约束
- 图片加载必须配置 `crossfade(true)` 以保证视觉丝滑。
- 视频缩略图若首帧为全黑，必须回退提取第 1 秒。
