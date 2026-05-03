#!/usr/bin/env python3
"""
重新设计MediaPipe 468 -> GPUPixel 106轮廓映射

GPUPixel轮廓：开放曲线，33点，从左上开始，经过下巴，到右上结束
MediaPipe FACE_OVAL：闭合曲线，36点，包含额头

策略：
1. 从FACE_OVAL中选取脸颊+下巴区域的点（排除额头）
2. 如果不够33个点，从MediaPipe其他脸颊边缘点补充
3. 保持从左到右的顺序
"""

import math

# GPUPixel轮廓33点坐标
gpupixel_contour = [
    (0.302451, 0.384169),  # 0
    (0.302986, 0.409377),  # 1
    (0.304336, 0.434977),  # 2
    (0.306984, 0.460683),  # 3
    (0.311010, 0.486447),  # 4
    (0.316537, 0.511947),  # 5
    (0.323069, 0.536942),  # 6
    (0.331312, 0.561627),  # 7
    (0.342011, 0.585088),  # 8
    (0.355477, 0.607217),  # 9
    (0.371142, 0.627774),  # 10
    (0.388459, 0.646991),  # 11
    (0.407041, 0.665229),  # 12
    (0.426325, 0.682694),  # 13
    (0.447468, 0.697492),  # 14
    (0.471782, 0.707060),  # 15
    (0.500000, 0.709867),  # 16 - 下巴最低
    (0.528218, 0.707060),  # 17
    (0.552532, 0.697492),  # 18
    (0.573675, 0.682694),  # 19
    (0.592959, 0.665229),  # 20
    (0.611541, 0.646991),  # 21
    (0.628858, 0.627774),  # 22
    (0.644523, 0.607217),  # 23
    (0.657989, 0.585088),  # 24
    (0.668688, 0.561627),  # 25
    (0.676931, 0.536942),  # 26
    (0.683463, 0.511947),  # 27
    (0.688990, 0.486447),  # 28
    (0.693016, 0.460683),  # 29
    (0.695664, 0.434977),  # 30
    (0.697014, 0.409377),  # 31
    (0.697549, 0.384169),  # 32
]

# MediaPipe FACE_OVAL 36点（闭合曲线，从额头开始顺时针）
# 根据搜索结果：FACE_OVAL = [10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109]
# 这些点的坐标（基于MediaPipe标准人脸模型，需要归一化）
# 参考：https://github.com/google-ai-edge/mediapipe/issues/1615

# MediaPipe FACE_OVAL点的近似坐标（基于标准人脸模型，y轴向下）
# 注意：这些坐标需要根据实际情况调整
mediapipe_face_oval = {
    10:   (0.500, 0.100),   # 额头中心（最高）- 排除
    338:  (0.430, 0.120),   # 额头左 - 排除
    297:  (0.370, 0.150),   # 额头左 - 排除
    332:  (0.330, 0.190),   # 额头左 - 排除
    284:  (0.310, 0.240),   # 左太阳穴 - 排除
    251:  (0.300, 0.290),   # 左太阳穴 - 排除
    389:  (0.290, 0.340),   # 左脸颊上 - 保留
    356:  (0.280, 0.390),   # 左脸颊 - 保留
    454:  (0.270, 0.440),   # 左脸颊 - 保留
    323:  (0.260, 0.490),   # 左脸颊 - 保留
    361:  (0.250, 0.540),   # 左脸颊 - 保留
    288:  (0.260, 0.590),   # 左脸颊下 - 保留
    397:  (0.280, 0.640),   # 左下巴 - 保留
    365:  (0.310, 0.680),   # 左下巴 - 保留
    379:  (0.350, 0.710),   # 下巴左 - 保留
    378:  (0.400, 0.730),   # 下巴左 - 保留
    400:  (0.450, 0.740),   # 下巴 - 保留
    377:  (0.500, 0.745),   # 下巴中心最低 - 保留
    152:  (0.550, 0.740),   # 下巴 - 保留
    148:  (0.600, 0.730),   # 下巴右 - 保留
    176:  (0.650, 0.710),   # 下巴右 - 保留
    149:  (0.690, 0.680),   # 右下巴 - 保留
    150:  (0.720, 0.640),   # 右下巴 - 保留
    136:  (0.740, 0.590),   # 右脸颊下 - 保留
    172:  (0.750, 0.540),   # 右脸颊 - 保留
    58:   (0.740, 0.490),   # 右脸颊 - 保留
    132:  (0.730, 0.440),   # 右脸颊 - 保留
    93:   (0.720, 0.390),   # 右脸颊 - 保留
    234:  (0.710, 0.340),   # 右脸颊上 - 保留
    127:  (0.700, 0.290),   # 右太阳穴 - 排除
    162:  (0.690, 0.240),   # 右太阳穴 - 排除
    21:   (0.670, 0.190),   # 额头右 - 排除
    54:   (0.630, 0.150),   # 额头右 - 排除
    103:  (0.570, 0.120),   # 额头右 - 排除
    67:   (0.500, 0.100),   # 额头中心（重复）- 排除
    109:  (0.470, 0.110),   # 额头右 - 排除
}

