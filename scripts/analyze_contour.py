#!/usr/bin/env python3
"""
分析GPUPixel轮廓33点的空间分布，设计MediaPipe映射方案

GPUPixel轮廓：开放曲线，从左上方开始，经过下巴，到右上方结束
"""

# GPUPixel FaceTextureCoordinates - 轮廓33点 (0-32)
gpupixel_contour = [
    (0.302451, 0.384169),  # 0 - 左上起点
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
    (0.500000, 0.709867),  # 16 - 下巴最低点
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
    (0.697549, 0.384169),  # 32 - 右上终点
]

print("=== GPUPixel 轮廓33点分析 ===")
print(f"起点 (0): ({gpupixel_contour[0][0]:.3f}, {gpupixel_contour[0][1]:.3f})")
print(f"终点 (32): ({gpupixel_contour[32][0]:.3f}, {gpupixel_contour[32][1]:.3f})")
print(f"下巴最低点 (16): ({gpupixel_contour[16][0]:.3f}, {gpupixel_contour[16][1]:.3f})")
print()

# 分析左右对称性
print("=== 左右对称性分析 ===")
for i in range(17):
    j = 32 - i
    left = gpupixel_contour[i]
    right = gpupixel_contour[j]
    # 以x=0.5为对称轴
    sym_x = 1.0 - right[0]
    print(f"点{i}({left[0]:.3f},{left[1]:.3f}) <-> 点{j}({right[0]:.3f},{right[1]:.3f}), 对称x={sym_x:.3f}")

print()

# 分析y值变化
print("=== Y值变化（从上到下） ===")
for i, (x, y) in enumerate(gpupixel_contour):
    print(f"点{i}: y={y:.3f}")

print()

# 计算相邻点间距
print("=== 相邻点间距 ===")
import math
for i in range(32):
    x1, y1 = gpupixel_contour[i]
    x2, y2 = gpupixel_contour[i+1]
    dist = math.sqrt((x2-x1)**2 + (y2-y1)**2)
    print(f"点{i}->{i+1}: {dist:.4f}")

print()

# 统计特征
xs = [p[0] for p in gpupixel_contour]
ys = [p[1] for p in gpupixel_contour]
print(f"X范围: [{min(xs):.3f}, {max(xs):.3f}]")
print(f"Y范围: [{min(ys):.3f}, {max(ys):.3f}]")
print(f"中心点: ({sum(xs)/len(xs):.3f}, {sum(ys)/len(ys):.3f})")
