#!/usr/bin/env python3
"""
MediaPipe 眼睛区域关键点可视化工具
分别裁剪左眼和右眼区域，并标注 468 关键点序号
"""

import cv2
import numpy as np
import mediapipe as mp
import os

# 配置
INPUT_IMAGES = [
    "input_images/face001.png",
    "input_images/face002.png",
]
OUTPUT_DIR = "output_images"

# 眼睛区域裁剪参数
EYE_REGION_SCALE = 0.25  # 眼睛区域占图片宽度的比例
EYE_ASPECT_RATIO = 0.8   # 裁剪区域高宽比

# 可视化参数
POINT_RADIUS = 3
FONT_SCALE = 0.35
TEXT_THICKNESS = 1
LINE_THICKNESS = 1

# MediaPipe 468 点中眼睛相关的关键点索引
# 左眼（画面右侧=实际左眼）
LEFT_EYE_LANDMARKS = {
    # 外轮廓
    33: "左眼外角", 133: "左眼内角",
    # 上眼睑（从外到内）
    246: "上眼睑外1", 161: "上眼睑外2", 160: "上眼睑外3",
    159: "上眼睑中1", 158: "上眼睑中2", 144: "上眼睑内1",
    145: "上眼睑内2", 153: "上眼睑内3",
    # 下眼睑（从内到外）
    263: "下眼睑内角", 466: "下眼睑内1", 388: "下眼睑中1",
    387: "下眼睑中2", 386: "下眼睑外1", 385: "下眼睑外2",
    384: "下眼睑外3", 398: "下眼睑外4", 362: "下眼睑外5",
    # 瞳孔
    468: "左瞳孔", 469: "左虹膜上", 470: "左虹膜右",
    471: "左虹膜下", 472: "左虹膜左",
    # 眼周辅助点
    7: "左眼上", 163: "左眼下", 144: "左眼内上", 153: "左眼内下",
}

# 右眼（画面左侧=实际右眼）
RIGHT_EYE_LANDMARKS = {
    # 外轮廓
    362: "右眼外角", 463: "右眼内角",
    # 上眼睑（从外到内）
    398: "上眼睑外1", 384: "上眼睑外2", 385: "上眼睑外3",
    386: "上眼睑中1", 387: "上眼睑中2", 373: "上眼睑内1",
    374: "上眼睑内2", 380: "上眼睑内3", 381: "上眼睑内4",
    382: "上眼睑内5", 362: "上眼睑内6",
    # 下眼睑（从内到外）
    33: "下眼睑内角", 246: "下眼睑内1", 161: "下眼睑中1",
    160: "下眼睑中2", 159: "下眼睑外1", 158: "下眼睑外2",
    144: "下眼睑外3", 133: "下眼睑外4", 362: "下眼睑外5",
    # 瞳孔
    473: "右瞳孔", 474: "右虹膜上", 475: "右虹膜右",
    476: "右虹膜下", 477: "右虹膜左",
    # 眼周辅助点
    237: "右眼上", 263: "右眼下", 373: "右眼内上", 380: "右眼内下",
}

# 裁剪时使用的所有眼睛关键点（不含虹膜）
LEFT_EYE_CROP_INDICES = [33, 133, 246, 161, 160, 159, 158, 144, 145, 153, 263, 466, 388, 387, 386, 385, 384, 398, 362]
RIGHT_EYE_CROP_INDICES = [362, 463, 398, 384, 385, 386, 387, 373, 374, 380, 381, 382, 33, 246, 161, 160, 159, 158, 144, 133]


def detect_landmarks(image_path):
    """检测人脸关键点"""
    mp_face_mesh = mp.solutions.face_mesh
    face_mesh = mp_face_mesh.FaceMesh(
        static_image_mode=True,
        max_num_faces=1,
        refine_landmarks=True,
        min_detection_confidence=0.5
    )
    
    image = cv2.imread(image_path)
    if image is None:
        print(f"[ERROR] 无法加载图片: {image_path}")
        return None, None, None
    
    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    results = face_mesh.process(image_rgb)
    
    h, w = image.shape[:2]
    landmarks = results.multi_face_landmarks[0].landmark if results.multi_face_landmarks else None
    
    face_mesh.close()
    return image, landmarks, (w, h)


