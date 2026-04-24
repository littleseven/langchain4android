package com.picme.beauty.egl

/**
 * R 计划 - 美颜 Shader 定义
 *
 * 包含：
 * 1. 顶点着色器（通用）
 * 2. 磨皮算法（双边滤波快速近似，9点采样+值域高斯权重）
 * 3. 美白算法（亮度提升）
 * 4. 组合效果（大眼、瘦脸、唇色、腮红）
 */
object BeautyShaders {

    val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uTextureTransform;
        varying vec2 vTextureCoord;

        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uTextureTransform * aTextureCoord).xy;
        }
    """.trimIndent()

    val FRAGMENT_SHADER_BEAUTY = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        uniform samplerExternalOES uTexture;
        uniform vec2 uTexelSize;
        uniform float uSmoothing;
        uniform float uWhitening;
        uniform float uBigEyes;
        uniform float uSlimFace;
        uniform vec2 uFaceCenter;
        uniform vec2 uLeftEye;
        uniform vec2 uRightEye;
        uniform vec2 uMouthCenter;
        uniform vec2 uMouthLeft;
        uniform vec2 uMouthRight;
        uniform vec2 uUpperLipCenter;
        uniform vec2 uLowerLipCenter;
        uniform vec2 uLipOuterContourPoints[20];
        uniform float uLipOuterContourCount;
        uniform vec2 uLipInnerContourPoints[20];
        uniform float uLipInnerContourCount;
        uniform float uFaceRadius;
        uniform float uHasFace;
        uniform float uLipColor;
        uniform int uLipColorIndex;
        uniform float uBlush;
        uniform int uBlushColorFamily;
        // 锐化增强（借鉴 GPUPixel 算法）
        uniform float uSharpen;
        // 色调滤镜矩阵（4x5 ColorMatrix 分解为 4 行系数 + 1 个归一化平移量）
        // uCMRow0~3：每行的 RGBA 系数（行主序）
        // uCMOffset：平移量（第5列）已除以 255 归一化到 0~1
        // uHasColorMatrix：1.0 表示有色调滤镜，0.0 表示直通
        uniform vec4 uCMRow0;
        uniform vec4 uCMRow1;
        uniform vec4 uCMRow2;
        uniform vec4 uCMRow3;
        uniform vec4 uCMOffset;
        uniform float uHasColorMatrix;
        uniform float uExposure;      // -10.0 ~ +10.0, default 0.0
        uniform float uContrast;      // 0.0 ~ 4.0, default 1.0
        uniform float uSaturation;    // 0.0 ~ 2.0, default 1.0
        uniform float uTemperature;   // -1.0 ~ +1.0, default 0.0 (色温: 蓝-黄偏移)
        uniform float uTint;          // -1.0 ~ +1.0, default 0.0 (色调: 绿-品红偏移)
        uniform float uBrightness;    // -1.0 ~ +1.0, default 0.0
        uniform float uRedAdj;        // 0.0 ~ 2.0, default 1.0
        uniform float uGreenAdj;      // 0.0 ~ 2.0, default 1.0
        uniform float uBlueAdj;       // 0.0 ~ 2.0, default 1.0
        varying vec2 vTextureCoord;

        vec2 applyBigEye(vec2 uv, vec2 eyeCenter, float radius, float intensity) {
            vec2 dir = uv - eyeCenter;
            float dist = length(dir);
            if (dist >= radius) {
                return uv;
            }
            float percent = 1.0 - dist / radius;
            float factor = 1.0 - intensity * percent * 0.38;
            return eyeCenter + dir * factor;
        }

        vec2 applySlimFace(vec2 uv, vec2 center, float radius, float intensity) {
            vec2 dir = uv - center;
            float dist = length(dir);
            if (dist >= radius) {
                return uv;
            }
            vec2 eyeAxis = normalize(uRightEye - uLeftEye);
            if (length(eyeAxis) < 0.0001) {
                eyeAxis = vec2(1.0, 0.0);
            }
            float percent = 1.0 - dist / radius;
            float strength = intensity * percent * percent * 0.45;
            float axisOffset = dot(dir, eyeAxis) / max(radius, 0.0001);
            vec2 offset = eyeAxis * axisOffset * strength * radius;
            return uv - offset;
        }

        vec2 warpCoord(vec2 uv) {
            if (uHasFace < 0.5) {
                return uv;
            }
            float eyeRadius = max(uFaceRadius * 0.22, 0.05);
            vec2 warped = uv;
            if (uBigEyes > 0.001) {
                warped = applyBigEye(warped, uLeftEye, eyeRadius, uBigEyes);
                warped = applyBigEye(warped, uRightEye, eyeRadius, uBigEyes);
            }
            if (abs(uSlimFace) > 0.001) {
                warped = applySlimFace(warped, uFaceCenter, uFaceRadius, uSlimFace);
            }
            return clamp(warped, 0.0, 1.0);
        }

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

        vec3 lipColorByIndex(int index) {
            if (index == 1) return vec3(0.77, 0.20, 0.26);
            if (index == 2) return vec3(1.00, 0.50, 0.31);
            if (index == 3) return vec3(0.88, 0.32, 0.49);
            if (index == 4) return vec3(1.00, 0.42, 0.62);
            if (index == 5) return vec3(0.61, 0.14, 0.21);
            if (index == 6) return vec3(1.00, 0.63, 0.48);
            if (index == 7) return vec3(0.80, 0.36, 0.36);
            if (index == 8) return vec3(0.86, 0.08, 0.24);
            if (index == 9) return vec3(1.00, 0.71, 0.76);
            if (index == 10) return vec3(0.70, 0.13, 0.13);
            if (index == 11) return vec3(1.00, 0.08, 0.58);
            return vec3(0.83, 0.46, 0.49);
        }

        float distancePointToSegment(vec2 point, vec2 a, vec2 b) {
            vec2 ab = b - a;
            float len2 = dot(ab, ab);
            if (len2 < 0.000001) {
                return length(point - a);
            }
            float t = clamp(dot(point - a, ab) / len2, 0.0, 1.0);
            vec2 projection = a + ab * t;
            return length(point - projection);
        }

        float contourMaskFromOuterPolygon(vec2 uv) {
            if (uLipOuterContourCount < 6.0) {
                return 0.0;
            }
            bool inside = false;
            float minDist = 10.0;
            vec2 firstPoint = vec2(0.0, 0.0);
            vec2 prevPoint = vec2(0.0, 0.0);
            bool hasPrev = false;
            for (int i = 0; i < 20; i++) {
                if (float(i) >= uLipOuterContourCount) {
                    continue;
                }
                vec2 current = uLipOuterContourPoints[i];
                if (!hasPrev) {
                    firstPoint = current;
                    prevPoint = current;
                    hasPrev = true;
                    continue;
                }
                bool cross = ((prevPoint.y > uv.y) != (current.y > uv.y)) &&
                    (uv.x < (current.x - prevPoint.x) * (uv.y - prevPoint.y) / (current.y - prevPoint.y + 0.000001) + prevPoint.x);
                if (cross) {
                    inside = !inside;
                }
                minDist = min(minDist, distancePointToSegment(uv, prevPoint, current));
                prevPoint = current;
            }
            if (hasPrev) {
                bool cross = ((prevPoint.y > uv.y) != (firstPoint.y > uv.y)) &&
                    (uv.x < (firstPoint.x - prevPoint.x) * (uv.y - prevPoint.y) / (firstPoint.y - prevPoint.y + 0.000001) + prevPoint.x);
                if (cross) {
                    inside = !inside;
                }
                minDist = min(minDist, distancePointToSegment(uv, prevPoint, firstPoint));
            }
            float feather = max(uFaceRadius * 0.025, 0.004);
            float edgeSoft = smoothstep(0.0, feather, minDist);
            return (inside ? 1.0 : 0.0) * edgeSoft;
        }

        float contourMaskFromInnerPolygon(vec2 uv) {
            if (uLipInnerContourCount < 6.0) {
                return 0.0;
            }
            bool inside = false;
            float minDist = 10.0;
            vec2 firstPoint = vec2(0.0, 0.0);
            vec2 prevPoint = vec2(0.0, 0.0);
            bool hasPrev = false;
            for (int i = 0; i < 20; i++) {
                if (float(i) >= uLipInnerContourCount) {
                    continue;
                }
                vec2 current = uLipInnerContourPoints[i];
                if (!hasPrev) {
                    firstPoint = current;
                    prevPoint = current;
                    hasPrev = true;
                    continue;
                }
                bool cross = ((prevPoint.y > uv.y) != (current.y > uv.y)) &&
                    (uv.x < (current.x - prevPoint.x) * (uv.y - prevPoint.y) / (current.y - prevPoint.y + 0.000001) + prevPoint.x);
                if (cross) {
                    inside = !inside;
                }
                minDist = min(minDist, distancePointToSegment(uv, prevPoint, current));
                prevPoint = current;
            }
            if (hasPrev) {
                bool cross = ((prevPoint.y > uv.y) != (firstPoint.y > uv.y)) &&
                    (uv.x < (firstPoint.x - prevPoint.x) * (uv.y - prevPoint.y) / (firstPoint.y - prevPoint.y + 0.000001) + prevPoint.x);
                if (cross) {
                    inside = !inside;
                }
                minDist = min(minDist, distancePointToSegment(uv, prevPoint, firstPoint));
            }
            float feather = max(uFaceRadius * 0.025, 0.004);
            float edgeSoft = smoothstep(0.0, feather, minDist);
            return (inside ? 1.0 : 0.0) * edgeSoft;
        }

        vec3 blushColorByFamily(int family) {
            if (family == 1) return vec3(1.00, 0.62, 0.45);
            if (family == 2) return vec3(0.67, 0.31, 0.52);
            return vec3(1.00, 0.56, 0.67);
        }

        vec4 sharpenSkin(vec4 color, vec2 uv, float sharpenIntensity) {
            if (sharpenIntensity < 0.001) return color;
            
            // 拉普拉斯锐化（9点采样，与 GPUPixel 一致）
            vec3 center = color.rgb;
            vec3 sum = center * 0.25;
            sum += texture2D(uTexture, uv + vec2(-uTexelSize.x, 0.0)).rgb * 0.125;
            sum += texture2D(uTexture, uv + vec2(uTexelSize.x, 0.0)).rgb * 0.125;
            sum += texture2D(uTexture, uv + vec2(0.0, -uTexelSize.y)).rgb * 0.125;
            sum += texture2D(uTexture, uv + vec2(0.0, uTexelSize.y)).rgb * 0.125;
            sum += texture2D(uTexture, uv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb * 0.0625;
            sum += texture2D(uTexture, uv + vec2(uTexelSize.x, -uTexelSize.y)).rgb * 0.0625;
            sum += texture2D(uTexture, uv + vec2(-uTexelSize.x, uTexelSize.y)).rgb * 0.0625;
            sum += texture2D(uTexture, uv + vec2(uTexelSize.x, uTexelSize.y)).rgb * 0.0625;
            
            vec3 hPass = center - sum;
            vec3 sharpened = center + sharpenIntensity * hPass * 2.0;
            
            return vec4(clamp(sharpened, 0.0, 1.0), color.a);
        }

        float lipColorMaskFromPixel(vec3 baseColor) {
            float luma = dot(baseColor, vec3(0.299, 0.587, 0.114));
            float maxChannel = max(baseColor.r, max(baseColor.g, baseColor.b));
            float minChannel = min(baseColor.r, min(baseColor.g, baseColor.b));
            float saturation = maxChannel - minChannel;
            float redness = baseColor.r - max(baseColor.g, baseColor.b);
            float redGate = smoothstep(0.02, 0.16, redness);
            float satGate = smoothstep(0.05, 0.24, saturation);
            float darkGate = 1.0 - smoothstep(0.78, 0.98, luma);
            return clamp(redGate * satGate * darkGate, 0.0, 1.0);
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

        vec4 applyLipTint(vec4 color, vec2 uv) {
            if (uHasFace < 0.5 || uLipColor < 0.01) {
                return color;
            }
            vec2 eyeAxis = normalize(uRightEye - uLeftEye);
            if (length(eyeAxis) < 0.0001) {
                eyeAxis = vec2(1.0, 0.0);
            }
            vec2 mouthLeft = uMouthLeft;
            vec2 mouthRight = uMouthRight;
            vec2 upperLipCenter = uUpperLipCenter;
            vec2 lowerLipCenter = uLowerLipCenter;
            vec2 mouthCenter = (mouthLeft + mouthRight + upperLipCenter + lowerLipCenter) * 0.25;
            vec2 mouthAxisRaw = mouthRight - mouthLeft;
            float mouthAxisLen = length(mouthAxisRaw);
            vec2 mouthAxis = mouthAxisLen > 0.0001 ? normalize(mouthAxisRaw) : eyeAxis;
            vec2 mouthNormal = vec2(-mouthAxis.y, mouthAxis.x);
            if (dot(lowerLipCenter - upperLipCenter, mouthNormal) < 0.0) {
                mouthNormal = -mouthNormal;
            }
            vec2 delta = uv - mouthCenter;
            float localX = dot(delta, mouthAxis);
            float localY = dot(delta, mouthNormal);
            float leftWidth = max(abs(dot(mouthCenter - mouthLeft, mouthAxis)) * 1.06, uFaceRadius * 0.11);
            float rightWidth = max(abs(dot(mouthRight - mouthCenter, mouthAxis)) * 1.06, uFaceRadius * 0.11);
            float activeHalfWidth = localX >= 0.0 ? rightWidth : leftWidth;
            float upperHeight = max(abs(dot(upperLipCenter - mouthCenter, mouthNormal)) * 2.0, uFaceRadius * 0.038);
            float lowerHeight = max(abs(dot(lowerLipCenter - mouthCenter, mouthNormal)) * 2.0, uFaceRadius * 0.05);
            float x = localX / max(activeHalfWidth, 0.0001);
            float upperY = max(localY, 0.0) / max(upperHeight, 0.0001);
            float lowerY = max(-localY, 0.0) / max(lowerHeight, 0.0001);
            float upperEllipse = x * x + upperY * upperY;
            float lowerEllipse = x * x + lowerY * lowerY;
            float upperMask = 1.0 - smoothstep(0.45, 1.0, upperEllipse);
            float lowerMask = 1.0 - smoothstep(0.45, 1.0, lowerEllipse);
            float cornerMask = 1.0 - smoothstep(0.92, 1.22, abs(x));
            float seamWidth = max((upperHeight + lowerHeight) * 0.08, uFaceRadius * 0.006);
            float sideSwitch = max((upperHeight + lowerHeight) * 0.06, uFaceRadius * 0.004);
            float upperSideMask = smoothstep(-sideSwitch, sideSwitch, localY);
            float lowerSideMask = smoothstep(-sideSwitch, sideSwitch, -localY);
            float seamFade = 1.0 - smoothstep(0.0, seamWidth, abs(localY));
            float splitMask = max(upperMask * upperSideMask, lowerMask * lowerSideMask);
            float fallbackMask = clamp(splitMask * cornerMask * (1.0 - 0.22 * seamFade), 0.0, 1.0);
            float outerContourMask = contourMaskFromOuterPolygon(uv);
            float innerContourMask = contourMaskFromInnerPolygon(uv);
            float contourMask = clamp(outerContourMask - innerContourMask, 0.0, 1.0);
            float contourPreferredMask = max(contourMask, fallbackMask * 0.78);
            float lipMask = mix(fallbackMask, contourPreferredMask, step(6.0, uLipOuterContourCount));
            vec3 target = lipColorByIndex(uLipColorIndex);
            float colorMask = lipColorMaskFromPixel(color.rgb);
            float edgeAwareLipMask = lipMask * mix(0.42, 1.0, colorMask);
            float blend = clamp(uLipColor, 0.0, 1.0) * 0.78 * edgeAwareLipMask;
            vec3 tinted = mix(color.rgb, target, blend);
            return vec4(clamp(tinted, 0.0, 1.0), color.a);
        }

        vec3 applyColorGrade(vec3 color) {
            // 曝光: color * pow(2.0, exposure)
            color *= pow(2.0, uExposure);
            // 对比度: (color - 0.5) * contrast + 0.5
            color = (color - 0.5) * uContrast + 0.5;
            // 饱和度: mix(luma, color, saturation)
            float luma = dot(color, vec3(0.299, 0.587, 0.114));
            color = mix(vec3(luma), color, uSaturation);
            // 色温: 红增蓝减
            color.r += uTemperature * 0.01;
            color.b -= uTemperature * 0.01;
            // 色调: 绿增蓝减
            color.g += uTint * 0.005;
            color.b -= uTint * 0.005;
            // 亮度
            color += uBrightness;
            // RGB 通道调整
            color.r *= uRedAdj;
            color.g *= uGreenAdj;
            color.b *= uBlueAdj;
            return clamp(color, 0.0, 1.0);
        }

        void main() {
            vec2 warpedUv = warpCoord(vTextureCoord);
            float mask = skinMask(warpedUv);
            vec4 smoothed = smoothSkin(warpedUv, uSmoothing);
            vec4 whitened = whitenSkin(smoothed, uWhitening, mask);
            
            // 应用锐化增强（仅在皮肤区域）
            vec4 sharpened = sharpenSkin(whitened, warpedUv, uSharpen * mask);
            
            vec4 lipTinted = applyLipTint(sharpened, warpedUv);
            float blushMask = blushMaskFromCheeks(warpedUv);
            float blushBlend = clamp(uBlush, 0.0, 1.0) * 0.28 * blushMask;
            vec3 blushTarget = blushColorByFamily(uBlushColorFamily);
            vec3 makeupColor = mix(lipTinted.rgb, blushTarget, blushBlend);
            vec3 graded = applyColorGrade(makeupColor);
            vec4 finalColor = vec4(graded, lipTinted.a);

            // 色调滤镜矩阵变换（uHasColorMatrix > 0.5 时生效）
            if (uHasColorMatrix > 0.5) {
                vec4 src = finalColor;
                float r = dot(uCMRow0, src) + uCMOffset.r;
                float g = dot(uCMRow1, src) + uCMOffset.g;
                float b = dot(uCMRow2, src) + uCMOffset.b;
                float a = dot(uCMRow3, src) + uCMOffset.a;
                finalColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), clamp(a, 0.0, 1.0));
            }

            gl_FragColor = finalColor;
        }
    """.trimIndent()

    val FRAGMENT_SHADER_DEBUG_RED = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
        }
    """.trimIndent()

    val FRAGMENT_SHADER_DEBUG_TEXTURE_R = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTextureCoord;
        void main() {
            vec4 color = texture2D(uTexture, vTextureCoord);
            gl_FragColor = vec4(color.r, color.r, color.r, 1.0);
        }
    """.trimIndent()
}

