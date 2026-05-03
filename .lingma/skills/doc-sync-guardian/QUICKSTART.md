# 🚀 DocSync Guardian 快速开始指南

## ⏱️ 5 分钟上手

### Step 1: 验证安装（30 秒）

```bash
cd /Users/guoshuai/AndroidStudioProjects/PicMe

# 检查文件是否存在
ls -la .lingma/skills/doc-sync-guardian/SKILL.md
ls -la .lingma/skills/doc-sync-guardian/scripts/*.py
ls -la .lingma/skills/doc-sync-guardian/scripts/*.sh

# 确认执行权限
chmod +x .lingma/skills/doc-sync-guardian/scripts/*
```

### Step 2: 运行首次审计（2 分钟）

```bash
# 生成综合审计报告
python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py
```

**预期输出**:
```
📊 开始生成综合审计报告...
   运行文档一致性检查...
   运行 I18N 同步检查...

✅ 综合审计报告已生成: docs/comprehensive_audit_20260503_HHMMSS.md
```

### Step 3: 查看审计结果（1 分钟）

```bash
# 打开最新报告
ls -lt docs/comprehensive_audit_*.md | head -1 | awk '{print $NF}' | xargs code
```

**重点关注**:
- ❌ 错误项（必须修复）
- ⚠️ 警告项（建议修复）
- ✅ 通过项（保持）

### Step 4: 修复发现的问题（按需）

根据报告中的优先级建议进行修复。

---

## 📋 常用命令速查

### 日常审计

```bash
# 全量审计（推荐每周执行）
python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py

# 仅检查文档一致性
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh

# 仅检查 I18N 同步
python3 .lingma/skills/doc-sync-guardian/scripts/check-i18n-sync.py
```

### 功能交付后

```bash
# 生成文档更新草案
python3 .lingma/skills/doc-sync-guardian/scripts/sync-doc-template.py \
  --commit-hash HEAD \
  --output /tmp/doc_update.md

# 查看草案
code /tmp/doc_update.md
```

### 查看历史报告

```bash
# 列出所有审计报告
ls -lt docs/*audit*.md

# 查看特定报告
code docs/comprehensive_audit_20260503_090000.md
```

---

## 🎯 典型工作流

### 工作流 1: 每周例行审计

```bash
# 周一上午 9 点
cd /Users/guoshuai/AndroidStudioProjects/PicMe

# 1. 运行审计
python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py

# 2. 查看报告
ls -lt docs/comprehensive_audit_*.md | head -1 | awk '{print $NF}' | xargs code

# 3. 修复高优先级问题
# ... 根据报告内容修复 ...

# 4. 重新审计验证
python3 .lingma/skills/doc-sync-guardian/scripts/generate-audit-report.py

# 5. 提交修复
git add docs/*.md
git commit -m "docs: 修复审计发现的问题

- 补充缺失的 I18N 翻译
- 更新过时的技术文档
- 清理废弃引用"
```

### 工作流 2: 新功能交付

```bash
# 1. 完成代码开发并提交
git add .
git commit -m "feat: 实现曝光滑杆实时预览"

# 2. 生成文档更新草案
COMMIT_HASH=$(git log -1 --format=%H)
python3 .lingma/skills/doc-sync-guardian/scripts/sync-doc-template.py \
  --commit-hash $COMMIT_HASH \
  --output /tmp/doc_draft.md

# 3. 根据草案更新文档
code /tmp/doc_draft.md
# 手动编辑 PRODUCT.md, FEATURES.md, AGENTS.md

# 4. 验证一致性
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh
python3 .lingma/skills/doc-sync-guardian/scripts/check-i18n-sync.py

# 5. 提交文档更新
git add PRODUCT.md docs/FEATURES.md app/*/AGENTS.md
git commit -m "docs: 同步曝光滑杆功能文档"
```

### 工作流 3: Code Review

```bash
# 在 PR 审查时

# 1. 切换到 PR 分支
git checkout feature/xxx

# 2. 运行检查
./.lingma/skills/doc-sync-guardian/scripts/check-doc-consistency.sh
python3 .lingma/skills/doc-sync-guardian/scripts/check-i18n-sync.py

# 3. 在 PR 评论中添加检查结果
# 复制脚本输出到 PR 评论
```

