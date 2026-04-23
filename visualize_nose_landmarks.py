#!/usr/bin/env python3
"""
MediaPipe 鼻子区域关键点可视化脚本
放大 face001.png 的鼻子部位，标注 468 点中的鼻子相关点位及其序号
"""

import cv2
import numpy as np
import mediapipe as mp
from pathlib import Path

# ==================== 配置区域 ====================

# 输入图片路径
INPUT_IMAGE = "input_images/face001.png"
OUTPUT_IMAGE = "output_images/nose_landmarks_visualization.png"

# 鼻子区域裁剪参数（归一化坐标，相对于图片中心）
NOSE_REGION_SCALE = 0.20  # 鼻子区域占图片宽度的比例（缩小，仅包含鼻子）
CROP_ASPECT_RATIO = 1.5   # 裁剪区域高宽比（更高，包含鼻梁到鼻尖）
OUTPUT_SCALE = 2.0        # 输出图片放大倍数

# MediaPipe 468点中所有鼻子相关的关键点索引
NOSE_LANDMARKS = {
    # 鼻梁中心线
    6: "鼻根",
    168: "眉心下",
    195: "鼻梁下",
    197: "鼻梁中",
    
    # 鼻尖
    1: "鼻尖下",
    2: "左鼻孔左",
    4: "鼻尖中心",
    5: "鼻底中",
    19: "鼻小柱右",
    48: "鼻尖左",
    51: "鼻尖右",
    
    # 鼻翼及周围
    31: "鼻底左",
    35: "鼻底右",
    64: "左鼻翼外",
    98: "左鼻翼下",
    241: "鼻梁左",
    327: "右鼻翼上",
    358: "右鼻翼外",
    359: "右鼻翼下",
    461: "鼻梁右",
    
    # 鼻孔及鼻中隔
    326: "左鼻孔右",
    
    # 鼻子内部点（左侧）
    21: "鼻侧1", 22: "鼻侧2", 23: "鼻侧3", 24: "鼻侧4",
    25: "鼻侧5", 26: "鼻侧6", 27: "鼻侧7", 28: "鼻侧8",
    114: "鼻内1", 115: "鼻内2", 116: "鼻内3", 117: "鼻内4",
    118: "鼻内5", 119: "鼻内6", 120: "鼻内7", 121: "鼻内8",
    131: "鼻翼根左",
    134: "鼻侧内1", 135: "鼻侧内2", 136: "鼻侧内3", 137: "鼻侧内4",
    138: "鼻侧内5", 139: "鼻侧内6", 140: "鼻侧内7", 141: "鼻侧内8",
    142: "鼻侧内9", 143: "鼻侧内10", 144: "鼻侧内11", 145: "鼻侧内12",
    146: "鼻侧内13", 147: "鼻侧内14", 148: "鼻侧内15", 149: "鼻侧内16",
    150: "鼻侧内17", 151: "鼻侧内18", 152: "鼻侧内19", 153: "鼻侧内20",
    154: "鼻侧内21",
    
    # 鼻子内部点（右侧）
    275: "鼻侧右1", 276: "鼻侧右2", 277: "鼻侧右3", 278: "鼻侧右4",
    279: "鼻侧右5", 280: "鼻侧右6", 281: "鼻侧右7", 282: "鼻侧右8",
    283: "鼻侧右9",
    294: "鼻翼根右1", 295: "鼻翼根右2", 296: "鼻翼根右3",
    297: "鼻翼根右4", 298: "鼻翼根右5",
    360: "鼻翼根右6", 361: "鼻翼根右7",
    417: "鼻内右1", 418: "鼻内右2", 419: "鼻内右3", 420: "鼻内右4",
    421: "鼻内右5",
    423: "鼻侧右内1", 424: "鼻侧右内2", 425: "鼻侧右内3",
    426: "鼻侧右内4", 427: "鼻侧右内5",
    429: "鼻侧右内6", 430: "鼻侧右内7", 431: "鼻侧右内8",
    433: "鼻侧右内9", 434: "鼻侧右内10", 435: "鼻侧右内11",
    436: "鼻侧右内12", 437: "鼻侧右内13",
    456: "鼻侧右内14", 457: "鼻侧右内15", 458: "鼻侧右内16",
    459: "鼻侧右内17", 460: "鼻侧右内18",
    465: "鼻尖右内1", 466: "鼻尖右内2",
}

# 可视化参数
POINT_RADIUS = 3          # 关键点半径
FONT_SCALE = 0.3          # 文字缩放（减小字号）
TEXT_THICKNESS = 1        # 文字粗细
LINE_THICKNESS = 1        # 连线粗细
CROP_PADDING = 50         # 裁剪区域边距（像素）

# ==================== 主要逻辑 ====================

