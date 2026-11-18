# LUMORA Camera Photography Workflow Implementation Plan

**V2 verification record.** The steps below are the original implementation plan. Executed checks, outcomes, screenshots and remaining device checks are tracked in [the acceptance report](../../TEST_REPORT.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a coherent V2 photography workflow with independent film styles, non-destructive enhancement/editing, an adaptive camera/gallery/settings UI, and truthful V2.0 build and verification artifacts.

**Architecture:** Preserve the current MediaStore source/result flow and twelve stable preset IDs. Extend the preset and record models compatibly, use one parameter-driven color engine for camera preview and saved-image processing where supported, and persist edits as new outputs/records. Keep expensive image work off the main thread and keep thumbnails bounded to display dimensions.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX, Android MediaStore/Bitmap/EXIF, SharedPreferences JSON, JUnit 4, Android instrumentation, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-18-lumora-camera-v2-design.md`

## Global Constraints

- Preserve the twelve existing preset IDs and their current saved-selection compatibility.
- Preserve the captured original and store each processed result separately.
- Reprocessing creates a new result and never overwrites the source.
- Do not bundle generated or placeholder imagery and call it a sample.
- Keep filter controls and automatic enhancement on a background dispatcher.
- Update app version to `2.0.0`.

---

### Task 1: Expand and test the preset contract

**Files:**
- Modify: `app/src/main/assets/presets.json`
- Modify: `app/src/main/java/com/lumora/camera/data/FilmPreset.kt`
- Modify: `app/src/test/java/com/lumora/camera/PresetContractTest.java`
- Create: `app/src/androidTest/java/com/lumora/camera/PresetAssetTest.kt`

**Interfaces:**
- Preserve IDs: `portra`, `vivid_film`, `classic_chrome`, `vintage`, `cinema`, `japanese`, `soft_portrait`, `night_film`, `monochrome`, `warm_light`, `cool_tone`, `natural`.
- Add IDs for `rainy_mood`, `urban_night`, `forest_green`, `seaside_sunset`, `flash_street`, `low_contrast_portrait`, `high_contrast_monochrome`, and `warm_documentary`.
- `FilmPreset` remains immutable and stores only independent per-preset parameters used by preview and output engines.

- [ ] **Step 1: Replace the hardcoded count-only test with an asset contract test.** Assert 20 unique IDs, all twelve legacy IDs, all eight added IDs, finite numeric parameters, and at least one meaningful parameter difference for every added style.
- [ ] **Step 2: Run `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat :app:connectedDebugAndroidTest --tests com.lumora.camera.PresetAssetTest`.** Expected: the asset assertion fails because only twelve presets exist.
- [ ] **Step 3: Add eight distinct parameter profiles.** Base them on independent tonal/color/grain/vignette choices; do not duplicate a preset under a different name.
- [ ] **Step 4: Re-run preset tests and inspect every profile's preview/output parameter mapping.** Expected: all 20 profiles load and legacy IDs remain unchanged.

### Task 2: Make color processing preserve image dimensions and edit intent

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/image/FilmColorEngine.kt`
- Modify: `app/src/main/java/com/lumora/camera/image/PhotoEnhancementEngine.kt`
- Create: `app/src/main/java/com/lumora/camera/image/PhotoEditParameters.kt`
- Create: `app/src/test/java/com/lumora/camera/image/PhotoEditParametersTest.kt`
- Modify: `app/src/androidTest/java/com/lumora/camera/image/FilmColorEngineTest.kt`

- [ ] **Step 1: Test edit-parameter defaults and bounds.** Cover NATURAL, STANDARD, ENHANCED, exposure, contrast, highlights, shadows, temperature, tint, saturation, detail, sharpening, denoise, grain, and vignette values.
- [ ] **Step 2: Run focused tests and observe failure for the absent parameter model.** Expected: `PhotoEditParameters` test compilation fails.
- [ ] **Step 3: Implement explicit bounded edit parameters and apply them through the row-based engine.** Keep original input pixels unchanged and retain source dimensions.
- [ ] **Step 4: Add deterministic exposure/white-balance analysis and restrained highlight/shadow adjustment.** Do not claim reconstruction of clipped detail, face segmentation, or multi-frame noise reduction.
- [ ] **Step 5: Run unit/instrumentation tests on neutral and non-neutral settings.** Verify neutral parameters produce no unexpected color shift and strength levels scale processing predictably.

### Task 3: Preview real photographs and add non-destructive editing

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Modify: `app/src/main/java/com/lumora/camera/data/PhotoRepository.kt`
- Modify: `app/src/main/java/com/lumora/camera/data/FilmPreset.kt`
- Create: `app/src/main/java/com/lumora/camera/ui/PhotoEditorScreen.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/PhotoRepositoryMigrationTest.kt`

- [ ] **Step 1: Add repository migration tests with an isolated test preference namespace.** Load a legacy photo record without favorite/edit fields and verify it remains readable with safe defaults.
- [ ] **Step 2: Run the migration test and confirm it fails for the missing new model fields.** Do not clear the app's real preference namespace during tests.
- [ ] **Step 3: Add favorite and edit/output metadata compatibly.** Reprocessing writes a new MediaStore image and appends a linked record; deletion/undo never removes the original as a side effect.
- [ ] **Step 4: Implement before/after editor controls and background processing states.** Keep full-resolution processing on `Dispatchers.IO`; decode gallery thumbnails only to their display target size.
- [ ] **Step 5: Build real style preview from a selected gallery image or actual recent capture.** Render selected style previews from that photo; show preview unavailable until a real photo exists. Never use the generated swatch color as a photographic sample.
- [ ] **Step 6: Re-run migration and editor tests.** Verify selected preset, strength, favorite, undo/reprocess, original/result comparison, and photo URI relationships.

### Task 4: Complete adaptive camera, gallery, and settings presentation

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Create: `app/src/main/java/com/lumora/camera/ui/CameraScreen.kt`
- Create: `app/src/main/java/com/lumora/camera/ui/GalleryScreen.kt`
- Create: `app/src/main/java/com/lumora/camera/ui/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/CameraSafeAreaTest.kt`

- [ ] **Step 1: Extract camera, gallery, and settings composables without changing routes or persisted behavior.** Move one screen at a time and run existing unit/build checks after each extraction.
- [ ] **Step 2: Apply the approved visual system to camera controls.** Keep safe top actions, real mode/extension indicators, aspect ratio/timer where implemented, grid/tools, styles, capture, gallery, and lens selection accessible without crowding.
- [ ] **Step 3: Finish gallery/editor/settings hierarchy.** Show capture time/style/source-result distinction and organize camera, capture, image quality, storage, device support, and about settings.
- [ ] **Step 4: Run portrait/landscape insets instrumentation and emulator screenshots.** Inspect touch bounds and clipping at compact/short and wide screen profiles available in the installed emulator.

### Task 5: Update V2.0 release metadata and evidence

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TEST_REPORT.md`
- Create: `docs/DEVICE_COMPATIBILITY_REPORT.md`
- Create: `docs/CAPTURE_QUALITY_REPORT.md`
- Create: `docs/PERFORMANCE_REPORT.md`
- Create: `docs/screenshots/v2-gallery.png`
- Create: `docs/screenshots/v2-settings.png`

- [ ] **Step 1: Add tests for release version contract and report labels.** Assert app version metadata is `2.0.0` and reports separate emulator, physical-device, and unverified claims.
- [ ] **Step 2: Update `versionCode` and `versionName`.** Set `versionName` to `2.0.0`; increment `versionCode` from the existing value.
- [ ] **Step 3: Update README, changelog, architecture, and reports from observed implementation only.** State which requested modes remain unsupported and which hardware checks require a real phone.
- [ ] **Step 4: Run `.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease`.** Record each exit result; release output remains unsigned unless a signing configuration already exists.
- [ ] **Step 5: Install the Debug APK on the available API 35 emulator.** Capture in-app screenshots and one test photo; report emulator evidence separately from physical-camera quality and endurance.