def crop_eye_region_by_landmarks(image, landmarks, w, h, is_left=True):
    """基于关键点坐标裁剪眼睛区域"""
    if is_left:
        eye_indices = LEFT_EYE_CROP_INDICES
    else:
        eye_indices = RIGHT_EYE_CROP_INDICES
    
    # 收集所有眼睛关键点的坐标
    x_coords = []
    y_coords = []
    for idx in eye_indices:
        if idx < len(landmarks):
            landmark = landmarks[idx]
            x_coords.append(int(landmark.x * w))
            y_coords.append(int(landmark.y * h))
    
    if not x_coords or not y_coords:
        return None, 0, 0, 0, 0
    
    # 计算边界框
    min_x = min(x_coords)
    max_x = max(x_coords)
    min_y = min(y_coords)
    max_y = max(y_coords)
    
    # 添加边距（20%）
    width = max_x - min_x
    height = max_y - min_y
    padding_x = int(width * 0.3)
    padding_y = int(height * 0.4)
    
    x1 = max(0, min_x - padding_x)
    y1 = max(0, min_y - padding_y)
    x2 = min(w, max_x + padding_x)
    y2 = min(h, max_y + padding_y)
    
    cropped_image = image[y1:y2, x1:x2].copy()
    
    print(f"[INFO] {'左眼' if is_left else '右眼'}区域裁剪: ({x1},{y1}) -> ({x2},{y2}), 尺寸: {x2-x1}x{y2-y1}")
    
    return cropped_image, x1, y1, w, h


