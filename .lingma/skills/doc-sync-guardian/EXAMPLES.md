# DocSync Guardian 使用示例

本文档提供常见使用场景的完整示例。

---

## 示例 1: 日常审计工作流

### 场景描述
每周一上午执行全量文档审计，确保项目文档保持最新。

### 执行步骤

```bash
# 1. 进入项目根目录
cd /Users/guoshuai/AndroidStudioProjects/PicMe

# 2. 运行综合审计
python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py
```

### 预期输出

```
📊 开始生成综合审计报告...
   运行文档一致性检查...
   运行 I18N 同步检查...

✅ 综合审计报告已生成: /Users/guoshuai/AndroidStudioProjects/PicMe/docs/comprehensive_audit_20260503_090000.md
📖 请查看报告并采取相应行动

============================================================
📊 审计报告摘要
============================================================
报告文件: docs/comprehensive_audit_20260503_090000.md
生成时间: 2026-05-03 09:00:00
============================================================
```

### 后续行动

```bash
# 3. 查看审计报告
code docs/comprehensive_audit_20260503_090000.md

# 4. 根据报告中的优先级建议修复问题
# 5. 修复后重新运行审计验证
python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py
```

---

## 示例 2: 新功能交付后的文档同步

### 场景描述
刚刚完成了"曝光滑杆实时预览"功能，需要同步更新相关文档。

### 执行步骤

```bash
# 1. 获取最新 commit hash
git log -1 --format=%H
# 输出: abc123def456789

# 2. 生成文档更新草案
python3 .lingma/skills/doc-sync-guardian/scripts/sync-doc-template.py \
  --commit-hash abc123def456789 \
  --output /tmp/exposure_slider_doc_update.md

# 3. 查看草案
code /tmp/exposure_slider_doc_update.md
```

### 草案内容示例

```markdown
# 📝 文档同步更新草案

**生成时间**: 2026-05-03 10:30:00  
**Commit Hash**: `abc123def456789`  
**变更文件数**: 1 个模块  

---

## 📊 变更分析

### 影响的模块
- Camera

### 变更类型
- ui_component
- shader

---

## 🔄 需要更新的文档

### Camera 模块

**Section 2: 技术实现规范**
- [ ] 补充 Composable 组件结构说明
- [ ] 更新状态管理方案（remember/StateFlow）
- [ ] 说明动画和过渡效果实现

**Section 4: 检查清单**
- [ ] 可访问性 (contentDescription) 是否完整？
- [ ] 深色/浅色模式适配是否正确？
- [ ] 多语言文案是否提取到 strings.xml？

**Section 5: 产品对照**
- [ ] 更新产品指标对应关系
- [ ] 补充技术决策记录

---

## ✅ 通用检查清单

- [ ] 三层文档术语保持一致
- [ ] 所有 markdown 链接有效
- [ ] 代码示例可以正常编译
- [ ] I18N 文案同步三语资源
```

### 根据草案更新文档

```bash
# 4. 更新 PRODUCT.md
# 在 Section 3.1 添加性能指标
echo "- **曝光调节响应延迟**: < 100ms" >> temp_product_update.txt

# 5. 更新 FEATURES.md
# 在 Section 1.2 补充交互说明
cat >> temp_features_update.txt << EOF
- **曝光滑杆触感反馈**: 滑动时触发轻微震动，增强操作确认感
- **实时预览**: 参数调整立即生效，无需等待
EOF

# 6. 更新 Camera AGENTS.md
# 在 Section 2 添加技术实现
# 在 Section 4 添加检查清单
# 在 Section 5 更新产品对照

# 7. 提交文档更新
git add PRODUCT.md docs/FEATURES.md app/src/main/java/com/picme/features/camera/AGENTS.md
git commit -m "docs: 同步曝光滑杆功能文档

- PRODUCT.md: 补充响应延迟指标
- FEATURES.md: 添加触感反馈说明
- Camera AGENTS.md: 完善状态管理和检查清单"
```

### 验证一致性

```bash
# 8. 运行一致性检查
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh

# 9. 检查 I18N 同步
python3 .lingma/skills/doc-sync-guardian/scripts/check-i18n-sync.py
```

---

## 示例 3: Code Review 中的文档检查

### 场景描述
正在审查一个 PR，需要确认文档是否同步更新。

### 执行步骤

