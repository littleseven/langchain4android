# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PicMe is a technology research project exploring two main tracks: **(1) AI Coding paradigm** — on-device Agent mechanisms and Agent-centric application architecture, and **(2) Audio/Video technology** — self-developed real-time beauty/filter/makeup engine ("BIG_BEAUTY") via OpenGL ES + EGL. The camera app serves as a concrete case study at the intersection of these two tracks. This project does not pursue commercialization; its core value lies in technical exploration and engineering practice.

Key technological decisions:
- **On-device Agent**: `domain/agent/` implements an Agent Runtime that maps natural language to device capabilities via Qwen3-1.7B running on MNN-LLM.
- **100% On-device**: All AI processing (LLM inference, face detection, OCR) runs locally — no cloud dependency.
- **Self-developed Engine**: Full OpenGL ES + EGL pipeline (no third-party beauty SDKs); GPUPixel has been completely removed.

## Common Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run JVM unit tests (no device required)
./gradlew test
# Or module-specific:
./gradlew :app:testDebugUnitTest
./gradlew :beauty-engine:testDebugUnitTest

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Code quality
./gradlew lint
./gradlew ktlintCheck
./gradlew detekt

# Clean build
./gradlew clean

# Install to device
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# View PicMe logs
adb logcat -s "PicMe:*"

# Full dev verification loop (compile → install → launch → screenshot → logs)
./scripts/auto-dev-loop.sh
```

## High-Level Architecture

### Module Structure

Two Gradle modules defined in `settings.gradle.kts`:
- **`:app`** — Main Android application (Camera, Gallery, Editor, Settings)
- **`:beauty-engine`** — Independent Android library; self-developed OpenGL ES + EGL real-time beauty engine

GPUPixel has been fully removed; all GPU capabilities are provided by the self-developed engine.

### Clean Architecture (App Module)

```
features/  →  domain/usecase/  →  domain/repository/  →  data/
   ↓                ↓
domain/agent/  beauty-engine:api/  (strict boundary — see below)
```

- **Features**: Compose UI + ViewModels. Camera features include an Agent interaction panel for natural language control.
- **Domain**: Pure Kotlin, no Android dependencies. Includes `domain/agent/` for Agent Runtime (`AgentOrchestrator`, `LocalLlmEngine`, `CapabilityRegistry`, `MemoryManager`, `PrivacyGuard`) and `domain/usecase/AiAgentUseCase` as Facade.
- **Data**: Repository implementations, Room DB, DataStore preferences, and LLM model download management (`LlmModelDownloadManager`).

### Beauty-Engine Layered Architecture (Critical Dependency Boundary)

```
App Layer
    ↓ (only dependency allowed)
beauty-engine:api/          ← Public API contract (BeautySettings, BeautyParams,
                               FilterType, Face, BeautyProcessor, PhotoProcessor,
                               FaceDetector, BeautyPreviewProvider, etc.)
    ↑
beauty-engine:egl/          ← Internal OpenGL ES + EGL pipeline (BeautyRenderer,
                               CameraPreviewRenderer, PhotoProcessorImpl, EGLCore)
    ↑
beauty-engine:internal/     ← Face detection adapters, frame-sync system
```

**Dependency rules**:
- App code **must only** depend on `beauty-engine:api/` classes. Direct references to `egl/` or `internal/` are forbidden.
- `api/` package **must not** depend on `egl/`, `androidx.camera.*`, or any App-layer packages.
- `egl/` implements `api/` interfaces and may depend on Android/OpenGL ES libraries.
- All GPU/EGL operations are encapsulated inside `beauty-engine:egl`.

### Face Detection Architecture

Dual-engine detection unified to 106 landmarks:
- **InsightFace 2D106** (default): Local ONNX Runtime inference with NNAPI GPU/NPU acceleration. Two-stage pipeline: Det10G ROI → 2D106 landmarks.
- **MediaPipe Face Mesh 468→106** (fallback): Async analysis stream with precise 468→106 semantic mapping.
- Auto mode: prefers InsightFace; falls back to MediaPipe on miss or init failure.

All detection implementations live in `beauty-engine/internal/facedetect/`. App layer consumes only `beauty-engine:api/facedetect/` contracts.

### Frame-Sync Makeup System

Solves makeup "flying off" caused by face detection (~10 fps) and rendering (30–60 fps) running at different rates.

- Components in `beauty-engine/internal/framesync/`: `FrameSyncBridge`, `FrameSyncManager`, `MotionTracker`, `DetectionQueue`, `FaceDetectionWorker`.
- Rendering thread queries `FrameSyncManager` by `FrameId` to get time-aligned face data (exact match → history fallback → prediction compensation → hide).
- **Recording must reuse the same `FrameSyncManager` instance** as preview to ensure consistent behavior.

### Agent Runtime (On-Device)

```
User Input ("拍张照" / "换个冷调滤镜")
    → AiAgentUseCase (Facade)
    → AgentOrchestrator.processUserInput()
    → LocalLlmEngine (Qwen3-1.7B via MNN-LLM, 100% on-device)
    → AgentCommandParser (LLM response → AgentCommand)
    → CapabilityRegistry (route to Capability)
    → CameraCapability (execute device operation)
