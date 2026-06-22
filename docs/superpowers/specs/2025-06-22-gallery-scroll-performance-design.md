# Gallery Scroll Performance Optimization

**Date**: 2025-06-22
**Status**: Approved
**Scope**: Gallery list page scroll experience with ~8000 photos

## Problem

Gallery list page shows white screens during scrolling with ~8000 photos:
1. Coil `AsyncImage` decodes full-size images then scales down for thumbnails
2. `ThumbnailPrefetcher` exists in DI but is not connected to `MediaGrid`
3. Coil memory cache at 25% heap — suboptimal for mid-high-end devices
4. `allFlatMedia` uses `remember()` instead of `derivedStateOf()`

## Design

### 1. AsyncImage size constraint (`MediaGrid.kt`)

Add `.size(360)` to `ImageRequest.Builder` so Coil decodes directly to thumbnail dimensions (~360px) instead of full resolution.

### 2. Connect ThumbnailPrefetcher (`MediaGrid.kt` + `GalleryScreen.kt`)

- `MediaGrid` accepts `thumbnailPrefetcher: ThumbnailPrefetcher?` parameter
- Uses `rememberLazyGridState()` for scroll position tracking
- `LaunchedEffect` on `firstVisibleItemIndex` triggers `prefetchWindow()` for visible + 3 pages before/after
- `GalleryScreen` passes `thumbnailPrefetcher` from DI to `MediaGrid`

### 3. Coil memory cache tuning (`CoilConfig.kt`)

- `maxSizePercent`: 0.25 → 0.35
- Enable `strongReferencesEnabled(true)` to avoid redundant decoding

### 4. allFlatMedia optimization (`GalleryScreen.kt`)

Replace `remember(groupedMedia) { flatMap }` with `derivedStateOf` to avoid unnecessary recomposition when `groupedMedia` reference is unchanged.

## Files Changed

| File | Change |
|------|--------|
| `app/.../features/gallery/components/MediaGrid.kt` | size constraint + prefetcher integration |
| `app/.../features/gallery/GalleryScreen.kt` | pass prefetcher + derivedStateOf |
| `app/.../core/image/CoilConfig.kt` | cache tuning |

## Risk

Low — changes are additive and scoped to the image loading layer. No data layer or architecture changes.