```bash
# 1. 切换到 PR 分支
git checkout feature/new-beauty-filter

# 2. 运行文档一致性检查
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh

# 3. 检查 I18N 同步
python3 .lingma/skills/doc-sync-guardian/scripts/check-i18n-sync.py
```

### CR 检查清单

在 PR 评论中添加：

```markdown
## 📝 文档审查结果

### ✅ 通过项
- [x] PRODUCT.md 已更新性能指标
- [x] FEATURES.md 已补充交互说明
- [x] BeautyEngine AGENTS.md 第 5 章完整

### ⚠️ 需要修复
- [ ] I18N 缺少繁体中文翻译（19 个字符串）
  - 运行 `python3 .lingma/skills/doc-sync-guardian/scripts/check-i18n-sync.py` 查看详情
  - 补充 `app/src/main/res/values-zh-rTW/strings.xml` 中的缺失项

### ❌ 阻塞问题
无

**建议**: 修复 I18N 问题后即可合并
```

---

## 示例 4: 记录重大技术决策

### 场景描述
完成了"拍照 GPU 化"架构迁移，需要记录技术决策。

### 执行步骤

#### Step 1: 创建技术专项文档

```bash
# 创建新文档
cat > docs/PHOTO_GPU_TECH_SPEC.md << 'EOF'
# 拍照 GPU 化技术方案

**版本**: 1.0  
**日期**: 2026-05-03  
**作者**: [RD 姓名]  

---

## 1. 背景与问题

### 当前问题
- 预览与拍照效果不一致（70-85%）
- CPU Canvas 路径处理慢（1080p: 800-1200ms, 4K: 3-5s）
- 两套独立实现导致维护成本高

### 目标
- 效果一致性 ≥ 99%（像素级对齐）
- 处理速度提升：1080p < 300ms, 4K < 800ms
- 复用预览 Shader 链路，降低维护成本

---

## 2. 备选方案

### 方案 A: GPU 离屏渲染（最终选择）
**优点**:
- 完全复用预览 Shader，一致性 99%+
- GPU 并行处理，性能提升 3-5 倍
- 架构统一，易于维护

**缺点**:
- EGL 上下文管理复杂
- 需要降级策略应对失败场景
- 初期开发成本高

### 方案 B: 优化 CPU Canvas 路径
**优点**:
- 实现简单，风险低
- 无需引入 EGL 管理

**缺点**:
- 性能提升有限（预计 30-50%）
- 仍无法解决一致性问题
- 长期维护成本高

### 方案 C: 混合方案（GPU + CPU  fallback）
**优点**:
- 兼顾性能和稳定性
- 降级路径清晰

**缺点**:
- 架构复杂度高
- 需要维护两套代码

---

## 3. 最终决策

选择 **方案 A（GPU 离屏渲染）**，理由：
1. **一致性优先**: 产品核心体验要求预览即所得
2. **性能达标**: GPU 路径满足 < 300ms 指标
3. **长期收益**: 架构统一降低维护成本

---

## 4. 实施计划

### Phase 1: 基础框架（1 周）
- [ ] EGL Offscreen Context 封装
- [ ] Texture -> PBO -> JPEG 编码链路
- [ ] 降级策略实现

### Phase 2: Shader 复用（1 周）
- [ ] 提取预览 Shader 为独立模块
- [ ] 拍照链路接入 Shader
- [ ] 单元测试编写

### Phase 3: 性能优化（1 周）
- [ ] 内存池优化
- [ ] 异步处理流水线
- [ ] 真机性能测试

### Phase 4: 灰度发布（2 周）
- [ ] 5% 用户灰度
- [ ] 监控回退率
- [ ] 全量发布

---

## 5. 风险评估

### 风险 1: EGL 上下文创建失败
**概率**: 低  
**影响**: 高（拍照失败）  
**缓解措施**: 
- 实现多级降级（EGL -> CPU Canvas）
- 添加详细日志便于定位
- 灰度阶段密切监控

### 风险 2: OOM（内存溢出）
**概率**: 中  
**影响**: 高（应用崩溃）  
**缓解措施**:
- 使用内存池复用 Buffer
- 4K 照片分块处理
- 添加内存监控和预警

### 风险 3: 兼容性问题
**概率**: 中  
**影响**: 中（部分机型异常）  
**缓解措施**:
- 覆盖主流机型测试（小米、华为、OPPO、vivo）
- 建立设备兼容性矩阵
- 提供手动切换引擎入口

---

## 6. 验收标准

- [ ] 效果一致性 ≥ 99%（像素对比测试）
- [ ] 1080p 处理 < 300ms（100 次平均）
- [ ] 4K 处理 < 800ms（100 次平均）
- [ ] 降级成功率 100%（模拟 EGL 失败场景）
- [ ] 灰度阶段回退率 < 1%
- [ ] 无新增崩溃（Crash-free rate ≥ 99.9%）

---

## 7. 相关文档

- PRODUCT.md: Section 3.1 美颜系统 - 拍照 GPU 化
- FEATURES.md: Section 1.3.5 大美丽引擎策略
- Camera AGENTS.md: Section 2.3 拍照后处理
- BIG_BEAUTY_TECH_SPEC.md: Section 6 拍照 GPU 化方案

---

## 8. 修订历史

| 版本 | 日期 | 修订内容 | 修订者 |
|------|------|---------|--------|
| 1.0 | 2026-05-03 | 初始版本 | [RD] |
EOF
```

