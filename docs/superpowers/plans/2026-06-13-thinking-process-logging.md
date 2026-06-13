# Thinking-Process Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the LLM thinking process visible in logs by adding a dedicated `LogModule.CHAT`, logging raw LLM responses before think-tag stripping, and exposing the toggle in Settings.

**Architecture:** Add a new `CHAT` log module so chat-related tags can be switched on/off. Log the raw response at the entry points where think tags are removed: `ChatViewModel` before cleaning the final streamed response, and `AgentCommandParser` at the top of `cleanLlmResponse`. The existing Settings log-management UI will pick up the new module automatically because it iterates `LogModule.entries`.

**Tech Stack:** Kotlin, PicMe Logger, Room (no schema change), Jetpack Compose Settings.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `app/src/main/java/com/mamba/picme/domain/model/LogModuleConfig.kt` | Add `LogModule.CHAT`; enable it by default. |
| `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` | Log raw local LLM response before `cleanThinkTags`. |
| `agent-core/src/main/java/com/mamba/picme/agent/core/runtime/parsing/AgentCommandParser.kt` | Log raw LLM response at the start of `cleanLlmResponse`. |
| `app/src/test/java/com/mamba/picme/domain/model/LogModuleConfigTest.kt` | Unit test for new module mapping and default config. |

---

## Assumptions

- The existing `Logger` already routes to logcat and the in-app `LogOverlay`; no new log sink is required.
- Streaming token-level logs are intentionally limited to the final raw response to avoid log flooding. Per-token thinking can still be inferred from the final raw log.
- `RemoteOrchestrator` already logs raw remote content before cleaning, so only local/command paths need new logging.

---

### Task 1: Add `LogModule.CHAT`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/model/LogModuleConfig.kt`

- [ ] **Step 1.1: Add the CHAT enum entry**

In the `LogModule` enum, add a new entry after `ORCHESTRATOR`:

```kotlin
    CHAT(
        listOf("ChatViewModel", "ChatScreen", "ChatThreadSidebar", "AgentCommandParser"),
        "Chat"
    );
```

The file should now look like:

```kotlin
enum class LogModule(val tagPrefixes: List<String>, val displayName: String) {
    FACE_DETECTION(
        listOf("FaceDetector", "MediaPipe", "Mnn", "Ncnn", "LandmarkAdapter"),
        "Face Detection"
    ),
    RENDERING(
        listOf("BeautyRenderer", "CameraPreview", "EGLCore", "FaceMakeupPass", "BeautyPass"),
        "Rendering"
    ),
    BEAUTY(
        listOf("ImageProc", "BeautyPreview", "BeautyRecorder", "Framebuffer", "FrameSync", "ModelManager"),
        "Beauty"
    ),
    AGENT(listOf("Agent"), "Agent"),
    CAMERA(listOf("Camera"), "Camera"),
    DOWNLOAD(listOf("Download"), "Download"),
    SETTINGS(listOf("Settings"), "Settings"),
    ORCHESTRATOR(listOf("Orchestrator"), "Orchestrator"),
    CHAT(
        listOf("ChatViewModel", "ChatScreen", "ChatThreadSidebar", "AgentCommandParser"),
        "Chat"
    );

    companion object { /* ... */ }
}
```

- [ ] **Step 1.2: Enable CHAT by default**

In `LogModuleConfig.default()`:

```kotlin
        fun default(): LogModuleConfig = LogModuleConfig(
            enabledModules = setOf(
                LogModule.AGENT,
                LogModule.ORCHESTRATOR,
                LogModule.DOWNLOAD,
                LogModule.SETTINGS,
                LogModule.CHAT
            )
        )
```

- [ ] **Step 1.3: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 2: Log raw local response in `ChatViewModel`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 2.1: Log the final raw response before cleaning**

In `generateLocalResponse`, locate the block after `collect` finishes:

```kotlin
            val cleanedResponse = cleanThinkTags(rawResponse)
            _streamingMessage.value = null
            insertAgentMessage(sessionId, cleanedResponse, modelLabel, performance)
```

Change it to:

```kotlin
            Logger.i(TAG, "Raw local response before cleaning: ${rawResponse.replace("\n", "\\n")}")

            val cleanedResponse = cleanThinkTags(rawResponse)
            _streamingMessage.value = null
            insertAgentMessage(sessionId, cleanedResponse, modelLabel, performance)
```

This logs the complete raw text (including any `<think>...</think>` block) before `cleanThinkTags` removes it.

- [ ] **Step 2.2: Optional — log when a streaming token contains a think marker**

