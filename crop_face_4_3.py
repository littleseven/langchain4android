#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
人脸区域裁剪脚本
截取图片中头顶到脖子中间的图像，并按 3:4 比例保留（竖屏）
"""

import cv2
import numpy as np
import mediapipe as mp
import sys
import os


def crop_face_region_4_3(image_path, output_path=None):
    """
    裁剪图片中头顶到脖子中间的区域，按 3:4 比例（竖屏）
    
    Args:
        image_path: 输入图片路径
        output_path: 输出图片路径（默认为输入文件名加 _cropped 后缀）
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
        refine_landmarks=True,
        min_detection_confidence=0.5
    )
    
    # 读取图片
    print(f"正在读取图片：{image_path}")
    image = cv2.imread(image_path)
    if image is None:
        print(f"错误：无法读取图片 {image_path}")
        return
    
    # 获取图片尺寸
    height, width, _ = image.shape
    print(f"原始图片尺寸：{width} x {height}")
    
    # 转换为 RGB
    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    
    # 检测人脸关键点
    print("正在进行人脸关键点检测...")
    results = face_mesh.process(image_rgb)
    
    if not results.multi_face_landmarks:
        print("警告：未检测到人脸！")
        return
    
    print(f"检测到 {len(results.multi_face_landmarks)} 个人脸")
    
    # 获取第一个人脸的关键点
    face_landmarks = results.multi_face_landmarks[0]
    landmarks = face_landmarks.landmark
    
    # 关键位置索引
    TOP_HEAD_INDEX = 10  # 头顶（额头中间偏上）
    FOREHEAD_CENTER = 151  # 额头中心
    CHIN_CENTER = 152  # 下巴中心
    NECK_TOP = 199  # 脖子顶部
    
    # 计算关键点位置
    top_head = landmarks[TOP_HEAD_INDEX]
    forehead = landmarks[FOREHEAD_CENTER]
    chin = landmarks[CHIN_CENTER]
    neck = landmarks[NECK_TOP]
    
    # 转换为像素坐标
    top_y = int(top_head.y * height)
    forehead_y = int(forehead.y * height)
    chin_y = int(chin.y * height)
    neck_y = int(neck.y * height)
    
    # 计算脸部中心 x 坐标（取额头和下巴的中点）
    center_x = int((forehead.x + chin.x) / 2 * width)
    
    # 定义裁剪区域
    # 头顶位置（留出更多头顶空间，约 10%）
    crop_top = max(0, top_y - int(height * 0.10))
    
    # 下巴位置（减少脖子部分，只保留下巴下方 15% 的脸高）
    face_height = chin_y - forehead_y
    crop_bottom = min(height, chin_y + int(face_height * 0.15))  # 下巴下方 15% 的脸高
    
    # 计算裁剪高度
    crop_height = crop_bottom - crop_top
    
    # 保持原始宽度不变
    crop_width = width
    
    # 左右边界使用原始图片的全部宽度
    crop_left = 0
    crop_right = width
    
    # 确保宽度正确
    crop_width = crop_right - crop_left
    
    print(f"\n裁剪区域计算：")
    print(f"  头顶位置：y={top_head.y:.3f} ({top_y}px)")
    print(f"  额头中心：y={forehead.y:.3f} ({forehead_y}px)")
    print(f"  下巴中心：y={chin.y:.3f} ({chin_y}px)")
    print(f"  脖子位置：y={neck.y:.3f} ({neck_y}px)")
    print(f"  裁剪区域：")
    print(f"    左={crop_left}, 右={crop_right}, 上={crop_top}, 下={crop_bottom}")
    print(f"    宽度={crop_width}, 高度={crop_height}")
    print(f"  人脸中心 x={center_x} (图片中心: {width//2}px)")
    
    # 裁剪图片
    cropped_image = image[crop_top:crop_bottom, crop_left:crop_right]
    
    # 设置输出路径
    if output_path is None:
        base_name = os.path.splitext(image_path)[0]
        output_path = f"{base_name}_cropped.png"
    
    # 保存图片
    print(f"\n正在保存裁剪后的图片到：{output_path}")
    cv2.imwrite(output_path, cropped_image)
    print(f"保存成功！裁剪后尺寸：{crop_width} x {crop_height}")
    
    # 释放资源
    face_mesh.close()
    
    return cropped_image


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
    
    crop_face_region_4_3(image_path, output_path)


if __name__ == "__main__":
    main()