#### Step 2: 更新三层文档引用

```bash
# 更新 PRODUCT.md
# 在 Section 3.1 添加：
# "技术方案详见 `docs/PHOTO_GPU_TECH_SPEC.md`"

# 更新 FEATURES.md
# 在 Section 1.3.5 添加：
# "技术实现详见 `docs/PHOTO_GPU_TECH_SPEC.md`"

# 更新 Camera AGENTS.md
# 在 Section 2.3 添加：
# "架构设计参考 `docs/PHOTO_GPU_TECH_SPEC.md Section 6`"
```

#### Step 3: 提交并通知团队

```bash
git add docs/PHOTO_GPU_TECH_SPEC.md PRODUCT.md docs/FEATURES.md app/src/main/java/com/picme/features/camera/AGENTS.md
git commit -m "docs: 记录拍照 GPU 化技术决策

- 创建 PHOTO_GPU_TECH_SPEC.md 记录完整技术方案
- 更新 PRODUCT.md 性能指标和验收标准
- 同步 FEATURES.md 用户体验目标
- 关联 Camera AGENTS.md 实现细节

技术决策要点:
- 选择 GPU 离屏渲染方案，一致性 ≥ 99%
- 性能目标: 1080p < 300ms, 4K < 800ms
- 降级策略: EGL 失败时自动回退 CPU 路径
- 灰度计划: 5% -> 20% -> 50% -> 100%"

# 通知团队
echo "📢 重大技术决策已记录: 拍照 GPU 化方案"
echo "📄 详见: docs/PHOTO_GPU_TECH_SPEC.md"
echo "🔗 PR: [链接]"
```

---

## 示例 5: 清理过时文档

### 场景描述
发现顶层 AGENTS.md 过长（600+ 行），包含大量模块级实现细节，需要瘦身。

### 执行步骤

```bash
# 1. 检查顶层 AGENTS.md 长度
wc -l AGENTS.md
# 输出: 623 AGENTS.md

# 2. 识别需要下沉的内容
grep -n "```kotlin\|```java" AGENTS.md
# 输出: 包含代码示例的行号

# 3. 创建备份
cp AGENTS.md AGENTS.md.backup
```

#### 内容分流

```markdown
## 从顶层 AGENTS.md 移除的内容

### 移至 Camera AGENTS.md
- 相机预览初始化流程（含代码示例）
- ImageAnalysis 配置参数表
- 人脸检测引擎切换逻辑

### 移至 BeautyEngine AGENTS.md
- 磨皮算法实现细节
- Shader 参数说明
- 多 Pass 渲染顺序

### 移至技术专项文档
- MediaPipe 468→106 映射详解 → `docs/MEDIAPIPE_MAPPING_TECH_SPEC.md`
- EGL 离屏渲染架构 → `docs/OFFSCREEN_RENDERING_TECH_SPEC.md`

## 保留在顶层 AGENTS.md 的内容

- 角色协作机制（CO/PM/RD/CR/QA）
- 全局红线（PRIVACY / PERF / I18N）
- Self-Heal 执行流程
- 文档治理规则
```

#### 执行瘦身

