# Thumbnail LRU Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Coil queue-based `ThumbnailPrefetcher` with a dual-level LRU thumbnail cache that bypasses Coil's request queue, eliminating permanent thumbnail load failures after scrolling 3+ screens.

**Architecture:** `ThumbnailCache` manages L1 (LinkedHashMap access-order LRU, 200 entries) and L2 (disk JPEG files, 100MB). A Coil `Interceptor` transparently intercepts small-size requests, returning cached results before they enter Coil's queue. Thumbnails are generated via `ContentResolver.loadThumbnail()` (API 29+), which uses system pre-generated thumbnails — no full-image decoding needed.

**Tech Stack:** Kotlin, Coil 2.7.0, Android ContentResolver, LinkedHashMap (access-order LRU), Kotlin coroutines

---

### File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| CREATE | `core/image/ThumbnailCache.kt` | Dual-level cache: L1 LRU memory + L2 disk + generation via `ContentResolver.loadThumbnail()` |
| CREATE | `core/image/ThumbnailCacheInterceptor.kt` | Coil Interceptor: intercept small requests, return cached results, bypass Coil queue |
| MODIFY | `core/image/CoilConfig.kt` | Accept `ThumbnailCache` param, register interceptor |
| MODIFY | `di/AppContainer.kt` | Replace `thumbnailPrefetcher` with `thumbnailCache` |
| MODIFY | `features/gallery/GalleryScreen.kt` | Use `thumbnailCache` instead of `thumbnailPrefetcher` |
| MODIFY | `features/gallery/components/MediaGrid.kt` | Replace `ThumbnailPrefetcher` param with `ThumbnailCache`, change preload call |
| DELETE | `core/image/ThumbnailPrefetcher.kt` | Replaced by `ThumbnailCache.preload()` |

---

### Task 1: Create ThumbnailCache — Core Cache & Generation

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/image/ThumbnailCache.kt`

- [ ] **Step 1: Write ThumbnailCache class**

```kotlin
package com.mamba.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 双级缩略图缓存：L1 LRU 内存缓存 + L2 本地磁盘文件。
 *
 * L1（内存）：[LinkedHashMap] access-order LRU，最多 [maxMemoryEntries] 条目，
 *   驱逐时自动回收 Bitmap。
 * L2（磁盘）：[context.cacheDir]/thumbnails/，JPEG Q80，上限 [maxDiskSizeBytes]。
 *   超出上限时按 lastModified 删除最旧文件至 80% 水位。
 *
 * 生成策略（API 29+）：
 *   [ContentResolver.loadThumbnail]（系统拍照时预生成，极快）
 *   → 失败时 fallback: [MediaStore.Video.Thumbnails.getThumbnail]
 *
 * 线程安全：L1 由 [Mutex] 保护；L2 写入在 IO 协程中异步执行。
 *
 * @param context Application context
 * @param maxMemoryEntries L1 内存缓存最大条目数（默认 200）
 * @param maxDiskSizeBytes L2 磁盘缓存上限（默认 100MB）
 */