---

## 🔍 解读审计报告

### 报告结构

```markdown
# 📊 PicMe 项目综合审计报告

## 🔍 审计范围
## 📋 执行检查
  ### 1. 文档一致性检查
  ### 2. I18N 三语资源同步检查
## 📈 总体评估
## 🎯 下一步行动
```

### 关键指标

| 指标 | 含义 | 目标 |
|------|------|------|
| 文档引用链完整性 | 三层文档是否有断裂 | 100% |
| I18N 同步率 | 三语资源键数量一致 | 100% |
| 模块规范符合率 | AGENTS.md 第 5 章完整 | 100% |

### 优先级说明

- 🔴 **高优先级**: 必须立即修复（如文档与代码冲突）
- 🟡 **中优先级**: 本周内修复（如过时文档）
- 🟢 **低优先级**: 有空时优化（如结构调整）

---

## 💡 最佳实践

### ✅ 推荐做法

1. **定期审计**: 每周执行一次全量审计
2. **及时更新**: 代码变更后立即更新相关文档
3. **小步快跑**: 增量更新优于批量重构
4. **双向链接**: 保持三层文档的可追溯性
5. **自动化检查**: 集成到 CI/CD 流程

### ❌ 避免做法

1. **积累变更**: 不要等到版本发布前才更新文档
2. **忽略警告**: 警告项可能演变为错误项
3. **单向引用**: 只从产品到技术，缺少反向追溯
4. **硬编码路径**: 使用相对路径而非绝对路径
5. **过度详细**: 顶层文档保持简洁，细节下沉

---

## 🛠️ 故障排查

### 问题: 脚本执行失败

```bash
# 检查 Python 版本
python3 --version  # 需要 3.9+

# 检查执行权限
ls -la .lingma/skills/doc-sync-guardian/scripts/*

# 修复权限
chmod +x .lingma/skills/doc-sync-guardian/scripts/*
```

### 问题: 找不到文件

```bash
# 确保在项目根目录
pwd  # 应该输出: /Users/guoshuai/AndroidStudioProjects/PicMe

# 如果不在，切换目录
cd /Users/guoshuai/AndroidStudioProjects/PicMe
```

### 问题: 报告为空

```bash
# 检查文档是否存在
ls -la PRODUCT.md docs/FEATURES.md

# 检查 Git 仓库
git status
```

---

## 📚 深入学习

### 文档层次

1. **SKILL.md**: 完整的 Skill 使用指南（724 行）
2. **README.md**: 快速开始和常见问题（380 行）
3. **EXAMPLES.md**: 实际使用示例（622 行）
4. **本文件**: 5 分钟快速上手

### 推荐阅读顺序

1. 📖 **新手**: 本文件 → README.md → 运行审计
2. 📖 **进阶**: EXAMPLES.md → 理解工作流
3. 📖 **专家**: SKILL.md → 自定义规则

### 相关文档

- [AGENTS.md](../../AGENTS.md) - 顶层治理规范
- [docs/AGENTS_SPEC.md](../../docs/AGENTS_SPEC.md) - 文档编写规范
- [PRODUCT.md](../../PRODUCT.md) - 产品需求
- [docs/FEATURES.md](../../docs/FEATURES.md) - 交互细节

---

## 🆘 获取帮助

### 遇到问题？

1. **查看 README.md**: 常见问题解答
2. **查看 EXAMPLES.md**: 类似场景示例
3. **查看 SKILL.md**: 完整技术文档
4. **提交 Issue**: 在项目 Issues 中反馈

### 联系方式

- **项目 Issues**: GitHub Issues
- **团队讨论**: Slack #picme-dev
- **文档维护者**: [CR] 规范守护者

---

## 🎉 下一步

现在你已经掌握了基础知识，可以：

1. ✅ **运行首次审计**: `python3 scripts/generate-audit-report.py`
2. ✅ **阅读完整文档**: `code README.md`
3. ✅ **查看使用示例**: `code EXAMPLES.md`
4. ✅ **集成到工作流**: 添加到 CI/CD 或 Git Hook

祝你使用愉快！🚀

---

**最后更新**: 2026-05-03  
**版本**: 1.0  
**维护者**: [CO] 协调者
