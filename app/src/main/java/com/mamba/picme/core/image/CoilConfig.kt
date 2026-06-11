package com.mamba.picme.core.image

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

object CoilConfig {
    fun createImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
