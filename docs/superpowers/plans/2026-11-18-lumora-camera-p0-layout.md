# LUMORA Camera P0 Layout Implementation Plan

**V2 verification record.** The steps below are the original implementation plan. Executed checks, outcomes, screenshots and remaining device checks are tracked in [the acceptance report](../../TEST_REPORT.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep every interactive control inside the correct system-safe area while allowing the camera preview to use the full display in portrait and landscape.

**Architecture:** Configure edge-to-edge and system-bar appearance once in `MainActivity`; let ordinary screens consume safe drawing insets and let the camera preview extend behind bars while its controls consume safe drawing and gesture insets once. Remove the manifest portrait lock and make camera control layout responsive to available height and width.

**Tech Stack:** Kotlin, AndroidX Activity/Core, Jetpack Compose, Compose UI instrumentation tests, CameraX 1.6.2.

**Spec:** `docs/superpowers/specs/2026-11-18-lumora-camera-v2-design.md`

## Global Constraints

- Ordinary pages place controls and scroll content inside the Compose safe drawing area.
- The camera preview may draw behind system bars, while its top and bottom controls use the applicable safe drawing/gesture insets exactly once.
- Use inset-aware layout rather than fixed status-bar heights.
- Remove the portrait-only manifest restriction.
- Camera controls remain inside safe drawing and gesture areas in portrait and landscape.

---

### Task 1: Add inset regression-test support

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/com/lumora/camera/CameraSafeAreaTest.kt`
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`

**Interfaces:**
- Tests use the existing accessibility descriptions `设置` and `拍照` to locate the top and bottom camera controls.
- Tests compare Compose node bounds in pixels with `WindowInsetsCompat` status-bar and navigation-bar insets from the same activity window.

- [x] **Step 1: Add Compose instrumentation dependencies and test.** Added Compose UI test dependencies using the existing Compose BOM and `CameraSafeAreaTest`, which grants camera permission, waits for the camera screen, reads settings and shutter bounds, and checks top and bottom system inset clearance.
- [x] **Step 2: Run the instrumentation test to establish the current behavior.** The original status/navigation-only checks passed, while the expanded check for display-cutout and system-gesture insets exposed top and landscape-side overlaps on the API 35 emulator; the manifest assertion also confirmed `screenOrientation=1`. The screen must use measured safe insets, with no fixed-height workaround.
- [x] **Step 3: Add any missing test semantics without changing layout.** Existing accessible labels locate the settings and shutter controls, so no extra test tags were needed.
- [x] **Step 4: Re-run and record the baseline assertions.** Confirmed failures are the manifest orientation lock and missing cutout/gesture clearance, not permission, startup, or test discovery; the connected-test output records top safe inset 60.95dp and landscape left safe inset 48.76dp against control positions 43dp.

### Task 2: Centralize window and screen insets

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Modify: `app/src/main/res/values/styles.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/com/lumora/camera/CameraSafeAreaTest.kt`

**Interfaces:**
- `MainActivity.onCreate` owns the single window edge-to-edge and system-bar icon policy.
- Ordinary `Scaffold` pages use Compose's system insets; camera preview remains full-bleed and only its control layer receives safe drawing/gesture padding.

- [ ] **Step 1: Set the edge-to-edge window policy in the activity.** Use AndroidX `enableEdgeToEdge` and one dark-surface system-bar icon policy that remains legible over the camera preview; do not add a second per-screen window policy.
- [ ] **Step 2: Remove fixed portrait orientation and normalize theme bar colors.** Remove `android:screenOrientation="portrait"`; keep a contrast-safe bar appearance for non-camera screens and configuration changes.
- [ ] **Step 3: Apply safe drawing/gesture insets once.** Keep preview and grid edge-to-edge, apply `WindowInsets.safeContent` once to the camera control layer, and audit ordinary `Scaffold` pages for duplicate inset padding.
- [ ] **Step 4: Run the instrumentation regression test.** Run `.\gradlew.bat :app:connectedDebugAndroidTest --tests com.lumora.camera.CameraSafeAreaTest`; expected: both bounds assertions pass.

### Task 3: Adapt camera controls to short and wide layouts

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/CameraSafeAreaTest.kt`

- [ ] **Step 1: Capture a landscape/short-height baseline.** With the API 35 emulator, rotate the camera screen and record current control overlap or clipping.
- [ ] **Step 2: Add responsive camera control arrangement.** Use available window size classes or `BoxWithConstraints`; reduce secondary film-strip/PRO-panel height on short layouts while keeping shutter and lens/gallery controls visible.
- [ ] **Step 3: Extend instrumentation assertions to both orientations.** Rotate the activity; assert settings and shutter nodes exist and remain within root bounds adjusted for current system insets.
- [ ] **Step 4: Run unit and instrumentation checks.** Run `.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest`; confirm the camera remains bound after rotation.

### Task 4: Verify system UI variants and document evidence

**Files:**
- Modify: `docs/TEST_REPORT.md`
- Create: `docs/screenshots/v2-camera-portrait.png`
- Create: `docs/screenshots/v2-camera-landscape.png`

- [ ] **Step 1: Run portrait and landscape with gesture navigation.** Capture emulator screenshots after controls settle; verify the rendered touch target and visible icon/text occupy the same area.
- [ ] **Step 2: Exercise three-button navigation and simulated cutout profiles.** Use emulator settings/device profiles that are installed; record unavailable profile coverage rather than substituting a physical-device claim.
- [ ] **Step 3: Record layout results in the verification report.** Include API/image, orientation, navigation mode, cutout configuration, and screenshot paths.
- [ ] **Step 4: Run `.\gradlew.bat :app:assembleDebug`.** Confirm the layout build remains installable before moving to the capture-quality plan.
