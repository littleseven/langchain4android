# GitHub Wiki 发布指南

本文档说明如何将 `wiki/` 目录的文档发布到 GitHub Wiki。

## 📋 前置条件

1. **GitHub 账号**: 已拥有 `littleseven/PicMe` 仓库的管理权限
2. **Git 客户端**: 已安装 Git (macOS/Linux/Windows)
3. **GitHub CLI (可选)**: 用于命令行操作 (`brew install gh`)

---

## 🚀 发布步骤

### 方法 1: Git 推送 (推荐)

这是最灵活的方式,支持版本控制和批量更新。

#### Step 1: 克隆 Wiki 仓库

```bash
cd /tmp
git clone https://github.com/littleseven/PicMe.wiki.git
cd PicMe.wiki
```

#### Step 2: 复制 Wiki 文档

```bash
# 从项目 wiki 目录复制所有 .md 文件
cp /Users/guoshuai/AndroidStudioProjects/PicMe/wiki/*.md .
```

#### Step 3: 查看变更

```bash
git status
```

应该看到类似输出:
```
On branch master
Your branch is up to date with 'origin/master'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
        Architecture-Decisions.md
        Architecture-Overview.md
        Beauty-Engine.md
        Face-Detection-Engines.md
        Home.md
        Quick-Start.md
        README.md
```

#### Step 4: 提交并推送

```bash
git add .
git commit -m "docs: 初始化 Wiki 文档 (2026-05)

- Home.md: Wiki 主页,项目概览与快速导航
- Quick-Start.md: 环境配置与构建指南
- Architecture-Overview.md: Clean Architecture + 单引擎设计
- Beauty-Engine.md: 大美丽实时美颜系统详解
- Face-Detection-Engines.md: InsightFace + MediaPipe 双引擎架构
- Architecture-Decisions.md: ADR-001/002/003 关键技术决策
- README.md: Wiki 维护说明"

git push origin master
```

#### Step 5: 验证发布

访问: https://github.com/littleseven/PicMe/wiki

确认所有页面正常显示,链接无死链。

---

### 方法 2: GitHub CLI (快速)

适合首次创建或少量页面更新。

#### Step 1: 安装 GitHub CLI

```bash
# macOS
brew install gh

# Linux
sudo apt install gh

# Windows
winget install GitHub.cli
```

#### Step 2: 认证 GitHub

```bash
gh auth login
```

按照提示完成浏览器认证。

#### Step 3: 创建 Wiki 页面

```bash
cd /Users/guoshuai/AndroidStudioProjects/PicMe

# 逐个创建页面
gh wiki create Home --content "./wiki/Home.md" --title "Home"
gh wiki create Quick-Start --content "./wiki/Quick-Start.md" --title "Quick Start"
gh wiki create Architecture-Overview --content "./wiki/Architecture-Overview.md" --title "Architecture Overview"
gh wiki create Beauty-Engine --content "./wiki/Beauty-Engine.md" --title "Beauty Engine"
gh wiki create Face-Detection-Engines --content "./wiki/Face-Detection-Engines.md" --title "Face Detection Engines"
gh wiki create Architecture-Decisions --content "./wiki/Architecture-Decisions.md" --title "Architecture Decisions"
```

#### Step 4: 验证发布

访问: https://github.com/littleseven/PicMe/wiki

---

### 方法 3: GitHub 网页界面 (手动)

适合少量页面或临时更新,不推荐用于批量发布。

#### Step 1: 访问 Wiki 页面

打开: https://github.com/littleseven/PicMe/wiki

#### Step 2: 创建首页

1. 点击 "Create the first page"
2. 标题填写: `Home`
3. 内容粘贴 `wiki/Home.md` 的完整内容
4. 点击 "Save Page"

#### Step 3: 创建其他页面

重复 Step 2,依次创建:
- Quick-Start
- Architecture-Overview
- Beauty-Engine
- Face-Detection-Engines
- Architecture-Decisions

#### Step 4: 设置侧边栏 (可选)

