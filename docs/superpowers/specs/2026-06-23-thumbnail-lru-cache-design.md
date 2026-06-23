# Thumbnail LRU Cache Design

**Date:** 2026-06-23
**Status:** Implemented
**Author:** guoshuai

## Problem

Gallery MediaGrid scrolling stops displaying thumbnails after ~3 screens. The thumbnails show as dark gray placeholders and never load, even after waiting. Root cause: Coil's request queue saturation.

### Root Cause Chain

```
Scroll → LaunchedEffect triggers prefetchWindow(~30 URIs)
      → imageLoader.enqueue() fills Coil request queue
      → AsyncImage also independently requests visible area thumbnails
      → After 3 screens, Coil request queue is saturated (default max 64 concurrent)
      → New requests are queued but never executed → permanent black blocks
```

The `ThumbnailPrefetcher` and `AsyncImage` share Coil's single `ImageLoader` thread pool + request queue. The prefetcher saturates the queue, and `cancelActiveRequests` can only cancel its own requests — not the ones `AsyncImage` creates internally.

## Solution: Dual-Level Thumbnail Cache + Coil Interceptor

### Architecture

```
MediaItem (AsyncImage, .size(360))  ← UNCHANGED
    ↓
Coil ImageLoader
    ↓
ThumbnailCacheInterceptor  ← NEW: intercepts small-size requests
    ↓
┌─────────────────────────────────────────────┐
│  ThumbnailCache                              │
│                                              │
│  Request: get(uri)                           │
│  ┌─────────────────────────────────────┐    │
│  │ L1: LruMemoryCache                  │    │
│  │   LinkedHashMap (access-order LRU)  │    │
│  │   max 200 entries                   │    │
│  │   HIT → return BitmapDrawable       │    │
│  └──────────┬──────────────────────────┘    │
│             │ MISS                            │
│  ┌──────────▼──────────────────────────┐    │
│  │ L2: ThumbnailDiskCache              │    │
│  │   cacheDir/thumbnails/              │    │
│  │   JPEG Q80, ~15KB per thumbnail     │    │
│  │   HIT → decode → backfill L1 → ret  │    │
│  └──────────┬──────────────────────────┘    │
│             │ MISS                            │
│  ┌──────────▼──────────────────────────┐    │
│  │ ThumbnailGenerator                  │    │
│  │   ContentResolver.loadThumbnail()   │    │
│  │   API 29+, system pre-generated     │    │
│  │   → store L1 + L2 → return          │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
    ↓ FALLBACK (generation fails)
Coil normal flow (ContentUriFetcher → decode → display)
```

### Components

#### 1. `ThumbnailCache` (`core/image/ThumbnailCache.kt`)

Core cache manager. Responsibilities:
- L1: LRU memory cache (LinkedHashMap, access-order, max 200 entries)
- L2: Disk cache (File-based, app cache dir, max 100MB)
- Thumbnail generation via `ContentResolver.loadThumbnail()`
- Thread-safe (synchronized for L1, coroutine for L2 writes)
- Replaces `ThumbnailPrefetcher` entirely

Key API:
```kotlin
suspend fun get(uri: Uri): Bitmap?
suspend fun preload(uris: List<Uri>)
fun evict(uri: Uri)
```

#### 2. `ThumbnailCacheInterceptor` (`core/image/ThumbnailCacheInterceptor.kt`)

Coil `Interceptor` implementation:
- Intercept condition: `request.size.width <= 640 && data is content:// Uri`
- Hit: return `SuccessResult(dataSource = DataSource.MEMORY_CACHE)` — completely bypasses Coil's queue
- Miss: let Coil handle normally, then backfill our cache on success

#### 3. `CoilConfig` Modification

Register `ThumbnailCacheInterceptor` in the ImageLoader builder.

#### 4. Remove `ThumbnailPrefetcher`

The `prefetchWindow()` + `cancelActiveRequests()` pattern is replaced by `ThumbnailCache.preload()`, which writes directly to our cache layers instead of enqueuing into Coil's request queue.

### Disk Cache Management

```
Directory: context.cacheDir/thumbnails/
Filename: <sha256_first_16_hex>.jpg
Single file: ~10-15KB (JPEG Q80, 360px)
Max size: 100MB (~7000-10000 thumbnails)

Eviction strategy:
  - On write: check total size, evict oldest files (by lastModified) if over limit
  - On startup: async cleanup to avoid blocking first screen
```

### Error Handling

```
loadThumbnail returns null:
  1. Video files → try MediaStore.Video.Thumbnails.getThumbnail()
  2. Still null → fallback to Coil normal flow
  3. Coil fails → show placeholder (0xFF2A2A2A), no crash

API < 29 devices:
  Skip cache layers entirely, use Coil original flow transparently
```

### File Changes

| Action | File |
|--------|------|
| NEW | `core/image/ThumbnailCache.kt` |
| NEW | `core/image/ThumbnailCacheInterceptor.kt` |
| MODIFY | `core/image/CoilConfig.kt` — register interceptor |
| MODIFY | `features/gallery/GalleryScreen.kt` — thumbnailPrefetcher → thumbnailCache |
| MODIFY | `features/gallery/components/MediaGrid.kt` — ThumbnailPrefetcher → ThumbnailCache param |
| DELETE | `core/image/ThumbnailPrefetcher.kt` |
| MODIFY | `PicMeApplication.kt` or DI container — provide ThumbnailCache |

### Unchanged Files

- `MediaItem.kt` — `AsyncImage` code completely unchanged; interceptor transparently intercepts
- `MediaRepositoryImpl.kt` — data layer unchanged
- `MediaViewModel.kt` — ViewModel unchanged
- `CoilConfig.kt` existing cache settings — memory/disk cache remain as fallback

## Why This Solves the Problem

1. **Coil request queue is never touched for cached thumbnails.** The interceptor returns results before they enter the queue. The queue saturation problem is completely bypassed.

2. **`ContentResolver.loadThumbnail()` is fast.** The system pre-generates thumbnails when photos are taken. No full-image decoding needed. No IPC overhead beyond a single thumbnail fetch.

3. **200-slot LRU** keeps the most frequently scrolled thumbnails in memory. Each 360px bitmap is ~360KB (ARGB_8888), so 200 entries ≈ 72MB, well within budget.

4. **Disk cache survives app restarts.** Cold start on second launch is fast — thumbnails are read from local JPEG files instead of re-fetching from MediaStore.

5. **`preload()` doesn't pollute Coil's queue.** Background preloading writes directly to our disk cache. Coil is only used as a fallback.