# 确定额头区域阈值（y < 0.35为额头）
FOREHEAD_Y_THRESHOLD = 0.35

# 筛选非额头点，保持顺序
face_oval_no_forehead = []
for idx in [10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109]:
    x, y = mediapipe_face_oval[idx]
    if y >= FOREHEAD_Y_THRESHOLD:
        face_oval_no_forehead.append((idx, x, y))

print(f"FACE_OVAL非额头点数量: {len(face_oval_no_forehead)}")
for idx, x, y in face_oval_no_forehead:
    print(f"  {idx}: ({x:.3f}, {y:.3f})")

# 需要33个点，但只有约28个，需要补充5个点
# 从MediaPipe其他脸颊边缘点补充

# MediaPipe额外的脸颊边缘点（基于468点拓扑）
extra_cheek_points = [
    (215, 0.285, 0.370),  # 左脸颊上
    (213, 0.275, 0.420),  # 左脸颊
    (192, 0.265, 0.470),  # 左脸颊
    (138, 0.740, 0.370),  # 右脸颊上
    (135, 0.725, 0.420),  # 右脸颊
    (214, 0.735, 0.470),  # 右脸颊
]

# 合并所有候选点，按x排序（从左到右）
all_candidates = face_oval_no_forehead + [(idx, x, y) for idx, x, y in extra_cheek_points]
all_candidates.sort(key=lambda t: t[1])  # 按x排序

print(f"\n合并后候选点数量: {len(all_candidates)}")
for idx, x, y in all_candidates:
    print(f"  {idx}: ({x:.3f}, {y:.3f})")

# 使用动态规划选择33个点，使得与GPUPixel轮廓33点的形状最匹配
# 目标：选33个点，保持从左到右顺序，形状与GPUPixel最相似

def select_33_points(candidates, target_contour):
    """
    从候选点中选择33个点，使得与目标轮廓的形状最匹配
    使用贪心算法：按顺序选择，尽量保持与目标点的对应关系
    """
    n = len(candidates)
    m = len(target_contour)  # 33
    
    # 计算每个候选点与每个目标点的匹配代价
    # 代价 = 坐标差异（考虑形状归一化）
    
    # 首先归一化目标轮廓到[0,1]范围
    target_xs = [p[0] for p in target_contour]
    target_ys = [p[1] for p in target_contour]
    target_x_min, target_x_max = min(target_xs), max(target_xs)
    target_y_min, target_y_max = min(target_ys), max(target_ys)
    
    target_normalized = []
    for x, y in target_contour:
        nx = (x - target_x_min) / (target_x_max - target_x_min)
        ny = (y - target_y_min) / (target_y_max - target_y_min)
        target_normalized.append((nx, ny))
    
    # 归一化候选点
    cand_xs = [c[1] for c in candidates]
    cand_ys = [c[2] for c in candidates]
    cand_x_min, cand_x_max = min(cand_xs), max(cand_xs)
    cand_y_min, cand_y_max = min(cand_ys), max(cand_ys)
    
    cand_normalized = []
    for idx, x, y in candidates:
        nx = (x - cand_x_min) / (cand_x_max - cand_x_min)
        ny = (y - cand_y_min) / (cand_y_max - cand_y_min)
        cand_normalized.append((idx, nx, ny))
    
    # 动态规划：dp[i][j] = 前i个候选点中选j个点的最小代价
    # 为了简化，使用贪心：均匀采样33个点
    
    if n == m:
        # 正好33个，全部使用
        return [c[0] for c in candidates]
    elif n > m:
        # 多于33个，均匀选择
        step = n / m
        selected = []
        for i in range(m):
            idx = int(i * step)
            selected.append(candidates[idx][0])
        return selected
    else:
        # 少于33个，需要补充（不应该发生）
        raise ValueError(f"候选点不足: {n} < {m}")

try:
    selected_indices = select_33_points(all_candidates, gpupixel_contour)
    print(f"\n选中的33个MediaPipe点:")
    for i, idx in enumerate(selected_indices):
        print(f"  GPUPixel[{i}] <- MediaPipe[{idx}]")
except Exception as e:
    print(f"错误: {e}")
    print("\n需要重新设计候选点集合...")
