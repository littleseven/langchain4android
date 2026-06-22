# Gallery Scroll Performance Optimization — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate white-screen during gallery scrolling (~8000 photos) by adding Coil thumbnail size constraint, connecting the existing ThumbnailPrefetcher, and tuning memory cache.

**Architecture:** Image-loading-layer optimization only — no data/architecture changes. `AsyncImage` gets `.size(360)` so Coil decodes to thumbnail dimensions directly. `MediaGrid` tracks scroll position and feeds nearby URIs to `ThumbnailPrefetcher`. Coil memory cache bumped from 25% → 35% with strong references enabled.

**Tech Stack:** Jetpack Compose, Coil 2.7.0, Kotlin coroutines

---

### Task 1: CoilConfig cache tuning

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/core/image/CoilConfig.kt:19-22`

- [ ] **Step 1: Adjust memory cache parameters**

```kotlin
// Replace lines 19-22:
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }

// With:
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.35)
                    .strongReferencesEnabled(true)
                    .build()
            }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/image/CoilConfig.kt
git commit -m "perf(gallery): increase Coil memory cache to 35% and enable strong references"
```

---

### Task 2: AsyncImage size constraint

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt:158-162`

- [ ] **Step 1: Add `.size(360)` to ImageRequest**

```kotlin
// Replace lines 158-162:
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(asset.uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

// With:
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(asset.uri)
                .size(360)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
```

360px covers xxhdpi (330px for 110dp × 3x) and is close enough for xxxhdpi (385px). Coil uses this as the max dimension and scales proportionally.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt
git commit -m "perf(gallery): add size constraint to AsyncImage thumbnail decoding"
```

---

### Task 3: Connect ThumbnailPrefetcher to MediaGrid

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`

- [ ] **Step 1: Add import and parameter to MediaGrid**

In `MediaGrid.kt`, add the import at the top:

```kotlin
import com.mamba.picme.core.image.ThumbnailPrefetcher
```

Add `thumbnailPrefetcher` parameter to `MediaGrid` function signature (after `mediaById`):

```kotlin
@Composable
fun MediaGrid(
    context: Context,
    groupedMedia: List<GroupedMedia>,
    selectedIds: List<Long>,
    isSelectionMode: Boolean,
    thumbnailPositions: Map<Long, Rect>,
    mediaById: Map<Long, MediaAsset>,
    thumbnailPrefetcher: ThumbnailPrefetcher? = null,  // NEW
    onThumbnailPositioned: (Long, Rect) -> Unit,
    onMediaClick: (MediaAsset) -> Unit,
    onMediaLongClick: (MediaAsset) -> Unit,
    onDragSelectionStart: (MediaAsset) -> Unit,
    onDragSelectionItem: (MediaAsset) -> Unit,
    onDragSelectionEnd: () -> Unit
)
```

- [ ] **Step 2: Replace the stateless LazyVerticalGrid with a stateful one**

Inside `MediaGrid`, before `LazyVerticalGrid(`, add:

```kotlin
    val gridState = rememberLazyGridState()

    // Prefetch thumbnails for nearby items on scroll
    if (thumbnailPrefetcher != null && groupedMedia.isNotEmpty()) {
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
            thumbnailPrefetcher.prefetchWindow(prefetchUris)
        }
    }
```

Then add `state = gridState` to the `LazyVerticalGrid(...)` call.

- [ ] **Step 3: Pass thumbnailPrefetcher from GalleryScreen**

In `GalleryScreen.kt`, add near the top after `val context = LocalContext.current`:

```kotlin
    val app = context.applicationContext as com.mamba.picme.PicMeApplication
    val thumbnailPrefetcher = remember { app.container.thumbnailPrefetcher }
```

Then add `thumbnailPrefetcher = thumbnailPrefetcher` to both `MediaGrid(...)` calls (the main grid and the search result grid).

Note: Remove the duplicate `val app = ...` on line 167 (the one used for indexing/face clustering) — reuse the one declared above.

- [ ] **Step 4: Compile check**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt \
        app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt
git commit -m "perf(gallery): connect ThumbnailPrefetcher to MediaGrid scroll events"
```

---

### Task 4: allFlatMedia derivedStateOf optimization

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt:91`

- [ ] **Step 1: Replace remember with derivedStateOf**

```kotlin
// Replace line 91:
    val allFlatMedia = remember(groupedMedia) { groupedMedia.flatMap { group -> group.items } }

// With:
    val allFlatMedia by remember { derivedStateOf { groupedMedia.flatMap { group -> group.items } } }
```

Add import at the top:

```kotlin
import androidx.compose.runtime.derivedStateOf
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt
git commit -m "perf(gallery): use derivedStateOf for allFlatMedia to reduce recomposition"
```

---

### Verification

After all tasks, do a full build and install:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/picme-debug.apk
```

Verify:
1. Open gallery with ~8000 photos — initial load should show thumbnails immediately
2. Fast scroll — no white/unloaded thumbnails
3. Switch grouping modes (date/face) — no regression
