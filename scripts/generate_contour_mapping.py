#!/usr/bin/env python3
"""
生成MediaPipe -> GPUPixel轮廓映射表

策略：
1. 使用MediaPipe FACE_OVAL的脸颊+下巴区域点（排除额头）
2. 通过插值生成33个点
3. 保持从左到右的顺序
"""

# MediaPipe FACE_OVAL 36点（按顺序，从额头开始顺时针）
# FACE_OVAL = [10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109]

# 这些点的近似坐标（基于标准人脸模型，y轴向下）
# 注意：坐标是近似值，用于确定哪些点在额头区域
mediapipe_face_oval_coords = {
    10:   (0.500, 0.100),   # 额头中心 - 排除
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

# 筛选非额头点（y >= 0.30），保持FACE_OVAL顺序
FOREHEAD_Y_THRESHOLD = 0.30

face_oval_order = [10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109]

cheek_chin_points = []
for idx in face_oval_order:
    x, y = mediapipe_face_oval_coords[idx]
    if y >= FOREHEAD_Y_THRESHOLD:
        cheek_chin_points.append((idx, x, y))

print(f"FACE_OVAL脸颊+下巴点数量: {len(cheek_chin_points)}")
for idx, x, y in cheek_chin_points:
    print(f"  {idx}: ({x:.3f}, {y:.3f})")

# 需要33个点，但只有23个
# 方案：在相邻点之间插入新点，使总数达到33个

def interpolate_points(points, target_count):
    """
    在点之间线性插值，使总数达到target_count
    points: [(idx, x, y), ...]
    """
    n = len(points)
    if n >= target_count:
        return points[:target_count]
    
    # 需要插入的点数
    insert_count = target_count - n
    
    # 计算每对相邻点之间需要插入的点数
    # 优先在间距大的地方插入更多点
    distances = []
    for i in range(n - 1):
        x1, y1 = points[i][1], points[i][2]
        x2, y2 = points[i+1][1], points[i+1][2]
        dist = ((x2-x1)**2 + (y2-y1)**2) ** 0.5
        distances.append((i, dist))
    
    # 按距离排序，大的优先
    distances.sort(key=lambda x: x[1], reverse=True)
    
    # 分配插入点数
    inserts = [0] * (n - 1)  # 每对点之间插入的点数
    for i in range(insert_count):
        pair_idx = distances[i % len(distances)][0]
        inserts[pair_idx] += 1
    
    # 生成新点列表
    result = []
    for i in range(n - 1):
        # 添加原始点
        result.append(points[i])
        
        # 添加插值点
        idx1, x1, y1 = points[i]
        idx2, x2, y2 = points[i+1]
        
        for j in range(1, inserts[i] + 1):
            t = j / (inserts[i] + 1)
            # 使用负索引表示插值点
            new_idx = -(idx1 * 1000 + idx2 * 10 + j)  # 唯一标识
            new_x = x1 + (x2 - x1) * t
            new_y = y1 + (y2 - y1) * t
            result.append((new_idx, new_x, new_y))
    
    # 添加最后一个原始点
    result.append(points[-1])
    
    return result

# 插值到33个点
interpolated = interpolate_points(cheek_chin_points, 33)

print(f"\n插值后点数量: {len(interpolated)}")
for i, (idx, x, y) in enumerate(interpolated):
    if idx < 0:
        print(f"  GPUPixel[{i}] = 插值点({x:.3f}, {y:.3f})")
    else:
        print(f"  GPUPixel[{i}] = MediaPipe[{idx}]({x:.3f}, {y:.3f})")

# 生成Kotlin映射表代码
print("\n=== Kotlin映射表代码 ===")
print("// === 脸部轮廓 0-32 (33点) ===")
print("// 基于FACE_OVAL脸颊+下巴区域，通过插值补充到33点")
print("// 开放曲线：左脸颊 -> 下巴 -> 右脸颊")

mapping = []
for i, (idx, x, y) in enumerate(interpolated):
    if idx < 0:
        # 插值点，需要找到对应的MediaPipe点
        # 简化：使用最近的原始点
        # 实际实现中需要在Kotlin代码中进行插值计算
        pass
    else:
        mapping.append(idx)

print(f"原始MediaPipe点: {mapping}")
print(f"数量: {len(mapping)}")

# 由于有插值点，我们需要在Kotlin代码中实现插值逻辑
# 或者，我们可以使用更多的MediaPipe点来避免插值

# 替代方案：使用MediaPipe 468点中更多的脸颊边缘点
print("\n=== 替代方案：使用更多MediaPipe点 ===")

# MediaPipe 468点中额外的脸颊/轮廓点
# 基于468点拓扑，添加更多的脸颊边缘点
extra_points = [
    (215, 0.285, 0.370),  # 左脸颊上
    (213, 0.275, 0.420),  # 左脸颊
    (192, 0.265, 0.470),  # 左脸颊
    (138, 0.740, 0.370),  # 右脸颊上
    (135, 0.725, 0.420),  # 右脸颊
    (214, 0.735, 0.470),  # 右脸颊
    (116, 0.295, 0.320),  # 左脸颊上（更外）
    (117, 0.705, 0.320),  # 右脸颊上（更外）
]

# 合并所有点
all_points = cheek_chin_points + [(idx, x, y) for idx, x, y in extra_points]
# 按x排序（从左到右）
all_points.sort(key=lambda t: t[1])

print(f"合并后候选点数量: {len(all_points)}")
for idx, x, y in all_points:
    print(f"  {idx}: ({x:.3f}, {y:.3f})")

# 如果仍然不够33个，使用插值
if len(all_points) < 33:
    final_points = interpolate_points(all_points, 33)
else:
    # 选择33个最均匀分布的点
    step = len(all_points) / 33
    final_points = [all_points[int(i * step)] for i in range(33)]

print(f"\n最终33个点:")
for i, (idx, x, y) in enumerate(final_points):
    if idx < 0:
        print(f"  GPUPixel[{i}] = 插值点({x:.3f}, {y:.3f})")
    else:
        print(f"  GPUPixel[{i}] = MediaPipe[{idx}]({x:.3f}, {y:.3f})")

# 生成Kotlin数组
print("\n=== Kotlin映射表 ===")
indices = [p[0] for p in final_points]
# 将插值点标记为特殊值（负数），在Kotlin中处理
print(f"val CONTOUR_MAPPING = intArrayOf(")
print(f"    {', '.join(map(str, indices[:10]))},")
print(f"    {', '.join(map(str, indices[10:20]))},")
print(f"    {', '.join(map(str, indices[20:30]))},")
print(f"    {', '.join(map(str, indices[30:33]))}")
print(f")")
