# PicMe Wiki 文档

本目录包含 PicMe 项目的 GitHub Wiki 文档。

## 📚 文档列表

- [Home.md](Home.md) - Wiki 主页,项目概览与快速导航
- [Quick-Start.md](Quick-Start.md) - 环境配置与构建指南
- [Architecture-Overview.md](Architecture-Overview.md) - Clean Architecture + 单引擎设计
- [Beauty-Engine.md](Beauty-Engine.md) - 大美丽实时美颜系统详解
- [Face-Detection-Engines.md](Face-Detection-Engines.md) - InsightFace + MediaPipe 双引擎架构
- [Architecture-Decisions.md](Architecture-Decisions.md) - ADR-001/002/003 关键技术决策

## 🚀 发布到 GitHub Wiki

### 方法 1: 使用 Git 推送 (推荐)

GitHub Wiki 是一个独立的 Git 仓库,可以通过以下步骤发布:

```bash
# 1. 克隆 Wiki 仓库
git clone https://github.com/littleseven/PicMe.wiki.git
cd PicMe.wiki

# 2. 复制 wiki 文档
cp /path/to/PicMe/wiki/*.md .

# 3. 提交并推送
git add .
git commit -m "docs: 初始化 Wiki 文档 (2026-05)"
git push origin master
```

### 方法 2: 通过 GitHub 网页界面

1. 访问 `https://github.com/littleseven/PicMe/wiki`
2. 点击 "Create the first page"
3. 手动复制粘贴每个 `.md` 文件的内容
4. 保存并发布

### 方法 3: 使用 GitHub CLI

```bash
# 安装 gh (如果未安装)
brew install gh

# 认证 GitHub
gh auth login

# 创建 Wiki 页面
gh wiki create Home --content "./wiki/Home.md" --title "Home"
gh wiki create Quick-Start --content "./wiki/Quick-Start.md" --title "Quick Start"
gh wiki create Architecture-Overview --content "./wiki/Architecture-Overview.md" --title "Architecture Overview"
gh wiki create Beauty-Engine --content "./wiki/Beauty-Engine.md" --title "Beauty Engine"
gh wiki create Face-Detection-Engines --content "./wiki/Face-Detection-Engines.md" --title "Face Detection Engines"
gh wiki create Architecture-Decisions --content "./wiki/Architecture-Decisions.md" --title "Architecture Decisions"
```

## 📝 文档维护

### 更新流程

1. **修改文档**: 编辑 `wiki/*.md` 文件
2. **本地预览**: 使用 Markdown 编辑器查看效果
3. **提交变更**: 
   ```bash
   cd /path/to/PicMe.wiki
   git add .
   git commit -m "docs: 更新 XXX 文档"
   git push
   ```

### 命名规范

- 文件名使用 **PascalCase** (如 `Quick-Start.md`)
- 标题使用 **#** 层级结构
- 内部链接使用相对路径 (如 `[架构概览](Architecture-Overview)`)
- 外部链接使用完整 URL (如 `[PRODUCT.md](../PRODUCT.md)`)

### 图片处理

Wiki 中的图片需要上传到 GitHub Issues 或专门的图片托管服务:

```markdown
![架构图](https://user-images.githubusercontent.com/xxx/xxx.png)
```

或使用相对路径 (仅适用于 Git 推送方式):

```markdown
![架构图](images/architecture.png)
```

## 🔗 相关资源

- **GitHub Wiki**: https://github.com/littleseven/PicMe/wiki
- **项目仓库**: https://github.com/littleseven/PicMe
- **GitHub Wiki API**: https://docs.github.com/en/rest/reference/repos#pages

## 💡 提示

- Wiki 支持 **GitHub Flavored Markdown (GFM)**
- 可以使用 **Emoji** 增强可读性 (如 ✅ ❌ 🚀)
- 推荐使用 **Mermaid** 绘制流程图和架构图
- 定期审查文档链接,确保无死链

---

**最后更新**: 2026-05-05  
**维护者**: PicMe RD Team
