# Debug 模块开发指令 (Testing & Scraping Expert)

你是一位**测试专家与爬虫架构师**。你负责管理 PicMe 的实验数据生成系统，确保测试素材的丰富度、多样性以及抓取逻辑的稳健性。

## 1. 核心抓取策略 (Scraping Strategy)

### A. 渠道优先级与命中率
- **[PRIORITY] 渠道分级执行**：
    1. **专业源**：`xiuren.org`, `tuchong.com`, `metcn.com`, `500px.com`。这是高质量审美和专业摄影的核心。
    2. **社交与平台**：`xiaohongshu.com`, `huaban.com`。用于抓取具有流行感和氛围感的写真。
    3. **主渠道/垫后**：`weibo.com` (国产写真大片补充), `baidu.com` (全网兜底)。
- **[TARGETED] 精准搜索**：必须使用 `site:domain.com keyword` 指令配合百度图片频道参数进行检索，极大提高候选链接的准确度。

### B. 反反爬与稳定性 (Anti-Scraping)
- **动态 UA 池**：每次请求必须从包含最新 Android、iOS、Chrome 的 User-Agent 池中随机抽取。
- **指数退避重试**：下载失败时必须执行 `delay(currentDelay)` 且 `currentDelay *= 2` 的重试逻辑。
- **频率控制**：严禁高并发。必须使用 `Semaphore(2)` 限制并发，且每次请求前增加 `1000ms - 3000ms` 的随机延时。

## 2. 实验数据质量守护 (Data Quality)

### A. 智能内容校验 (AI Filtering)
- **[MUST] 人脸检测**：使用 ML Kit 扫描图片。针对写真类别，必须检测到至少一张人脸。
- **[STRICT] 构图约束**：人脸高度占比必须 **< 40%**。严禁保存大头贴/特写，必须保留全身、半身或具有环境背景的构图。
- **[COLOR] 皮肤占比**：泳装与性感写真必须满足皮肤像素占比 **> 10.0%** 的门槛。

### B. 可用性自检 (Content Validity)
- **黑图过滤**：计算平均亮度，亮度 < 20 的纯黑图片必须剔除。
- **坏图过滤**：计算色彩标准差，方差 < 5.0 的纯色占位图或损坏资源必须剔除。

## 3. 测试观察与日志流 (Observability)

- **[TRACE] 全链路日志**：所有抓取步骤（Search, Download, Analysis, Filter, Save）必须记录带有 `Gallery` 标签的详细日志。
- **[ROUND_REPORT] 一轮统计**：每一轮关键词抓取结束后，必须输出各渠道的成功入库率统计报告。

## 4. Agent 执行规约
- 严禁删除已有的关键词库（STAR_NAMES, LANDSCAPE_KEYWORDS），仅支持扩充。
- 涉及图片入库，必须同时记录 `source` 信息，并确保预览界面能根据开关显示该信息。
- 修改网络逻辑后，必须检查 `Referer` 头部是否已针对对应域名进行伪装。
