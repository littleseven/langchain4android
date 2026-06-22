package com.mamba.picme.core.image

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.mamba.picme.core.common.Logger

/**
 * 缩略图预加载器
 *
 * 在 LazyVerticalGrid 滚动时预加载可视区域前后的缩略图，
 * 减少白块闪烁，提升万级图库滑动体验。
 *
 * 预加载窗口：可见区域 + 前后各 3 页（约 30 张）。
 */
class ThumbnailPrefetcher(
    private val imageLoader: ImageLoader,
    private val context: Context
) {
    companion object {
        private const val TAG = "PicMe:Prefetch"
        private const val THUMBNAIL_SIZE = 360
        /** 前后预取页数 */
        private const val PREFETCH_WINDOW_PAGES = 3
    }

    /**
     * 预加载指定 URI 列表的缩略图。
     *
     * @param uris 需要预取的媒体 URI 列表
     */
    fun prefetchWindow(uris: List<String>) {
        if (uris.isEmpty()) return

        val requests = uris.mapNotNull { uri ->
            try {
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(THUMBNAIL_SIZE)
                    .build()
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to build prefetch request for $uri: ${e.message}")
                null
            }
        }

        if (requests.isNotEmpty()) {
            for (request in requests) {
                imageLoader.enqueue(request)
            }
            Logger.d(TAG, "Prefetched ${requests.size} thumbnails")
        }
    }
}