If you want to see thinking as it streams, add a guarded DEBUG log inside the `StreamEvent.Token` branch:

```kotlin
                    is StreamEvent.Token -> {
                        rawResponse = event.accumulatedText
                        if (rawResponse.contains("<think>") || rawResponse.contains("思考")) {
                            Logger.d(TAG, "Streaming token contains think marker")
                        }
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = cleanThinkTags(rawResponse)
                        )
                    }
```

This is optional because the final INFO log in Step 2.1 already captures the full thinking block.

- [ ] **Step 2.3: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 3: Log raw response in `AgentCommandParser`

**Files:**
- Modify: `agent-core/src/main/java/com/mamba/picme/agent/core/runtime/parsing/AgentCommandParser.kt`

- [ ] **Step 3.1: Add raw-response log at the top of `cleanLlmResponse`**

At the very beginning of `cleanLlmResponse` (line 152), add:

```kotlin
    private fun cleanLlmResponse(response: String): String {
        Logger.i(TAG, "Raw LLM response before cleaning: '${response.replace("\n", "\\n")}'")
        var cleaned = response.trim()
        // ... rest of the function
```

This ensures every command-path response is visible in logs before think tags and JSON repair logic run.

- [ ] **Step 3.2: Compile**

Run: `./gradlew :agent-core:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 4: Verify Settings UI picks up the new module

**Files:**
- No changes required, but verify:
  - `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt` `LogModuleConfigSection`

- [ ] **Step 4.1: Confirm automatic discovery**

`LogModuleConfigSection` already uses:

```kotlin
CompactMultiSelectChips(
    options = LogModule.entries.map { it to it.displayName },
    // ...
)
```

Because `LogModule.CHAT` is added to the enum, it will automatically appear as a toggle in Settings → Log Management.

- [ ] **Step 4.2: Manual check**

After installing the app:
1. Go to Settings → Log Management.
2. Confirm a "Chat" chip is present and enabled by default.
3. Disable it.
4. Send a chat message and confirm no `PicMe:ChatViewModel` INFO logs appear in logcat.

---

### Task 5: Add unit tests for the new log module

**Files:**
- Create: `app/src/test/java/com/mamba/picme/domain/model/LogModuleConfigTest.kt`

- [ ] **Step 5.1: Write tests**

```kotlin
package com.mamba.picme.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogModuleConfigTest {

    @Test
    fun chatTagResolvedToChatModule() {
        assertEquals(LogModule.CHAT, LogModule.fromTag("ChatViewModel"))
        assertEquals(LogModule.CHAT, LogModule.fromTag("ChatScreen"))
        assertEquals(LogModule.CHAT, LogModule.fromTag("AgentCommandParser"))
    }

    @Test
    fun defaultConfigEnablesChat() {
        val config = LogModuleConfig.default()
        assertTrue(config.isEnabled(LogModule.CHAT))
        assertTrue(config.isTagEnabled("ChatViewModel"))
    }

    @Test
    fun toggleDisablesChatTag() {
        val config = LogModuleConfig.default()
            .toggle(LogModule.CHAT, false)
        assertTrue(!config.isTagEnabled("ChatViewModel"))
    }
}
```

- [ ] **Step 5.2: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.LogModuleConfigTest" --no-daemon`
Expected: 4 tests PASS.

---

### Task 6: Final compile and manual verification

- [ ] **Step 6.1: Full compile**

Run: `./gradlew :agent-core:compileDebugKotlin :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6.2: Manual verification**

1. Install debug APK.
2. Open the debug log overlay or run `adb logcat -s PicMe:ChatViewModel:D PicMe:AgentCommandParser:D`.
3. Send a chat message using the local model.
4. Confirm you see:
   - `PicMe:ChatViewModel: Raw local response before cleaning: ...<think>...` (if the model still emits thinking tags)
   - The final UI message does **not** contain `<think>` tags.
5. Trigger an agent command (e.g., in camera say "拍照").
6. Confirm `PicMe:AgentCommandParser: Raw LLM response before cleaning: ...` appears.
7. Go to Settings → Log Management, disable Chat, and confirm the logs stop.

---

## Self-Review

| Requirement | Task |
|-------------|------|
| See thinking in logs | Task 2 + Task 3 |
| Dedicated log module for chat | Task 1 |
| Settings toggle | Task 1.2 + Task 4 (automatic) |
| Unit tests | Task 5 |
| No placeholders | All steps contain exact code and commands |

No placeholders found. Type consistency: `LogModule.CHAT` is used in enum, default config, and tests.
