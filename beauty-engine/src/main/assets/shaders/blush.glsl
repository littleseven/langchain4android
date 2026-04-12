vec3 blushColorByFamily(int family) {
    if (family == 1) return vec3(1.00, 0.62, 0.45);
    if (family == 2) return vec3(0.67, 0.31, 0.52);
    return vec3(1.00, 0.56, 0.67);
}

float blushMaskFromCheeks(vec2 uv) {
    if (uHasFace < 0.5 || uBlush < 0.001) {
        return 0.0;
    }
    vec2 faceRight = normalize(uRightEye - uLeftEye);
    if (length(faceRight) < 0.0001) {
        faceRight = vec2(1.0, 0.0);
    }
    vec2 eyeCenter = (uLeftEye + uRightEye) * 0.5;
    vec2 mouthCenter = (uMouthLeft + uMouthRight) * 0.5;
    vec2 faceUp = normalize(eyeCenter - mouthCenter);
    if (length(faceUp) < 0.0001) {
        faceUp = vec2(-faceRight.y, faceRight.x);
    }
    float eyeWidth = max(length(uRightEye - uLeftEye), 0.12);
    float eyeMouthDist = max(length(eyeCenter - mouthCenter), uFaceRadius * 0.55);
    float faceAspect = clamp(eyeMouthDist / eyeWidth, 0.9, 1.8);
    float roundFace = clamp((1.28 - faceAspect) / 0.28, 0.0, 1.0);
    float longFace = clamp((faceAspect - 1.40) / 0.30, 0.0, 1.0);
    float appleBaseFactor = 0.34 + longFace * 0.05 - roundFace * 0.03;
    vec2 appleBase = eyeCenter - faceUp * max(eyeMouthDist * appleBaseFactor, uFaceRadius * 0.17);
    float cheekOffsetX = max(eyeWidth * (0.34 + roundFace * 0.05 - longFace * 0.02), uFaceRadius * (0.31 + roundFace * 0.03));
    float cheekOffsetY = max(eyeMouthDist * (0.06 + roundFace * 0.05 - longFace * 0.03), uFaceRadius * (0.03 + roundFace * 0.04 - longFace * 0.01));
    vec2 leftCheekCenter = appleBase - faceRight * cheekOffsetX + faceUp * cheekOffsetY;
    vec2 rightCheekCenter = appleBase + faceRight * cheekOffsetX + faceUp * cheekOffsetY;
    float radiusX = max(uFaceRadius * (0.128 + longFace * 0.018 + roundFace * 0.005), 0.05);
    float radiusY = max(uFaceRadius * (0.102 + roundFace * 0.010 - longFace * 0.008), 0.04);
    vec2 leftDelta = uv - leftCheekCenter;
    float leftX = dot(leftDelta, faceRight) / radiusX;
    float leftY = dot(leftDelta, faceUp) / radiusY;
    float leftEllipse = leftX * leftX + leftY * leftY;
    float leftMask = 1.0 - smoothstep(0.48, 1.0, leftEllipse);
    vec2 rightDelta = uv - rightCheekCenter;
    float rightX = dot(rightDelta, faceRight) / radiusX;
    float rightY = dot(rightDelta, faceUp) / radiusY;
    float rightEllipse = rightX * rightX + rightY * rightY;
    float rightMask = 1.0 - smoothstep(0.48, 1.0, rightEllipse);
    float centerGap = smoothstep(uFaceRadius * 0.18, uFaceRadius * 0.30, abs(dot(uv - eyeCenter, faceRight)));
    float mouthGap = smoothstep(uFaceRadius * 0.10, uFaceRadius * 0.23, dot(uv - mouthCenter, faceUp));
    float mouthCornerGapLeft = smoothstep(uFaceRadius * 0.08, uFaceRadius * 0.18, length(uv - uMouthLeft));
    float mouthCornerGapRight = smoothstep(uFaceRadius * 0.08, uFaceRadius * 0.18, length(uv - uMouthRight));
    float eyeSafeRadius = max(uFaceRadius * 0.17, eyeWidth * 0.28);
    float leftEyeGap = smoothstep(eyeSafeRadius * 0.58, eyeSafeRadius, length(uv - uLeftEye));
    float rightEyeGap = smoothstep(eyeSafeRadius * 0.58, eyeSafeRadius, length(uv - uRightEye));
    float underEyeGap = smoothstep(uFaceRadius * 0.04, uFaceRadius * 0.12, -dot(uv - eyeCenter, faceUp));
    float eyeGap = leftEyeGap * rightEyeGap * underEyeGap;
    float sideSplitLeft = smoothstep(-uFaceRadius * 0.02, uFaceRadius * 0.09, dot(leftCheekCenter - uv, faceRight));
    float sideSplitRight = smoothstep(-uFaceRadius * 0.02, uFaceRadius * 0.09, dot(uv - rightCheekCenter, faceRight));
    leftMask *= sideSplitLeft;
    rightMask *= sideSplitRight;
    float cheekMask = max(leftMask, rightMask) * centerGap * mouthGap * mouthCornerGapLeft * mouthCornerGapRight * eyeGap;
    return clamp(cheekMask, 0.0, 1.0);
}
