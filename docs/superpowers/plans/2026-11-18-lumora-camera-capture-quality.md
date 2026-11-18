# LUMORA Camera Capture Quality Implementation Plan

**V2 verification record.** The steps below are the original implementation plan. Executed checks, outcomes, screenshots and remaining device checks are tracked in [the acceptance report](../../TEST_REPORT.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save camera-originated, device-supported full-resolution JPEGs and produce enhanced files without silently reducing resolution or risking loss of the source.

**Architecture:** Keep CameraX `ImageCapture` as the photo source, prefer quality capture and the largest supported output, and measure the actual saved file. Process the full-resolution decoded bitmap in bounded row chunks, preserve the source URI, and write a separate final JPEG with orientation/EXIF metadata. Record capture and processing facts through a small diagnostic value model.

**Tech Stack:** Kotlin, CameraX 1.6.2, Camera2 interop, Android MediaStore/EXIF, JUnit 4, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-18-lumora-camera-v2-design.md`

## Global Constraints

- Keep CameraX `ImageCapture` as the source of saved photographs.
- Prefer the quality capture mode and the largest supported still-output resolution available from the bound camera.
- Never intentionally downsample the final enhanced output.
- A processing error must leave the source photograph available.
- Avoid unnecessary JPEG encode/decode cycles and use one final encode for the enhanced image.
- Report missing capture metadata as unavailable, not inferred.

---

### Task 1: Define and test capture diagnostics

**Files:**
- Create: `app/src/main/java/com/lumora/camera/camera/CaptureQualityRecord.kt`
- Create: `app/src/test/java/com/lumora/camera/CaptureQualityRecordTest.kt`
- Modify: `app/src/main/java/com/lumora/camera/camera/CameraController.kt`

**Interfaces:**
- `CaptureQualityRecord` stores actual width, height, file bytes, MIME type, lens/camera identifier, mode, optional ISO/exposure/focal length/focus distance, and optional processing duration/output dimensions.
- `CaptureQualityRecord.toDiagnosticLine()` returns a stable, redaction-safe single-line summary; absent values use `unavailable`.

- [ ] **Step 1: Write tests for diagnostic formatting.** Test actual dimensions and bytes, optional metadata presence, and null metadata formatting without inventing default camera values.
- [ ] **Step 2: Run `.\gradlew.bat :app:testDebugUnitTest --tests com.lumora.camera.camera.CaptureQualityRecordTest`.** Expected: compilation fails because `CaptureQualityRecord` does not exist.
- [ ] **Step 3: Add the immutable diagnostic model and formatter.** Keep it independent of Android APIs so local JUnit can execute it.
- [ ] **Step 4: Re-run the focused test and full unit suite.** Expected: focused test and `:app:testDebugUnitTest` pass.

### Task 2: Request quality still capture and report actual output

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/camera/CameraController.kt`
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/CaptureOutputTest.kt`

**Interfaces:**
- Camera capture callback reports a saved URI plus `CaptureQualityRecord` created from the saved file's actual bounds, size, MIME type, and available EXIF metadata.

- [ ] **Step 1: Add a failing instrumentation assertion for saved output.** Capture one photo through the app and assert it is a JPEG with nonzero actual dimensions and bytes; verify no preview screenshot path is used.
- [ ] **Step 2: Run the focused instrumentation test on the API 35 emulator.** Expected: it fails on the current capture quality configuration or missing diagnostic callback contract.
- [ ] **Step 3: Change `ImageCapture` to quality-priority mode and choose the largest supported still resolution.** Use the CameraX-supported resolution API for the pinned 1.6.2 dependency; avoid a hardcoded pixel size that claims hardware support.
- [ ] **Step 4: Read actual output bounds/size and available EXIF fields after save.** Attach lens/mode information from the active controller; log the diagnostic line without image bytes or sensitive content.
- [ ] **Step 5: Re-run the focused instrumentation test.** Expected: CameraX output dimensions, file size, type, and diagnostic values agree with the actual MediaStore item.

### Task 3: Test full-resolution, bounded-row color processing

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/image/FilmColorEngine.kt`
- Create: `app/src/androidTest/java/com/lumora/camera/image/FilmColorEngineTest.kt`
- Modify: `app/src/main/java/com/lumora/camera/data/FilmPreset.kt`

- [ ] **Step 1: Write instrumented bitmap behavior tests.** Verify output dimensions match input, source pixels remain unchanged, and output pixels reflect a non-neutral preset.
- [ ] **Step 2: Run `.\gradlew.bat :app:connectedDebugAndroidTest --tests com.lumora.camera.image.FilmColorEngineTest`.** Expected: the test fails because processing currently copies a full bitmap and allocates a full-image `IntArray`.
- [ ] **Step 3: Add a tested in-place row/chunk processing entry point.** Process bounded scanlines using a reusable row buffer and preserve a separate decoded source file as the original; avoid a second full-image bitmap or full-image integer array.
- [ ] **Step 4: Keep the existing API delegating to the new tested path.** Preserve current preset parameter behavior for this task; do not add unrelated filters.
- [ ] **Step 5: Re-run bitmap instrumentation tests and existing unit tests.** Confirm exact dimensions and source immutability.

### Task 4: Remove silent downsampling and harden failure handling

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/image/PhotoEnhancementEngine.kt`
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/image/PhotoEnhancementEngineTest.kt`

- [ ] **Step 1: Add a large-dimension fixture test.** Use a generated instrumentation bitmap/file and assert successful enhanced output retains source width and height; add a failing-output case that confirms the original URI remains readable.
- [ ] **Step 2: Run the focused instrumentation test against current code.** Expected: the large-dimension assertion fails because `decodeScaled` caps the longest edge at 2400 pixels.
- [ ] **Step 3: Decode at source dimensions and use the tested bounded-row engine.** Do not cap output dimensions. Catch allocation/processing failures, delete only a partially written enhanced item, preserve the original, and return an explicit failure result.
- [ ] **Step 4: Copy orientation and relevant EXIF values after final JPEG output.** Avoid closing or recycling a bitmap owned by another operation; keep bitmap lifetime scoped to the processing worker.
- [ ] **Step 5: Re-run focused tests and inspect actual output bounds.** Verify enhanced image dimensions equal the source dimensions on success and failed enhancement leaves the source file intact.

### Task 5: Exercise capture and processing diagnostics end to end

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TEST_REPORT.md`

- [ ] **Step 1: Time the processing worker and attach output dimensions to diagnostics.** Keep processing off the main dispatcher and show a concise capture/processing failure state.
- [ ] **Step 2: Capture original and enhanced photos in the emulator.** Inspect MediaStore names, dimensions, sizes, MIME type, and copied orientation/EXIF fields.
- [ ] **Step 3: Exercise processing failure.** Use a controlled instrumentation failure before enhanced output is finalized; verify no source deletion and no partial visible enhanced item.
- [ ] **Step 4: Record the exact emulator checks and known physical-device gaps.** Do not call emulator dimensions evidence of physical sensor resolution.
- [ ] **Step 5: Run `.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug`.** Record command result and APK path.