```

- **Model**: Qwen3-1.7B-MNN downloaded from ModelScope via `LlmModelDownloadManager` (resumable + SHA256).
- **Capabilities**: `AdjustBeauty` (smooth/whiten/slim/eye/lip/blush/brow), `SwitchFilter`, `SwitchStyle`, `SwitchScene`, `SwitchRatio`, `AdjustExposure`, `AdjustZoom`, `FlipCamera`, `CapturePhoto`, `TextReply`.
- **Privacy**: `PrivacyGuard` grades operations; all LLM inference runs 100% locally.
- **Memory**: `MemoryManager` maintains conversation context for multi-turn dialogue.

### Zero-Copy GPU Pipeline (Preview)

```
CameraX → SurfaceTexture → OpenGL ES Shader → SurfaceView
```

- Preview path is fully GPU-based; `glReadPixels` back to CPU is prohibited in preview.
- Photo processing uses the same shader pipeline via off-screen FBO rendering (`PhotoProcessorImpl`) to guarantee preview/photo consistency. CPU fallback exists at `core/image/`.

## Code Style & Constraints

### Hard Rules (Enforced)
- **No fully-qualified names** for `com.picme.*` in source (custom Gradle task `checkNoFullyQualifiedName`); use imports.
- **No wildcard imports** (`*`).
- **Lambda parameters must be explicitly named**; implicit `it` is prohibited.
- **Log tags** must follow `PicMe:[ModuleName]` (e.g., `PicMe:Camera`, `PicMe:BeautyEngine`).
- **Indentation**: Kotlin/Java 4 spaces; XML/JSON/MD 2 spaces.

### I18N (Mandatory)
- **Never hardcode user-facing strings** in UI code.
- When adding or refactoring features, **must sync all supported languages**: `values/strings.xml` (EN/default), `values-zh-rCN/strings.xml` (Simplified Chinese), `values-zh-rTW/strings.xml` (Traditional Chinese).

### Global Red Lines
- **`[PRIVACY]`**: All AI processing (face, OCR, classification) must be 100% on-device. Cloud inference is strictly prohibited.
- **`[PERF]`**: Interaction feedback < 100 ms; shutter capture latency < 50 ms.
- **`[I18N]`**: All user-visible text must be extracted and synchronized across the three language sets above.

## Quality Toolchain

- **ktlint** (v1.3.1) — Kotlin code style
- **detekt** (v1.23.6, config: `detekt-config.yml`) — Static analysis
- **Unit tests** — Pure JVM tests covering coordinate algorithms, state machines, converters, end-to-end flows. ~50 test files across `app/src/test/` and `beauty-engine/src/test/`.
- **Instrumentation tests** — Require connected device/emulator.

## Documentation Hierarchy

The project follows a three-layer documentation system. When implementation reveals spec gaps, code and docs must be updated in the **same atomic commit**.

```
PRODUCT.md          → Goals and constraints (What)
docs/01-PRODUCT/FEATURES.md    → Interaction and UX rules (How)
<module>/AGENTS.md  → Implementation specs and checklists
```

Key technical specs:
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — Rendering pipeline, fallback, cooldown recovery, observability
- `docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md` — Coordinate conversion, viewport calculation
- `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` — InsightFace + MediaPipe dual-engine architecture
- `docs/02-ARCHITECTURE/ADR/ADR-001-beauty-engine-architecture.md` — Layered module architecture decision
- `docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md` — GPU off-screen rendering for photo processing

## Build Configuration

- **compileSdk**: 36, **minSdk**: 24, **targetSdk**: 35
- **Java/Kotlin target**: 11
- **Dependency management**: Version Catalog (`gradle/libs.versions.toml`)
- **Plugins**: Android Application/Library, Kotlin Android + Compose, KSP, ktlint, detekt

## Useful Scripts

Located in `scripts/`:
- `auto-dev-loop.sh` — Full verification loop (compile, install, launch, screenshot, log collection)
- `ai-gate.sh` — Quality gate (lint + compile + install check)
- `regression-test.sh` — P0 end-to-end regression
- `quick-compile.sh` — Layered fast compile (syntax → compile → dex → APK, stop on first failure)
- `impact-analyzer.sh` — Change impact analysis (affected modules, red lines, doc sync needs)
- `screenshot-diff.py` — Pixel-level screenshot comparison for UI regression
- `smart-commit.sh` — Auto-generate Conventional Commits based on changes
