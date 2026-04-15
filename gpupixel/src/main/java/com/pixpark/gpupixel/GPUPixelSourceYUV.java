/*
 * GPUPixel
 *
 * Created by PixPark on 2021/6/24.
 * Copyright © 2021 PixPark. All rights reserved.
 */

package com.pixpark.gpupixel;

import java.nio.ByteBuffer;

public class GPUPixelSourceYUV extends GPUPixelSource {
    protected GPUPixelSourceYUV() {}

    public static GPUPixelSourceYUV Create() {
        final GPUPixelSourceYUV source = new GPUPixelSourceYUV();
        if (source.mNativeClassID != 0) return source;

        source.mNativeClassID = nativeCreate();
        return source;
    }

    public void SetRotation(int rotation) {
        nativeSetRotation(mNativeClassID, rotation);
    }

    public void ProcessData(ByteBuffer yBuffer, ByteBuffer uBuffer, ByteBuffer vBuffer,
                            int width, int height, int rotation) {
        nativeProcessData(mNativeClassID, yBuffer, uBuffer, vBuffer, width, height, rotation);
    }

    @Override
    public void Destroy() {
        if (mNativeClassID != 0) {
            nativeDestroy(mNativeClassID);
            mNativeClassID = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativeClassID != 0) {
                nativeFinalize(mNativeClassID);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            super.finalize();
        }
    }

    // JNI Native methods
    private static native long nativeCreate();
    private static native void nativeDestroy(long nativeObj);
    private static native void nativeFinalize(long nativeObj);
    private static native void nativeProcessData(
            long nativeObj, ByteBuffer yBuffer, ByteBuffer uBuffer, ByteBuffer vBuffer,
            int width, int height, int rotation);
    private static native void nativeSetRotation(long nativeObj, int rotation);
}
