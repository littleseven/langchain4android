void main() {
    vec2 warpedUv;
    if (uUseGpupixelWarp > 0) {
        // GPUPixel风格：瘦脸和大眼分开调用，便于独立调试
        warpedUv = warpCoordGpupixelThinFace(vTextureCoord);
        warpedUv = warpCoordGpupixelBigEye(warpedUv);
    } else {
        warpedUv = warpCoord(vTextureCoord);
    }
    
    // Debug Mode: Show Skin Mask
    if (uDebugMode == 1) {
        float mask = skinMask(warpedUv);
        gl_FragColor = vec4(mask, mask, mask, 1.0);
        return;
    }

    // Debug Mode: Show Warp Offset
    if (uDebugMode == 2) {
        vec2 offset = warpedUv - vTextureCoord;
        float len = length(offset) * 100.0;
        gl_FragColor = vec4(len, 0.0, 1.0 - len, 1.0);
        return;
    }

    // Normal Rendering Pipeline
    float mask = skinMask(warpedUv);
    vec4 smoothed = smoothSkin(warpedUv, uSmoothing);
    vec4 whitened = whitenSkin(smoothed, uWhitening, mask);
    vec4 lipTinted = applyLipTint(whitened, warpedUv);
    
    vec3 makeupColor = applyBlush(lipTinted.rgb, warpedUv);
    vec3 graded = applyColorGrade(makeupColor);
    vec4 finalColor = vec4(clamp(graded, 0.0, 1.0), lipTinted.a);

    // Color Matrix Filter
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