def draw_eye_landmarks(cropped_image, landmarks, w, h, offset_x, offset_y, eye_landmarks):
    """在裁剪后的图片上绘制眼睛关键点（只绘制字典中的点）"""
    output = cropped_image.copy()
    
    # 获取要绘制的点索引列表
    landmark_indices = list(eye_landmarks.keys())
    
    # 绘制连接线（眼轮廓）
    if "左眼" in str(eye_landmarks):
        # 左眼轮廓连接
        upper_lid = [33, 246, 161, 160, 159, 158, 144, 145, 153, 133]
        lower_lid = [133, 263, 466, 388, 387, 386, 385, 384, 398, 362, 33]
    else:
        # 右眼轮廓连接
        upper_lid = [362, 398, 384, 385, 386, 387, 373, 374, 380, 381, 382, 463]
        lower_lid = [463, 33, 246, 161, 160, 159, 158, 144, 133, 362]
    
    # 绘制上眼睑连线
    for i in range(len(upper_lid) - 1):
        if upper_lid[i] >= len(landmarks) or upper_lid[i+1] >= len(landmarks):
            continue
        p1 = landmarks[upper_lid[i]]
        p2 = landmarks[upper_lid[i+1]]
        x1 = int(p1.x * w) - offset_x
        y1 = int(p1.y * h) - offset_y
        x2 = int(p2.x * w) - offset_x
        y2 = int(p2.y * h) - offset_y
        if 0 <= x1 < cropped_image.shape[1] and 0 <= y1 < cropped_image.shape[0]:
            if 0 <= x2 < cropped_image.shape[1] and 0 <= y2 < cropped_image.shape[0]:
                cv2.line(output, (x1, y1), (x2, y2), (255, 255, 255), LINE_THICKNESS)
    
    # 绘制下眼睑连线
    for i in range(len(lower_lid) - 1):
        if lower_lid[i] >= len(landmarks) or lower_lid[i+1] >= len(landmarks):
            continue
        p1 = landmarks[lower_lid[i]]
        p2 = landmarks[lower_lid[i+1]]
        x1 = int(p1.x * w) - offset_x
        y1 = int(p1.y * h) - offset_y
        x2 = int(p2.x * w) - offset_x
        y2 = int(p2.y * h) - offset_y
        if 0 <= x1 < cropped_image.shape[1] and 0 <= y1 < cropped_image.shape[0]:
            if 0 <= x2 < cropped_image.shape[1] and 0 <= y2 < cropped_image.shape[0]:
                cv2.line(output, (x1, y1), (x2, y2), (255, 255, 255), LINE_THICKNESS)
    
    # 绘制关键点（只绘制字典中的点）
    for idx, name in eye_landmarks.items():
        if idx >= len(landmarks):
            continue
        
        landmark = landmarks[idx]
        x = int(landmark.x * w) - offset_x
        y = int(landmark.y * h) - offset_y
        
        # 检查点是否在裁剪区域内
        if 0 <= x < cropped_image.shape[1] and 0 <= y < cropped_image.shape[0]:
            # 根据类型设置颜色
            if "瞳孔" in name or "虹膜" in name:
                color = (255, 0, 255)  # 品红 - 瞳孔
            elif "外角" in name or "内角" in name:
                color = (255, 255, 0)  # 黄色 - 眼角
            elif "上眼睑" in name:
                color = (0, 255, 255)  # 青色 - 上眼睑
            elif "下眼睑" in name:
                color = (0, 255, 0)  # 绿色 - 下眼睑
            else:
                color = (128, 128, 128)  # 灰色 - 其他
            
            # 绘制关键点
            cv2.circle(output, (x, y), POINT_RADIUS, color, -1)
            cv2.circle(output, (x, y), POINT_RADIUS + 2, (255, 255, 255), 1)
            
            # 绘制序号（红色，无黑底，显示在点的正下方）
            text = f"{idx}"
            
            # 获取文字尺寸，用于计算居中位置
            text_size = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, FONT_SCALE, TEXT_THICKNESS)[0]
            text_width = text_size[0]
            text_height = text_size[1]
            
            # 计算文字位置：点的正下方居中
            text_x = x - text_width // 2
            text_y = y + POINT_RADIUS + 3 + text_height
            
            # 直接绘制红色文字
            cv2.putText(
                output,
                text,
                (text_x, text_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                FONT_SCALE,
                (0, 0, 255),  # 红色 (BGR)
                TEXT_THICKNESS,
                cv2.LINE_AA
            )
    
    return output


def add_legend(output_image, eye_name, landmarks_dict):
    """添加图例"""
    h, w = output_image.shape[:2]
    legend_height = 80
    legend_width = w
    
    # 创建带图例的图片
    output_with_legend = np.zeros((h + legend_height, w, 3), dtype=np.uint8)
    output_with_legend[:h] = output_image
    output_with_legend[h:] = (40, 40, 40)  # 深灰色背景
    
    # 标题
    title = f"MediaPipe {eye_name} Landmarks"
    cv2.putText(
        output_with_legend,
        title,
        (10, h + 25),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        (255, 255, 255),
        1,
        cv2.LINE_AA
    )
    
    # 统计信息
    info_text = f"Points: {len(landmarks_dict)} | Region: {w}x{h}"
    cv2.putText(
        output_with_legend,
        info_text,
        (10, h + 50),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.4,
        (200, 200, 200),
        1,
        cv2.LINE_AA
    )
    
    return output_with_legend


def process_single_image(image_path):
    """处理单张图片的左右眼"""
    print(f"\n{'=' * 60}")
    print(f"处理图片: {image_path}")
    print('=' * 60)
    
    # 从路径提取文件名（不含扩展名）
    base_name = os.path.splitext(os.path.basename(image_path))[0]
    output_left = os.path.join(OUTPUT_DIR, f"{base_name}_left_eye.png")
    output_right = os.path.join(OUTPUT_DIR, f"{base_name}_right_eye.png")
    
    # 加载图片并检测关键点
    print(f"[INFO] 加载图片: {image_path}")
    image, landmarks, (w, h) = detect_landmarks(image_path)
    
    if landmarks is None:
        print("[ERROR] 未检测到人脸关键点")
        return
    
    print(f"[INFO] 图片尺寸: {w}x{h}")
    print(f"[INFO] 检测到 {len(landmarks)} 个关键点")
    
    # 处理左眼（画面右侧=实际左眼）
    print("\n[INFO] 处理左眼区域...")
    left_eye_image, x1, y1, _, _ = crop_eye_region_by_landmarks(image, landmarks, w, h, is_left=True)
    if left_eye_image is not None:
        left_eye_output = draw_eye_landmarks(left_eye_image, landmarks, w, h, x1, y1, LEFT_EYE_LANDMARKS)
        left_eye_with_legend = add_legend(left_eye_output, "Left Eye", LEFT_EYE_LANDMARKS)
        cv2.imwrite(output_left, left_eye_with_legend)
        print(f"[SUCCESS] 左眼结果已保存: {output_left}")
        print(f"[INFO] 输出尺寸: {left_eye_with_legend.shape[1]}x{left_eye_with_legend.shape[0]}")
    
    # 处理右眼（画面左侧=实际右眼）
    print("\n[INFO] 处理右眼区域...")
    right_eye_image, x1, y1, _, _ = crop_eye_region_by_landmarks(image, landmarks, w, h, is_left=False)
    if right_eye_image is not None:
        right_eye_output = draw_eye_landmarks(right_eye_image, landmarks, w, h, x1, y1, RIGHT_EYE_LANDMARKS)
        right_eye_with_legend = add_legend(right_eye_output, "Right Eye", RIGHT_EYE_LANDMARKS)
        cv2.imwrite(output_right, right_eye_with_legend)
        print(f"[SUCCESS] 右眼结果已保存: {output_right}")
        print(f"[INFO] 输出尺寸: {right_eye_with_legend.shape[1]}x{right_eye_with_legend.shape[0]}")


def main():
    print("=" * 60)
    print("MediaPipe 眼睛区域关键点可视化工具")
    print("=" * 60)
    
    # 创建输出目录
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    # 批量处理所有图片
    for image_path in INPUT_IMAGES:
        if not os.path.exists(image_path):
            print(f"\n[WARNING] 文件不存在，跳过: {image_path}")
            continue
        
        process_single_image(image_path)
    
    print("\n" + "=" * 60)
    print("所有图片处理完成！")
    print(f"输出目录: {OUTPUT_DIR}")
    print("提示: 可以使用图片查看器打开结果图片查看详细标注")
    print("=" * 60)


if __name__ == "__main__":
    main()