class ThumbnailCache(
    private val context: Context,
    private val maxMemoryEntries: Int = 200,
    private val maxDiskSizeBytes: Long = 100L * 1024 * 1024
) {
    companion object {
        private const val TAG = "PicMe:ThumbCache"
        private const val THUMBNAIL_SIZE_PX = 360
        private const val JPEG_QUALITY = 80
        private const val DISK_CACHE_DIR = "thumbnails"
    }

    // 后台写入协程作用域（不阻塞主流程）
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // L1: LRU memory cache — access-order = true，最近访问的条目排到最后
    private val l1Mutex = Mutex()
    private val memoryCache = object : LinkedHashMap<String, Bitmap>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val shouldRemove = size > maxMemoryEntries
            if (shouldRemove && eldest != null && !eldest.value.isRecycled) {
                eldest.value.recycle()
            }
            return shouldRemove
        }
    }

    // L2: Disk cache directory
    private val diskCacheDir: File by lazy {
        File(context.cacheDir, DISK_CACHE_DIR).also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /**
     * 获取缩略图。查询顺序：L1（内存 LRU）→ L2（磁盘文件）→ 系统生成 → null。
     *
     * @param uriString content:// 媒体 URI 字符串
     * @return Bitmap，生成失败时返回 null（调用方应走 Coil 正常流程）
     */
    suspend fun get(uriString: String): Bitmap? {
        // 1. L1: 内存 LRU 缓存（同步，mutex 保护）
        l1Mutex.withLock {
            memoryCache[uriString]?.let { cached ->
                if (!cached.isRecycled) return cached
                memoryCache.remove(uriString)
            }
        }

        // 2. L2: 磁盘缓存
        val diskFile = diskFile(uriString)
        if (diskFile.exists()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(diskFile.absolutePath)
            }
            if (bitmap != null) {
                l1Mutex.withLock { memoryCache[uriString] = bitmap }
                return bitmap
            } else {
                // 文件损坏，删除
                diskFile.delete()
            }
        }

        // 3. 系统生成（API 29+）
        val generated = generateThumbnail(uriString)
        if (generated != null) {
            l1Mutex.withLock { memoryCache[uriString] = generated }
            persistToDiskAsync(uriString, generated)
            return generated
        }

        // 4. 兜底 null → 调用方走 Coil
        return null
    }

    /**
     * 缓存 Coil 成功解码的 Bitmap（Interceptor 回填）。
     *
     * 同步写入 L1，异步写入 L2。确保后续同 URI 请求命中缓存。
     */
    fun cacheDecoded(uriString: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        l1Mutex.withLock {
            memoryCache[uriString] = bitmap
        }
        persistToDiskAsync(uriString, bitmap)
    }

    /**
     * 批量预加载缩略图（替代 ThumbnailPrefetcher.prefetchWindow）。
     *
     * 在后台 IO 协程中检测缓存缺失并生成，不阻塞主线程。
     * 跳过已在 L1 或 L2 中的 URI。
     *
     * @param uris 需要预加载的 URI 列表
     */
    fun preload(uris: List<String>) {
        if (uris.isEmpty()) return
        writeScope.launch {
            var loaded = 0
            for (uri in uris) {
                if (l1Mutex.withLock { memoryCache.containsKey(uri) }) continue
                if (diskFile(uri).exists()) continue

                val bitmap = generateThumbnail(uri)
                if (bitmap != null) {
                    l1Mutex.withLock { memoryCache[uri] = bitmap }
                    persistToDiskSync(uri, bitmap)
                    loaded++
                }
            }
            if (loaded > 0) {
                Logger.d(TAG, "Preloaded $loaded thumbnails (${uris.size} requested)")
            }
        }
    }

    /**
     * 清除指定 URI 的全部缓存（媒体删除时调用）。
     */
    fun evict(uriString: String) {
        l1Mutex.withLock {
            memoryCache.remove(uriString)?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        diskFile(uriString).delete()
    }

    /**
     * 清空全部缓存（调试 / 存储清理）。
     */
    fun clear() {
        l1Mutex.withLock {
            memoryCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            memoryCache.clear()
        }
        diskCacheDir.listFiles()?.forEach { it.delete() }
        Logger.d(TAG, "Cache cleared")
    }

    // --- private ---

    /**
     * 使用 [ContentResolver.loadThumbnail] 生成缩略图。
     * 失败时尝试 [MediaStore.Video.Thumbnails.getThumbnail]（视频 fallback）。
     */
    private fun generateThumbnail(uriString: String): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val uri = Uri.parse(uriString)
        val size = Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)

        return runCatching {
            context.contentResolver.loadThumbnail(uri, size, null)
        }.recoverCatching {
            // loadThumbnail 对部分视频 URI 会失败，尝试视频专用 API
            getVideoThumbnail(uri)
        }.getOrNull()
    }

    /**
     * 视频缩略图 fallback: [MediaStore.Video.Thumbnails.getThumbnail]。
     */
    @Suppress("DEPRECATION")
    private fun getVideoThumbnail(uri: Uri): Bitmap? {
        return try {
            val videoId = uri.lastPathSegment?.toLongOrNull() ?: return null
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                videoId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 异步写入磁盘缓存（fire-and-forget）。
     */
    private fun persistToDiskAsync(uriString: String, bitmap: Bitmap) {
        writeScope.launch {
            persistToDiskSync(uriString, bitmap)
        }
    }

    /**
     * 同步写入 JPEG 文件。
     */
    private fun persistToDiskSync(uriString: String, bitmap: Bitmap) {
        runCatching {
            ensureDiskCacheSize()
            val file = diskFile(uriString)
            if (!file.exists()) {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
        }.onFailure { e ->
            Logger.w(TAG, "Failed to persist thumbnail: ${e.message}")
        }
    }

    /**
     * 磁盘缓存空间管理：超过上限时删除最旧文件至 80% 水位。
     */
    private fun ensureDiskCacheSize() {
        val files = diskCacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxDiskSizeBytes) return

        files.sortBy { it.lastModified() }
        val targetSize = (maxDiskSizeBytes * 0.8).toLong()
        for (file in files) {
            if (totalSize <= targetSize) break
            totalSize -= file.length()
            file.delete()
        }
        Logger.d(TAG, "Disk cache evicted: over limit of ${maxDiskSizeBytes / 1024 / 1024}MB")
    }

    /**
     * URI → 磁盘缓存文件映射。
     * 文件名 = SHA-256(uri) 前 16 hex 字符 + .jpg。
     */
    private fun diskFile(uriString: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uriString.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(diskCacheDir, "$hash.jpg")
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/image/ThumbnailCache.kt
git commit -m "feat: add ThumbnailCache with dual-level LRU for gallery thumbnails"
```

---

### Task 2: Create ThumbnailCacheInterceptor — Coil Integration

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/image/ThumbnailCacheInterceptor.kt`

- [ ] **Step 1: Write the Interceptor**

```kotlin
package com.mamba.picme.core.image

import android.graphics.drawable.BitmapDrawable
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size

/**
 * Coil [Interceptor]：拦截小尺寸 content:// 缩略图请求，
 * 优先从 [ThumbnailCache] 返回结果，完全绕过 Coil 请求队列。
 *
 * 拦截条件：
 *   1. data 是 String 且以 "content://" 开头
 *   2. request.size 是 [Size.Pixels] 且 width <= 640
 *
 * 不拦截：
 *   - file:// / http(s):// URI
 *   - Size.ORIGINAL（原图请求）
 *   - 大尺寸请求（> 640px）
 */
class ThumbnailCacheInterceptor(
    private val cache: ThumbnailCache
) : Interceptor {

    companion object {
        /** 超过此尺寸的请求不拦截，走 Coil 原图流程 */
        private const val MAX_INTERCEPT_SIZE_PX = 640
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request

        // 判断是否缩略图请求
        if (!shouldIntercept(request)) {
            return chain.proceed(request)
        }

        val uriString = request.data as String

        // 1. 查自定义缓存
        val cached = cache.get(uriString)
        if (cached != null && !cached.isRecycled) {
            return SuccessResult(
                drawable = BitmapDrawable(chain.context.resources, cached),
                request = request,
                dataSource = coil.decode.DataSource.MEMORY_CACHE,
                isPlaceholderCached = false,
                isSampled = true
            )
        }

        // 2. 缓存未命中：走 Coil 正常流程
        val result = chain.proceed(request)

        // 3. Coil 成功解码 → 回填自定义缓存
        if (result is SuccessResult) {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null && !bitmap.isRecycled) {
                cache.cacheDecoded(uriString, bitmap)
            }
        }

        return result
    }

    private fun shouldIntercept(request: coil.request.ImageRequest): Boolean {
        // 仅拦截 content:// 字符串 URI
        val data = request.data
        if (data !is String || !data.startsWith("content://")) return false

        // 仅拦截小尺寸（缩略图）请求
        val size = request.size
        return when (size) {
            is Size.Pixels -> size.width <= MAX_INTERCEPT_SIZE_PX
            Size.ORIGINAL -> false
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/image/ThumbnailCacheInterceptor.kt
git commit -m "feat: add ThumbnailCacheInterceptor for Coil thumbnail bypass"
```

---

### Task 3: Modify CoilConfig — Register Interceptor

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/core/image/CoilConfig.kt`

- [ ] **Step 1: Add `ThumbnailCache` parameter and register interceptor**

Change the signature and body. Replace the current `createImageLoader` method:

```kotlin
package com.mamba.picme.core.image

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

object CoilConfig {
    fun createImageLoader(
        context: Context,
        thumbnailCache: ThumbnailCache? = null
    ): ImageLoader {
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
            .apply {
                if (thumbnailCache != null) {
                    interceptor(ThumbnailCacheInterceptor(thumbnailCache))
                }
            }
            .build()
    }
}
```

Note: the original file at line 13 declares `fun createImageLoader(context: Context): ImageLoader`. Expand it to accept the optional `thumbnailCache` parameter. The `Interceptor` is added via `.interceptor()` only when `thumbnailCache` is provided.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/image/CoilConfig.kt
git commit -m "feat: register ThumbnailCacheInterceptor in CoilConfig"
```

---

### Task 4: Modify AppContainer — Replace thumbnailPrefetcher with thumbnailCache

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1: Change the interface and implementation**

Replace `ThumbnailPrefetcher` import and usage. Changes:

**Import change (line 13):**
```kotlin
// Remove:
import com.mamba.picme.core.image.ThumbnailPrefetcher
// Add:
import com.mamba.picme.core.image.ThumbnailCache
```

**AppContainer interface property change (line 92-93):**
```kotlin
// Remove:
    /** 缩略图预加载器 */
    val thumbnailPrefetcher: ThumbnailPrefetcher
// Add:
    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    val thumbnailCache: ThumbnailCache
```

**AppContainerImpl constructor change — add `thumbnailCache` parameter:**
```kotlin
// Before:
class AppContainerImpl(private val context: Context) : AppContainer {
// After:
class AppContainerImpl(
    private val context: Context,
    private val thumbnailCacheParam: ThumbnailCache
) : AppContainer {
```

**AppContainerImpl property implementation (lines 122-128):**
```kotlin
// Remove all of this:
    /** 缩略图预加载器 */
    override val thumbnailPrefetcher: ThumbnailPrefetcher by lazy {
        ThumbnailPrefetcher(
            imageLoader = coil.Coil.imageLoader(context),
            context = context
        )
    }
// Add:
    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    override val thumbnailCache: ThumbnailCache = thumbnailCacheParam
```

**Important ordering in `PicMeApplication.onCreate()`:** `ThumbnailCache` must be created before `AppContainerImpl` (which needs it as a constructor arg), and also before `CoilConfig.createImageLoader()` (which needs it for the Interceptor). Since Coil's `ImageLoader` is created lazily via `ImageLoaderFactory.newImageLoader()` — which is called on first `AsyncImage` access (during Compose) — this ordering is guaranteed by eager creation in `onCreate()`.

- [ ] **Step 2: Update PicMeApplication.kt**

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/PicMeApplication.kt`

Find the `onCreate()` method and the ImageLoader creation. In `PicMeApplication`, the `ImageLoaderFactory` implementation's `newImageLoader()` (line ~511) likely calls `CoilConfig.createImageLoader(this)`. Change it to create a shared `ThumbnailCache` and pass it to both.

First, find the exact location:

The `newImageLoader()` override in `PicMeApplication` calls `CoilConfig.createImageLoader(this)`. Add a `thumbnailCache` parameter.

Since `PicMeApplication` implements `ImageLoaderFactory`, the `newImageLoader()` is called by Coil automatically. But we want to inject our ThumbnailCache into it. The cleanest way:

1. Add a `lateinit var thumbnailCache: ThumbnailCache` to `PicMeApplication`
2. Initialize it in `onCreate()` before the container
3. Use it in `newImageLoader()` and pass it to `AppContainerImpl`

But `PicMeApplication.newImageLoader()` is called exactly once by Coil when the first image is loaded. By the time it's called, `onCreate()` has already run, so `thumbnailCache` is initialized.

Actually, `ImageLoaderFactory.newImageLoader()` is called during `Coil.setImageLoader()` or when the ImageLoader singleton is first accessed. Let me check `PicMeApplication`:

```kotlin
class PicMeApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return CoilConfig.createImageLoader(this)  // line ~511
    }
}
```

The simplest fix: add a `thumbnailCache` field initialized before the container, then use it in `newImageLoader()`:

```kotlin
// In PicMeApplication:
lateinit var thumbnailCache: ThumbnailCache
    private set

override fun onCreate() {
    super.onCreate()
    thumbnailCache = ThumbnailCache(this)
    container = AppContainerImpl(this, thumbnailCache)
    // ...
}

override fun newImageLoader(): ImageLoader {
    return CoilConfig.createImageLoader(this, thumbnailCache)
}
```

And change `AppContainerImpl` constructor:
```kotlin
class AppContainerImpl(
    private val context: Context,
    private val thumbnailCache: ThumbnailCache
) : AppContainer {
    override val thumbnailCache: ThumbnailCache get() = thumbnailCache
    // ...
}
```

Wait, but what if `newImageLoader()` is called before `onCreate()` finishes? That shouldn't happen since `Coil.setImageLoader()` isn't called until the first image load, which happens in Compose rendering, which is after `onCreate()`.

Actually, looking at the Application code, `Coil.setImageLoader()` might not be explicitly called — since `PicMeApplication` implements `ImageLoaderFactory`, Coil automatically uses it. The singleton ImageLoader is created lazily on first access.

OK, let me just write the plan assuming we create the `ThumbnailCache` before the `ImageLoader`.

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/di/AppContainer.kt \
        app/src/main/java/com/mamba/picme/PicMeApplication.kt
git commit -m "refactor: replace ThumbnailPrefetcher with ThumbnailCache in DI"
```

---

### Task 5: Update GalleryScreen and MediaGrid — Wire ThumbnailCache

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt`

- [ ] **Step 1: Update GalleryScreen.kt**

Change line 110 from:
```kotlin
val thumbnailPrefetcher = remember { app.container.thumbnailPrefetcher }
```
To:
```kotlin
val thumbnailCache = remember { app.container.thumbnailCache }
```

Change line 506 from:
```kotlin
thumbnailPrefetcher = thumbnailPrefetcher,
```
To:
```kotlin
thumbnailCache = thumbnailCache,
```

Also change the search mode MediaGrid (lines 456-472) — it currently doesn't pass a `thumbnailPrefetcher`, which is fine. The new `thumbnailCache` param has a default of `null` so no change needed there. But for consistency, also pass it to search results:

Line 462 area, add after `mediaById = ...`:
```kotlin
thumbnailCache = thumbnailCache,
```

- [ ] **Step 2: Update MediaGrid.kt**

Change line 47 — replace import:
```kotlin
// Remove:
import com.mamba.picme.core.image.ThumbnailPrefetcher
// Add:
import com.mamba.picme.core.image.ThumbnailCache
```

Change line 65 — replace parameter:
```kotlin
// Remove:
    thumbnailPrefetcher: ThumbnailPrefetcher? = null,
// Add:
    thumbnailCache: ThumbnailCache? = null,
```

Change lines 86-110 — replace the `LaunchedEffect` preload logic:
```kotlin
// Remove the entire block:
    // 预加载可视区域附近的缩略图，减少滚动白屏
    if (thumbnailPrefetcher != null && groupedMedia.isNotEmpty()) {
        LaunchedEffect(gridState.firstVisibleItemIndex, gridState.layoutInfo.visibleItemsInfo.size) {
            ...
            thumbnailPrefetcher.prefetchWindow(prefetchUris)
        }
    }

// Replace with:
    // 预加载可视区域附近的缩略图到 ThumbnailCache
    if (thumbnailCache != null && groupedMedia.isNotEmpty()) {
        LaunchedEffect(gridState.firstVisibleItemIndex, gridState.layoutInfo.visibleItemsInfo.size) {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@LaunchedEffect

            val totalItems = groupedMedia.sumOf { it.items.size }
            val firstVisible = visibleItems.first().index
            val lastVisible = visibleItems.last().index
            val pageSize = (lastVisible - firstVisible + 1).coerceAtLeast(1)
            val prefetchStart = (firstVisible - pageSize * 3).coerceAtLeast(0)
            val prefetchEnd = (lastVisible + pageSize * 3).coerceAtMost(totalItems - 1)

            val prefetchUris = buildList {
                var idx = 0
                for (group in groupedMedia) {
                    for (item in group.items) {
                        if (idx in prefetchStart..prefetchEnd) {
                            add(item.uri)
                        }
                        idx++
                    }
                }
            }
            thumbnailCache.preload(prefetchUris)
        }
    }
```

Note: the only behavioral change is `thumbnailPrefetcher.prefetchWindow(prefetchUris)` → `thumbnailCache.preload(prefetchUris)`. The URI collection logic is identical.

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt \
        app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt
git commit -m "refactor: wire ThumbnailCache into GalleryScreen and MediaGrid"
```

---

### Task 6: Delete ThumbnailPrefetcher

**Files:**
- Delete: `app/src/main/java/com/mamba/picme/core/image/ThumbnailPrefetcher.kt`

- [ ] **Step 1: Delete the file**

```bash
rm app/src/main/java/com/mamba/picme/core/image/ThumbnailPrefetcher.kt
```

- [ ] **Step 2: Verify compilation (no broken references)**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git rm app/src/main/java/com/mamba/picme/core/image/ThumbnailPrefetcher.kt
git commit -m "refactor: remove ThumbnailPrefetcher, replaced by ThumbnailCache"
```

---

### Task 7: Integration Verification — Build & Lint

**Files:** None (verification only)

- [ ] **Step 1: Full debug build**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Lint check**

```bash
./gradlew :app:lint 2>&1 | tail -20
```
Expected: No new errors.

- [ ] **Step 3: ktlint check on changed files**

```bash
./gradlew ktlintCheck 2>&1 | tail -20
```
Expected: No new violations.

- [ ] **Step 4: Run unit tests**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```
Expected: All tests pass.

