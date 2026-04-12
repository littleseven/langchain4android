float skinMask(vec2 uv) {
    if (uHasFace < 0.5) {
        return 0.0;
    }
    vec2 delta = uv - uFaceCenter;
    delta.y *= 1.22;
    float faceDist = length(delta) / max(uFaceRadius, 0.001);
    float faceArea = 1.0 - smoothstep(0.72, 1.05, faceDist);
    float eyeRadius = max(uFaceRadius * 0.18, 0.04);
    float leftEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uLeftEye));
    float rightEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uRightEye));
    return clamp(faceArea * leftEyeKeep * rightEyeKeep, 0.0, 1.0);
}

vec4 smoothSkin(vec2 uv, float intensity) {
    vec4 centerColor = texture2D(uTexture, uv);
    float mask = skinMask(uv);
    if (mask < 0.01 || intensity < 0.001) {
        return centerColor;
    }

    vec3 centerRgb = centerColor.rgb;
    float centerL = dot(centerRgb, vec3(0.299, 0.587, 0.114));

    vec2 texelSize = uTexelSize;
    float sigmaSpatial = 1.8;
    float sigmaSpatialSq = sigmaSpatial * sigmaSpatial * 2.0;
    float sigmaRange = 0.10 + intensity * 0.08;
    float sigmaRangeSq = sigmaRange * sigmaRange * 2.0;

    vec3 sum = centerRgb;
    float wSum = 1.0;

    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) continue;
            vec2 offset = vec2(float(x), float(y)) * texelSize;
            vec3 sRgb = texture2D(uTexture, uv + offset).rgb;
            float sL = dot(sRgb, vec3(0.299, 0.587, 0.114));

            float dL = sL - centerL;
            float spatialDistSq = float(x*x + y*y);

            float spatialW = exp(-spatialDistSq / sigmaSpatialSq);
            float rangeW = exp(-(dL * dL) / sigmaRangeSq);

            float w = spatialW * rangeW;
            sum += sRgb * w;
            wSum += w;
        }
    }

    vec3 result = sum / wSum;
    return mix(centerColor, vec4(result, centerColor.a), intensity * mask);
}

vec4 whitenSkin(vec4 color, float intensity, float mask) {
    vec3 whitened = color.rgb * (1.0 + intensity * 0.3 * mask);
    whitened = clamp(whitened, 0.0, 1.0);
    return vec4(whitened, color.a);
}
