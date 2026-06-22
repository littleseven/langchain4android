package com.mamba.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

object CoilConfig {
    fun createImageLoader(context: Context): ImageLoader {
        val cacheDir = File(context.cacheDir, "coil_cache")
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.35)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
