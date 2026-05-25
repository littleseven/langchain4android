---
name: gpupixel-porting
description: 【已归档】GPUPixel 算法向大美丽模式移植的规范流程。GPUPixel 已于 2026-05 完全移除，本 Skill 仅保留作为历史技术参考。
version: 1.0.0-archived
created: 2026-05-03
updated: 2026-05-25
maintainer: [RD] 全栈工程师
tags: [archived, gpupixel, shader, legacy]
status: archived
---

# GPUPixel 移植 Skill

## 移植流程

### 1. 源码分析
- 阅读 GPUPixel C++ 源码（`temp/gpupixel/src/filter/`）
- 理解算法原理（顶点变形、纹理映射、混合模式）
- 记录关键参数和常量

### 2. Shader 移植
- 将 C++ 中的 GLSL shader 转换为 Kotlin 字符串
- 注意 varying/uniform 声明一致性
- 适配 OpenGL ES 2.0/3.0 语法差异

### 3. 坐标系适配
- GPUPixel 使用 111 点，MediaPipe 只有 106 点
- 需要建立索引映射或替代方案
- 处理坐标系差异（Y轴翻转、左右镜像）

### 4. 多 Pass 集成
- 分析 GPUPixel 的 Pass 链
- 集成到 BeautyRenderer 的多 Pass 架构
- 处理 FBO 纹理传递

## 关键适配点

### 顶点数差异
| GPUPixel | MediaPipe | 处理方式 |
|---------|-----------|---------|
| 111 点 | 106 点 | 移除 106-110，用相近点替代 |

### 纹理坐标
GPUPixel 提供 111 个顶点的纹理坐标，需要截取前 106 个：
```kotlin
val trimmedCoords = FACE_TEXTURE_COORDS.copyOf(106 * 2)
```

### 混合模式
| GPUPixel 模式 | 值 | 说明 |
|-------------|-----|------|
| Normal | 0 | 正常 |
| Multiply | 15 | 正片叠底 |
| Overlay | 17 | 叠加 |
| HardLight | 22 | 强光 |
| SoftLight | 24 | 柔光 |

## 常见算法移植

### 唇色/腮红（FaceMakeupFilter）
1. 加载妆容纹理（mouth.png / blusher.png）
2. 构建三角网格（106 点索引）
3. 顶点 Shader：position = 人脸关键点 NDC
4. 片段 Shader：双纹理采样 + Multiply 混合

### 瘦脸（FaceReshapeFilter）
1. 计算瘦脸方向向量
2. 根据距离衰减因子变形
3. 使用 warp shader 进行顶点位移

### 大眼（FaceReshapeFilter）
1. 以眼睛中心为圆心
2. 径向向外扩展像素
3. 使用圆形 warp 算法

## 调试清单

- [ ] Shader 编译成功
- [ ] 纹理加载成功
- [ ] 关键点坐标正确（无偏转、无镜像错误）
- [ ] 三角网格无缺口（检查索引越界）
- [ ] 混合效果可见（调整强度参数）
- [ ] 多 Pass 链正确执行

## 文件位置

- GPUPixel 源码：`temp/gpupixel/src/filter/`
- 大美丽 Shader：`beauty-engine/src/main/assets/shaders/`
- 渲染器：`beauty-engine/src/main/java/com/picme/beauty/egl/BeautyRenderer.kt`
