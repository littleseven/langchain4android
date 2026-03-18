# Project Plan

Build PicMe, a camera and gallery app with photo/video capture and date-organized album.

## Project Brief

App Name: PicMe
A modern, high-performance camera application.
Features:
- Dual-Mode Camera (Photo/Video capture).
- Real-time Camera Filters (B&W, Sepia, Vintage, Cool, Warm).
- **Face Detection & Auto-Focus**: Automatically detects faces and focuses for sharp portraits.
- **Capture Feedback**: Standard shutter sound effect on photo capture.
- Front/Back camera switching.
- Support for landscape and portrait orientations.
- Smart Media Album (Organized by date) with Horizontal Pager for full-screen viewing.
- Batch Media Management (Multi-select, Select All, and Batch Delete).
- Material Design 3 (M3) with support for Dark/Light mode and Dynamic Color.
- Multi-language support (English & Simplified Chinese) with runtime switching.
- Smooth Navigation Transitions (Fade & Slide).
- Tech-style Flat Adaptive Icon (Sun & Moon combination).

Technical Stack:
- Kotlin & Jetpack Compose (Material Design 3)
- CameraX (Image Capture, Video Capture, and Image Analysis)
- **Google ML Kit** (Face Detection)
- Navigation Compose with Animated Transitions
- Room Database (KSP) for metadata persistence
- DataStore for user preferences (Theme & Language)
- Coil (with VideoFrameDecoder) for media loading
- ExoPlayer (Media3) for high-performance video playback
- MVVM Architecture

## Implementation Steps

### Task_1_Foundation_Data: Set up the project foundation, data layer, and navigation.
- **Status:** COMPLETED
- **Updates:** 
    *   Established Material Design 3 theme with "Energetic" palette.
    *   Implemented Room database for `MediaAsset` persistence.
    *   Set up `MediaRepository` and `MediaViewModel`.
    *   Established basic navigation and `PicMeApplication`.

### Task_2_Camera_Feature: Implement the dual-mode camera screen with smart features.
- **Status:** COMPLETED
- **Updates:** 
    *   Implemented dual-mode camera (Photo/Video) using CameraX.
    *   **Real-time Filters**: Added 6 filter presets (Original, B&W, Sepia, Vintage, Cool, Warm) using ColorMatrix.
    *   **Face Detection**: Integrated ML Kit to automatically detect faces and trigger auto-focus via `CameraControl`.
    *   **Audio Feedback**: Added `MediaActionSound` for professional shutter click feedback.
    *   **Photo Processing**: Filters are applied to captured photos before saving to the MediaStore.

### Task_3_Gallery_Feature: Implement the gallery and media management.
- **Status:** COMPLETED
- **Updates:**
    *   Implemented grid layout for media items with Coil thumbnail loading.
    *   **Horizontal Pager**: Added full-screen viewing with swipe-to-navigate functionality.
    *   **ExoPlayer Integration**: Seamless video playback in full-screen mode.
    *   **Batch Management**: Implemented long-press to enter selection mode, multi-select, "Select All", and batch deletion.

### Task_4_Polish_Verify: Finalize UI/UX, preferences, and internationalization.
- **Status:** COMPLETED
- **Updates:**
    *   **Internationalization**: Added full support for English and Simplified Chinese.
    *   **Settings & Preferences**: Added a settings screen with Theme (Light/Dark/System) and Language selection using DataStore.
    *   **Visual Polish**: Implemented smooth fade and slide transitions between screens.
    *   **App Icon**: Designed and implemented a flat, tech-style adaptive icon featuring a sun/moon combination.
    *   Fixed experimental API warnings and verified build stability.
