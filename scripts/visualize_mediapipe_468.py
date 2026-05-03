#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MediaPipe 468点人脸关键点可视化脚本
在图片上以草绿色标注468个人脸关键点，并显示点序号
"""

import cv2
import numpy as np
import mediapipe as mp
import sys
import os


def visualize_mediapipe_landmarks(image_path, output_path=None):
    """
    在图片上可视化 MediaPipe 468 个人脸关键点
    
    Args:
        image_path: 输入图片路径
        output_path: 输出图片路径（默认为输入文件名加 _landmarks 后缀）
    """
    # 检查输入文件是否存在
    if not os.path.exists(image_path):
        print(f"错误：找不到文件 {image_path}")
        return
    
    # 初始化 MediaPipe Face Mesh
    mp_face_mesh = mp.solutions.face_mesh
    face_mesh = mp_face_mesh.FaceMesh(
        static_image_mode=True,
        max_num_faces=1,
        refine_landmarks=True,  # 启用瞳孔检测
        min_detection_confidence=0.5
    )
    
    # 读取图片
    print(f"正在读取图片：{image_path}")
    image = cv2.imread(image_path)
    if image is None:
        print(f"错误：无法读取图片 {image_path}")
        return
    
    # 转换为 RGB（MediaPipe 需要 RGB 格式）
    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    
    # 获取图片尺寸
    height, width, _ = image.shape
    print(f"图片尺寸：{width} x {height}")
    
    # 设置输出路径
    if output_path is None:
        base_name = os.path.splitext(image_path)[0]
        output_path = f"{base_name}_landmarks.png"
    
    # 处理图片
    print("正在进行人脸关键点检测...")
    results = face_mesh.process(image_rgb)
    
    if not results.multi_face_landmarks:
        print("警告：未检测到人脸！")
        return
    
    print(f"检测到 {len(results.multi_face_landmarks)} 个人脸")
    
    # 复制图片用于绘制
    output_image = image.copy()
    
    # 按面部区域定义颜色 (BGR 格式)
    # 草绿色系主色调，不同区域使用不同明度和色相
    region_colors = {
        'face_oval': (0, 255, 128),      # 脸部轮廓 - 草绿
        'left_eye': (0, 255, 200),       # 左眼 - 青绿
        'right_eye': (0, 220, 180),      # 右眼 - 深青绿
        'left_eyebrow': (100, 255, 100), # 左眉 - 亮绿
        'right_eyebrow': (80, 230, 80),  # 右眉 - 中绿
        'nose': (0, 200, 100),           # 鼻子 - 深绿
        'lips': (50, 255, 150),          # 嘴唇 - 浅草绿
        'iris': (150, 255, 50),          # 瞳孔 - 黄绿
        'face_inner': (0, 180, 80)       # 脸部内部 - 暗绿
    }
    
    # 定义各区域的关键点索引范围
    regions = {
        'face_oval': list(range(0, 36)),  # 0-35
        'left_eyebrow': list(range(70, 106)),  # 70-105
        'right_eyebrow': list(range(296, 336)),  # 296-335
        'left_eye': list(range(33, 143)),  # 33-142
        'right_eye': list(range(362, 472)),  # 362-471
        'lips': list(range(13, 195)),  # 13-194 (简化)
        'nose': [1, 2, 3, 4, 5, 6, 19, 21, 22, 23, 24, 25, 26, 27, 28, 
                 48, 49, 50, 51, 64, 65, 66, 67, 98, 102, 114, 115, 116, 
                 117, 118, 119, 120, 121, 131, 134, 168, 195, 196, 197, 
                 275, 276, 277, 278, 279, 280, 281, 282, 283, 294, 295, 
                 296, 297, 298, 326, 327, 328, 329, 330, 331, 332, 343, 
                 357, 358, 359, 360, 361, 417, 418, 419, 420, 421, 423, 
                 424, 425, 426, 427, 429, 430, 431, 433, 434, 435, 436, 
                 437, 456, 457, 458, 459, 460, 465, 466, 467],
        'iris': list(range(468, 478)),  # 468-477
        'face_inner': []  # 其余点
    }
    
    # 计算脸部内部点（不在其他区域的点）
    all_region_points = set()
    for region_points in regions.values():
        if region_points:
            all_region_points.update(region_points)
    regions['face_inner'] = [i for i in range(478) if i not in all_region_points]
    
    # 计算合适的圆点半径和字体大小（更小）
    point_radius = max(1, min(width, height) // 400)
    font_scale = max(0.2, min(width, height) / 3500)
    font_thickness = 1
    
    # 处理每张人脸
    for face_idx, face_landmarks in enumerate(results.multi_face_landmarks):
        print(f"处理第 {face_idx + 1} 个人脸...")
        
        # 绘制所有关键点
        for idx, landmark in enumerate(face_landmarks.landmark):
            # 将归一化坐标转换为像素坐标
            x = int(landmark.x * width)
            y = int(landmark.y * height)
            
            # 根据索引确定区域和颜色
            region_name = 'face_inner'
            for r_name, r_points in regions.items():
                if idx in r_points:
                    region_name = r_name
                    break
            
            point_color = region_colors[region_name]
            # 文字颜色稍微暗一些，提高可读性
            text_color = tuple(max(0, c - 50) for c in point_color)
            
            # 绘制小圆点
            cv2.circle(output_image, (x, y), point_radius, point_color, -1)
            
            # 计算文字偏移量，避免遮挡
            # 根据点的位置调整文字位置
            offset_x = point_radius + 1
            offset_y = -point_radius - 1
            
            # 对于脸部中心区域的点，文字向下偏移
            if 0.3 < landmark.x < 0.7 and 0.3 < landmark.y < 0.7:
                offset_y = point_radius + 12
            
            # 绘制点序号（所有点都显示）
            cv2.putText(
                output_image,
                str(idx),
                (x + offset_x, y + offset_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                font_scale,
                text_color,
                font_thickness,
                cv2.LINE_AA
            )
        
        print(f"已标注 {len(face_landmarks.landmark)} 个关键点")
    
    # 保存图片
    print(f"正在保存图片到：{output_path}")
    cv2.imwrite(output_path, output_image)
    print("保存成功！")
    
    # 释放资源
    face_mesh.close()


def main():
    """主函数"""
    if len(sys.argv) < 2:
        print("使用方法：")
        print(f"  python {sys.argv[0]} <输入图片路径> [输出图片路径]")
        print("\n示例：")
        print(f"  python {sys.argv[0]} face.jpg")
        print(f"  python {sys.argv[0]} face.jpg output.png")
        sys.exit(1)
    
    image_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else None
    
    visualize_mediapipe_landmarks(image_path, output_path)


if __name__ == "__main__":
    main()
