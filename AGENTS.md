# PicMe Project Overview

PicMe is a high-performance, modern Android camera application built with Jetpack Compose and CameraX. It features real-time face detection, advanced beauty filters, and a smart media gallery.

## Tech Stack
- **Language**: Kotlin 2.2.10
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Camera Engine**: CameraX (ImageCapture, VideoCapture, ImageAnalysis)
- **AI/ML**: Google ML Kit (Face Detection)
- **Database**: Room (KSP) for media metadata
- **Persistence**: DataStore (Preferences) for settings
- **Media Loading**: Coil 2.7.0 (with VideoFrameDecoder)
- **Video Playback**: Media3 ExoPlayer 1.5.1
- **Architecture**: MVVM with Repository Pattern

## Project Structure
- `com.example.picme.data`:
  - `local`: Room Database (`AppDatabase`), DAO (`MediaDao`).
  - `model`: Data entities (`MediaAsset`).
  - `repository`: Data abstractions (`MediaRepository`, `UserPreferencesRepository`).
- `com.example.picme.ui`:
  - `screens`: UI screens (`CameraScreen`, `GalleryScreen`, `SettingsScreen`).
  - `viewmodel`: ViewModels for state management.
  - `model`: UI-specific models (e.g., `FilterType`).
  - `navigation`: Compose Navigation logic.
  - `theme`: Material 3 Theme definitions.

## Key Features & Implementations
1. **Dual-Mode Camera**: Supports high-quality photo capture and video recording with real-time mode switching.
2. **AI-Driven Beauty System**:
   - **Face Detection**: Uses ML Kit to detect faces and landmarks in real-time.
   - **Smart Auto-Focus**: Automatically triggers `CameraControl` focus/metering on detected faces.
   - **Mesh Warp Beauty**: Implements `drawBitmapMesh` for high-precision "Slim Face" and "Big Eyes" effects based on face landmarks.
   - **Skin Smoothing**: Fast blur-based skin smoothing with adjustable intensity.
3. **Rotation & Mirroring Fix**: Automatically handles `rotationDegrees` from `ImageProxy` and provides mirroring for the front camera.
4. **Smart Gallery**:
   - Organized by date (descending).
   - Batch management (long-press to select, multi-select, delete).
   - Full-screen pager with ExoPlayer integration.
5. **Internationalization**: Full support for English and Simplified Chinese (runtime switching).

## Build & Run
- **Build**: `./gradlew assembleDebug`
- **Run**: Standard Android Studio Run configuration.
- **Minimum SDK**: 24
- **Target SDK**: 35