```bash
# 4. 编辑顶层 AGENTS.md，删除模块级细节
# 5. 在删除位置添加引用
cat >> AGENTS.md << 'EOF'

## 模块实现文档索引

- Camera 模块: `app/src/main/java/com/picme/features/camera/AGENTS.md`
- Gallery 模块: `app/src/main/java/com/picme/features/gallery/AGENTS.md`
- BeautyEngine: `beauty-engine/AGENTS.md`

## 技术专项文档索引

- 相机预览: `docs/CAMERA_PREVIEW_TECH_SPEC.md`
- 大美丽引擎: `docs/BIG_BEAUTY_TECH_SPEC.md`
- MediaPipe 映射: `docs/MEDIAPIPE_MAPPING_TECH_SPEC.md`
EOF

# 6. 验证瘦身效果
wc -l AGENTS.md
# 期望输出: ~300 AGENTS.md（减少 50%）

# 7. 运行一致性检查
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh

# 8. 提交更改
git add AGENTS.md app/*/AGENTS.md docs/*_TECH_SPEC.md
git commit -m "docs: 顶层 AGENTS.md 瘦身

- 移除模块级实现细节（~300 行）
- 下沉到对应模块 AGENTS.md 和技术专项文档
- 添加双向引用链接
- 保留治理骨架（角色、红线、流程）"
```

---

## 常见问题排查

### 问题 1: 脚本执行权限不足

**症状**:
```
Permission denied: .lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh
```

**解决**:
```bash
chmod +x .lingma/skills/doc-sync-guardian/scripts/*.sh
chmod +x .lingma/skills/doc-sync-guardian/scripts/*.py
```

### 问题 2: Python 版本过低

**症状**:
```
SyntaxError: invalid syntax
```

**解决**:
```bash
# 检查 Python 版本
python3 --version
# 需要 3.9+

# 如果版本过低，升级 Python
brew install python@3.11  # macOS
```

### 问题 3: 找不到项目根目录

**症状**:
```
❌ 文件不存在: /wrong/path/PRODUCT.md
```

**解决**:
```bash
# 确保在项目根目录执行
cd /Users/guoshuai/AndroidStudioProjects/PicMe

# 或者修改脚本中的路径查找逻辑
```

### 问题 4: I18N 检查报告为空

**症状**:
```
✅ 加载 en: 0 个字符串
✅ 加载 zh-CN: 0 个字符串
```

**解决**:
```bash
# 检查 strings.xml 文件是否存在
ls -la app/src/main/res/values*/strings.xml

# 检查 XML 格式是否正确
xmllint --noout app/src/main/res/values/strings.xml
```

---

## 进阶技巧

### 技巧 1: 自定义检查规则

编辑 `check-doc-consistency.sh`，添加自定义检查：

```bash
# 在脚本末尾添加

###############################################################################
# 自定义检查: 检查是否有 TODO 注释未处理
###############################################################################
echo -e "${YELLOW}[6/6] 检查未处理的 TODO 注释...${NC}"

TODO_COUNT=$(grep -r "TODO\|FIXME" app/src/main/java --include="*.kt" --include="*.java" | wc -l)

if [ "$TODO_COUNT" -gt 10 ]; then
    WARN_ITEMS+=("发现 $TODO_COUNT 个未处理的 TODO/FIXME 注释")
    ((WARN_COUNT++))
else
    PASS_ITEMS+=("TODO 注释数量合理 ($TODO_COUNT 个)")
    ((PASS_COUNT++))
fi
```

### 技巧 2: 集成到 Git Hook

创建 `.git/hooks/pre-commit`：

```bash
#!/bin/bash
# Pre-commit hook: 自动检查文档一致性

echo "🔍 执行文档一致性检查..."

# 运行检查
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh

# 如果检查失败，阻止提交
if [ $? -ne 0 ]; then
    echo ""
    echo "⚠️  文档一致性检查失败，请修复后再提交"
    echo "💡 提示: 查看 docs/audit_report_*.md 了解详情"
    exit 1
fi

echo "✅ 文档一致性检查通过"
```

### 技巧 3: 定时审计（Cron Job）

```bash
# 编辑 crontab
crontab -e

# 添加每周一定时任务
0 9 * * 1 cd /Users/guoshuai/AndroidStudioProjects/PicMe && \
  python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py >> \
  /var/log/picme-doc-audit.log 2>&1
```

---

**最后更新**: 2026-05-03  
**维护者**: [CO] 协调者
