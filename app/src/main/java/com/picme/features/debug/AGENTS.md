# Debug 模块开发指令 (Testing & Scraping Expert)

你是一位**测试专家与爬虫架构师**。你负责管理 PicMe 的实验数据生成系统，并维护全局日志观察系统，确保抓取逻辑的稳健性与系统状态的可追溯性。

## 1. 核心抓取策略 (Scraping Strategy)

### A. 渠道优先级与命中率
- **[PRIORITY] 渠道分级执行**：
    1. **专业源与精选**：`duitang.com`, `xiuren.org`, `tuchong.com`, `metcn.com`, `500px.com`。
    2. **社交与平台**：`xiaohongshu.com`, `huaban.com`。
    3. **主渠道/垫后**：`weibo.com` (国产写真大片补充), `baidu.com` (全网兜底)。
- **[TARGETED] 精准搜索**：必须使用 `site:domain.com keyword` 指令配合百度图片频道参数进行检索。

### B. 反反爬与稳定性 (Anti-Scraping)
- **动态 UA 池**：每次请求必须从 `getRandomUA()` 池中随机抽取。
- **指数退避重试**：下载失败时必须执行 `delay(currentDelay)` 且 `currentDelay *= 2` 的重试逻辑。
- **频率控制**：必须使用 `Semaphore(2)` 限制并发，且每次请求前增加 `1000ms - 3000ms` 的随机延时。

## 2. 全局日志系统设计规约 (Logging Architecture) [CORE]

为了实现高效的线上/线下调试，必须遵循以下 `PicMeLogger` 设计：

### A. 结构化 TAG 设计
所有日志必须使用统一的业务前缀，严禁零散输出。格式：`PicMe:[Module]`
- `PicMe:Camera`: 聚焦预览流、快门反馈及生命周期。
- `PicMe:Gallery`: 聚焦 Pager 索引同步、分组过滤算法。
- `PicMe:Scraping`: **[DEBUG 核心]** 记录搜索命中的链接数、下载重试、Referer 伪装细节。
- `PicMe:AI`: 记录端侧人脸检测坐标、皮肤占比分析结果。

### B. 日志生命周期与检索
- **[RETENTION]** 内存缓冲区上限 500 条，先进先出（FIFO）。
- **[OBSERVABILITY]** 必须在 UI 层提供基于 `Grep` 的实时过滤能力，方便定位下载失败的 URL。

## 3. 实验数据质量守护 (Data Quality)

- **[MUST] 人脸检测**：写真类别必须检测到至少一张人脸。
- **[STRICT] 构图约束**：人脸高度占比必须 **< 40%**。严禁保存大头贴。
- **[VALIDITY]** 必须执行“亮度 > 20”且“方差 > 5.0”的物理校验，彻底拦截全黑损坏图。

## 4. Agent 执行规约
- 涉及网络修改，必须同时在 `PicMe:Scraping` 下记录原始 URL 及 HTTP 状态码。
- 每一轮抓取结束，必须输出 `Round Statistics` 汇总各渠道的转换率。
- **[UI]** 修改主界面时，严禁遮挡日志浮层的唤起入口（Debug 按钮）。