1. 点击 "Edit sidebar"
2. 添加页面链接和分组
3. 保存

---

## 🔄 后续更新

### 使用 Git 推送 (推荐)

```bash
cd /path/to/PicMe.wiki

# 修改文档后
git add .
git commit -m "docs: 更新 XXX 文档"
git push
```

### 使用 GitHub CLI

```bash
gh wiki edit Home --content "./wiki/Home.md"
```

### 使用网页界面

1. 访问对应 Wiki 页面
2. 点击 "Edit page"
3. 修改内容
4. 点击 "Save Page"

---

## 📊 文档统计

| 文档 | 行数 | 大小 | 主题 |
|------|------|------|------|
| Home.md | 109 | 3.8K | 项目概览 |
| Quick-Start.md | 251 | 5.3K | 快速开始 |
| Architecture-Overview.md | 308 | 11K | 架构设计 |
| Beauty-Engine.md | ~200 | 4.6K | 美颜系统 |
| Face-Detection-Engines.md | ~180 | 4.3K | 人脸检测 |
| Architecture-Decisions.md | ~150 | 3.1K | ADR 记录 |
| **总计** | **~1200** | **~32K** | **7 个页面** |

---

## ✅ 验收清单

发布前请确认:

- [ ] 所有 `.md` 文件语法正确,无 Markdown 错误
- [ ] 内部链接有效,无死链 (如 `[架构概览](Architecture-Overview)`)
- [ ] 外部链接可访问 (如 `[PRODUCT.md](../PRODUCT.md)`)
- [ ] 代码块格式正确,语言标识符准确
- [ ] 表格对齐,无错位
- [ ] Emoji 显示正常
- [ ] 标题层级合理 (# → ## → ###)
- [ ] 图片链接有效 (如有)
- [ ] 最后更新日期和维护者信息准确

---

## 💡 最佳实践

### 1. 版本控制

- 每次更新都编写清晰的 commit message
- 使用语义化版本 (如 `docs: v2026.05 更新`)
- 重大变更在 Release Notes 中说明

### 2. 链接管理

- 内部链接使用相对路径 (Wiki 页面名)
- 外部链接使用完整 URL
- 定期检查死链 (`grep -r "http" *.md`)

### 3. 图片处理

- 优先使用 GitHub Issues 上传图片 (自动生成 URL)
- 或使用专门的图片托管服务 (如 imgur)
- 避免在 Wiki 仓库中存储大量图片

### 4. 协作规范

- 多人编辑时先 pull 最新代码
- 避免同时修改同一页面
- 重要变更先在 Issue 中讨论

---

## 🐛 常见问题

### Q1: Wiki 仓库克隆失败

**错误**: `Repository not found`

**原因**: 未启用 Wiki 功能

**解决**:
1. 访问 `https://github.com/littleseven/PicMe/settings`
2. 找到 "Features" 区域
3. 勾选 "Wikis"
4. 重新克隆

### Q2: 推送被拒绝

**错误**: `rejected master -> master (fetch first)`

**原因**: 远程仓库有未同步的变更

**解决**:
```bash
git pull --rebase origin master
git push
```

### Q3: 链接无法跳转

**原因**: Wiki 页面名称与链接不匹配

**解决**:
- 检查文件名是否使用 PascalCase
- 确认链接中的空格已替换为 `-`
- 例如: `Quick-Start.md` → `[Quick Start](Quick-Start)`

### Q4: Markdown 渲染异常

**原因**: 使用了非 GFM 语法

**解决**:
- 使用 [GitHub Flavored Markdown](https://github.github.com/gfm/)
- 避免使用 HTML 标签
- 表格使用标准语法

---

## 📚 相关资源

- **GitHub Wiki 文档**: https://docs.github.com/en/communities/documenting-your-project-with-wikis
- **GFM 规范**: https://github.github.com/gfm/
- **GitHub CLI 文档**: https://cli.github.com/manual/gh_wiki
- **PicMe Wiki**: https://github.com/littleseven/PicMe/wiki

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team