def detect_face_landmarks(image_path: str):
    """检测人脸关键点"""
    print(f"[INFO] 加载图片: {image_path}")
    image = cv2.imread(image_path)
    if image is None:
        raise FileNotFoundError(f"无法读取图片: {image_path}")
    
    h, w = image.shape[:2]
    print(f"[INFO] 图片尺寸: {w}x{h}")
    
    # 转换为 RGB
    rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    
    # 初始化 MediaPipe Face Mesh
    mp_face_mesh = mp.solutions.face_mesh
    face_mesh = mp_face_mesh.FaceMesh(
        static_image_mode=True,
        max_num_faces=1,
        refine_landmarks=True,
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5
    )
    
    # 检测关键点
    print("[INFO] 检测人脸关键点...")
    results = face_mesh.process(rgb_image)
    
    if not results.multi_face_landmarks:
        raise RuntimeError("未检测到人脸")
    
    landmarks = results.multi_face_landmarks[0].landmark
    print(f"[INFO] 检测到 {len(landmarks)} 个关键点")
    
    face_mesh.close()
    return image, landmarks, w, h


def crop_nose_region(image, landmarks, w, h):
    """裁剪鼻子区域（仅包含鼻子，不含嘴）"""
    # 获取鼻尖中心点 (index 4)
    nose_tip = landmarks[4]
    center_x = int(nose_tip.x * w)
    center_y = int(nose_tip.y * h)
    
    # 计算裁剪区域大小（缩小，仅包含鼻子）
    crop_width = int(min(w, h) * NOSE_REGION_SCALE)
    crop_height = int(crop_width * CROP_ASPECT_RATIO)
    
    # 计算裁剪边界（以鼻尖为中心，向上偏移更多，避开嘴部）
    x1 = max(0, center_x - crop_width // 2)
    y1 = max(0, center_y - crop_height * 2 // 3)  # 向上延伸更多，包含鼻梁
    x2 = min(w, center_x + crop_width // 2)
    y2 = min(h, center_y + crop_height // 3)  # 向下仅到鼻尖下方，不含嘴
    
    # 添加内边距（缩小边距）
    padding = int(crop_width * 0.1)  # 10%的边距
    x1 = max(0, x1 - padding)
    y1 = max(0, y1 - padding)
    x2 = min(w, x2 + padding)
    y2 = min(h, y2 + padding)
    
    # 裁剪图片
    cropped_image = image[y1:y2, x1:x2].copy()
    
    # 放大图片
    if OUTPUT_SCALE != 1.0:
        new_width = int(cropped_image.shape[1] * OUTPUT_SCALE)
        new_height = int(cropped_image.shape[0] * OUTPUT_SCALE)
        cropped_image = cv2.resize(cropped_image, (new_width, new_height), interpolation=cv2.INTER_CUBIC)
    
    print(f"[INFO] 鼻子区域裁剪: ({x1},{y1}) -> ({x2},{y2}), 原始尺寸: {x2-x1}x{y2-y1}")
    print(f"[INFO] 输出尺寸: {cropped_image.shape[1]}x{cropped_image.shape[0]} (放大{OUTPUT_SCALE}倍)")
    
    return cropped_image, x1, y1, center_x, center_y


def draw_nose_landmarks(cropped_image, landmarks, w, h, offset_x, offset_y):
    """在裁剪后的图片上绘制鼻子关键点"""
    output = cropped_image.copy()
    
    # 计算放大倍数对坐标的影响
    scale_factor = OUTPUT_SCALE
    
    # 绘制所有鼻子关键点
    for idx, name in NOSE_LANDMARKS.items():
        if idx >= len(landmarks):
            continue
        
        landmark = landmarks[idx]
        # 先计算在原始裁剪图中的坐标
        orig_x = int(landmark.x * w) - offset_x
        orig_y = int(landmark.y * h) - offset_y
        
        # 再根据放大倍数调整坐标
        x = int(orig_x * scale_factor)
        y = int(orig_y * scale_factor)
        
        # 检查点是否在裁剪区域内
        if 0 <= x < cropped_image.shape[1] and 0 <= y < cropped_image.shape[0]:
            # 根据区域设置颜色
            if idx in [6, 168, 197, 195]:  # 鼻梁
                color = (255, 0, 0)  # 蓝色 (BGR)
            elif idx in [4, 1, 19, 48, 51]:  # 鼻尖
                color = (0, 255, 0)  # 绿色
            elif idx in [64, 98, 241, 327, 358, 359, 461]:  # 鼻翼
                color = (0, 255, 255)  # 黄色
            elif idx in [2, 326]:  # 鼻孔
                color = (255, 0, 255)  # 品红
            elif idx in [31, 35, 5]:  # 鼻底
                color = (0, 165, 255)  # 橙色
            else:
                color = (128, 128, 128)  # 灰色
            
            # 绘制关键点（半径也根据放大倍数调整）
            point_radius = int(POINT_RADIUS * scale_factor)
            cv2.circle(output, (x, y), point_radius, color, -1)
            cv2.circle(output, (x, y), point_radius + 2, (255, 255, 255), 1)
            
            # 绘制序号（红色，无黑底，显示在点的正下方）
            text = f"{idx}"
            
            # 获取文字尺寸，用于计算居中位置
            text_size = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, FONT_SCALE * scale_factor, TEXT_THICKNESS)[0]
            text_width = text_size[0]
            text_height = text_size[1]
            
            # 计算文字位置：点的正下方居中
            text_x = x - text_width // 2
            text_y = y + point_radius + int(3 * scale_factor) + text_height  # 点半径 + 间距 + 文字高度
            
            # 直接绘制红色文字
            cv2.putText(
                output,
                text,
                (text_x, text_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                FONT_SCALE * scale_factor,  # 字号也根据放大倍数调整
                (0, 0, 255),  # 红色 (BGR)
                TEXT_THICKNESS,
                cv2.LINE_AA
            )
    
    # 绘制连接线（鼻梁中心线）
    nose_bridge_indices = [168, 6, 197, 195, 4]
    prev_point = None
    for idx in nose_bridge_indices:
        if idx >= len(landmarks):
            continue
        landmark = landmarks[idx]
        orig_x = int(landmark.x * w) - offset_x
        orig_y = int(landmark.y * h) - offset_y
        x = int(orig_x * scale_factor)
        y = int(orig_y * scale_factor)
        
        if 0 <= x < cropped_image.shape[1] and 0 <= y < cropped_image.shape[0]:
            if prev_point:
                cv2.line(output, prev_point, (x, y), (255, 255, 255), int(LINE_THICKNESS * scale_factor))
            prev_point = (x, y)
    
    return output


def add_legend(output_image):
    """添加图例"""
    h, w = output_image.shape[:2]
    legend_height = 120
    legend_img = np.zeros((legend_height, w, 3), dtype=np.uint8)
    legend_img[:] = (30, 30, 30)  # 深灰色背景
    
    # 图例标题
    cv2.putText(
        legend_img,
        "MediaPipe Nose Landmarks Legend",
        (10, 25),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        (255, 255, 255),
        1,
        cv2.LINE_AA
    )
    
    # 图例项
    legends = [
        ((255, 0, 0), "鼻梁 (Bridge)"),
        ((0, 255, 0), "鼻尖 (Tip)"),
        ((0, 255, 255), "鼻翼 (Wings)"),
        ((255, 0, 255), "鼻孔 (Nostrils)"),
        ((0, 165, 255), "鼻底 (Base)"),
    ]
    
    y_offset = 50
    for color, label in legends:
        cv2.circle(legend_img, (20, y_offset), 8, color, -1)
        cv2.putText(
            legend_img,
            label,
            (35, y_offset + 5),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (255, 255, 255),
            1,
            cv2.LINE_AA
        )
        y_offset += 25
    
    # 合并图例和主图
    final_output = np.vstack([output_image, legend_img])
    return final_output


def main():
    """主函数"""
    print("=" * 60)
    print("MediaPipe 鼻子区域关键点可视化工具")
    print("=" * 60)
    
    # 创建输出目录
    output_dir = Path(OUTPUT_IMAGE).parent
    output_dir.mkdir(parents=True, exist_ok=True)
    
    try:
        # 1. 检测人脸关键点
        image, landmarks, w, h = detect_face_landmarks(INPUT_IMAGE)
        
        # 2. 裁剪鼻子区域
        cropped_image, offset_x, offset_y, center_x, center_y = crop_nose_region(
            image, landmarks, w, h
        )
        
        # 3. 绘制关键点
        output_image = draw_nose_landmarks(
            cropped_image, landmarks, w, h, offset_x, offset_y
        )
        
        # 4. 添加图例
        final_output = add_legend(output_image)
        
        # 5. 保存结果
        cv2.imwrite(OUTPUT_IMAGE, final_output)
        print(f"\n[SUCCESS] 结果已保存到: {OUTPUT_IMAGE}")
        print(f"[INFO] 输出图片尺寸: {final_output.shape[1]}x{final_output.shape[0]}")
        
        # 6. 显示统计信息
        print("\n" + "=" * 60)
        print("鼻子关键点统计:")
        print("=" * 60)
        for idx, name in sorted(NOSE_LANDMARKS.items()):
            if idx < len(landmarks):
                lm = landmarks[idx]
                print(f"  [{idx:3d}] {name:12s} - 位置: ({lm.x:.3f}, {lm.y:.3f}, {lm.z:.3f})")
        
        print("\n提示: 可以使用图片查看器打开结果图片查看详细标注")
        
    except Exception as e:
        print(f"\n[ERROR] 处理失败: {e}")
        import traceback
        traceback.print_exc()
        return 1
    
    return 0


if __name__ == "__main__":
    exit(main())
