// GPUPixel FaceReshapeFilter - 大眼算法（基于106点关键点）
// 参考: gpupixel/src/main/cpp/src/filter/face_reshape_filter.cc
// 注意: uFacePoints, uAspectRatio, uUseGpupixelWarp 在 uniforms.glsl 中已声明

// 大眼效果：径向放大（GPUPixel enlargeEye）
// 注意：GPUPixel原始实现是正向映射（变形坐标后采样），
// 大美丽使用反向映射（从输出位置反推输入位置）
//
// 正向映射公式：output = origin + (input - origin) * w
//   其中 w = 1 - (1 - t^2) * delta, t = dist/radius
//   当 delta>0 时，w<1，坐标向origin收缩 = 放大效果
//
// 反向映射：使用迭代法近似求解
//   给定 output，求 input 使得正向映射后得到 output
//   迭代公式：guess_{n+1} = origin + (output - origin) / w(guess_n)
vec2 gpupixelEnlargeEye(vec2 textureCoord, vec2 originPosition, float radius, float delta) {
    // 计算当前位置到 origin 的距离（用于边界衰减）
    float d_out = distance(
        vec2(textureCoord.x, textureCoord.y / uAspectRatio),
        vec2(originPosition.x, originPosition.y / uAspectRatio)
    );

    // 如果超出影响范围，不做形变
    if (d_out > radius) {
        return textureCoord;
    }

    // 初始猜测
    vec2 guess = textureCoord;

    // 3次迭代求精（Shader中开销可控）
    for (int iter = 0; iter < 3; iter++) {
        float d = distance(
            vec2(guess.x, guess.y / uAspectRatio),
            vec2(originPosition.x, originPosition.y / uAspectRatio)
        );
        float t = d / radius;
        float w = 1.0 - (1.0 - t * t) * delta;
        w = clamp(w, 0.0, 1.0);

        // 根据当前猜测反推更准确的输入
        // output = origin + (guess - origin) * w
        // guess = origin + (output - origin) / w
        guess = originPosition + (textureCoord - originPosition) / max(w, 0.001);
    }

    // 在边界处平滑过渡（使用 cosine 衰减）
    float t_out = d_out / radius;
    float falloff = 0.5 * (1.0 + cos(t_out * 3.14159265));
    // falloff: t=0->1, t=1->0

    return mix(textureCoord, guess, falloff);
}

// 大眼：应用2对控制点变形（GPUPixel bigEye）
vec2 gpupixelBigEye(vec2 currentCoordinate, float bigEyeDelta) {
    // 2对控制点映射（originIndex=瞳孔 -> targetIndex=眼角）
    // 74->72: 右瞳孔 -> 右眼内角
    // 77->75: 左瞳孔 -> 左眼内角
    vec2 faceIndexs[2];
    faceIndexs[0] = vec2(74.0, 72.0);
    faceIndexs[1] = vec2(77.0, 75.0);

    for (int i = 0; i < 2; i++) {
        int originIndex = int(faceIndexs[i].x);
        int targetIndex = int(faceIndexs[i].y);

        vec2 originPoint = getFacePoint(originIndex);
        vec2 targetPoint = getFacePoint(targetIndex);

        float radius = distance(
            vec2(targetPoint.x, targetPoint.y / uAspectRatio),
            vec2(originPoint.x, originPoint.y / uAspectRatio)
        );
        radius = radius * 5.0;
        currentCoordinate = gpupixelEnlargeEye(currentCoordinate, originPoint, radius, bigEyeDelta);
    }
    return currentCoordinate;
}

// 大眼 warp 入口
vec2 warpCoordGpupixelBigEye(vec2 uv) {
    if (uHasFace < 0.5) {
        return uv;
    }

    if (uBigEyes > 0.001) {
        return gpupixelBigEye(uv, uBigEyes);
    }

    return uv;
}
