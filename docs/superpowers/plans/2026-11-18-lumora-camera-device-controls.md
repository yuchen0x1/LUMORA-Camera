# LUMORA Camera Device Controls Implementation Plan

**V2 verification record.** The steps below are the original implementation plan. Executed checks, outcomes, screenshots and remaining device checks are tracked in [the acceptance report](../../TEST_REPORT.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make camera controls and shooting modes correspond to the selected camera's reported capabilities and real capture behavior.

**Architecture:** Introduce a capability snapshot model populated from the active CameraX camera and Camera2 characteristics. Keep capability discovery separate from UI, refresh it on lens switches, and route only supported values into camera controls. Represent software assistance separately from hardware modes so unsupported RAW, night, portrait, stabilization, and long exposure are never presented as working hardware features.

**Tech Stack:** Kotlin, CameraX 1.6.2, Camera2 interop, Jetpack Compose, JUnit 4, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-18-lumora-camera-v2-design.md`

## Global Constraints

- Re-query capabilities when the selected lens changes.
- Controls are enabled only when the current camera reports support and the capture path applies the setting.
- ISO and shutter controls must use the reported range.
- Do not label single-frame processing as multi-frame night mode, portrait depth, optical stabilization, RAW/DNG, or long exposure.
- Unsupported capabilities are hidden or disabled with a concise reason.

---

### Task 1: Create a capability snapshot contract

**Files:**
- Create: `app/src/main/java/com/lumora/camera/camera/DeviceCapabilities.kt`
- Create: `app/src/test/java/com/lumora/camera/camera/DeviceCapabilitiesTest.kt`

**Interfaces:**
- `DeviceCapabilities` contains camera ID/facing, focal lengths, JPEG sizes, flash/AF flags, zoom bounds, exposure-compensation range/step, ISO range, exposure-time range, minimum focus distance, RAW support, stabilization data when available, and CameraX extension support.
- Ranges are nullable when unknown; `supportsManualExposure` is true only when both ISO and exposure-time ranges are present and valid.

- [ ] **Step 1: Write pure model tests.** Assert valid and missing ranges, minimum/maximum boundaries, and unavailable capability formatting.
- [ ] **Step 2: Run `.\gradlew.bat :app:testDebugUnitTest --tests com.lumora.camera.camera.DeviceCapabilitiesTest`.** Expected: compile failure because the snapshot model is absent.
- [ ] **Step 3: Add immutable capability/range models and derived support predicates.** Keep all model tests free of CameraX/Android runtime dependencies.
- [ ] **Step 4: Re-run the focused test and all local unit tests.** Expected: both pass.

### Task 2: Populate capabilities from the active camera

**Files:**
- Create: `app/src/main/java/com/lumora/camera/camera/DeviceCapabilityManager.kt`
- Modify: `app/src/main/java/com/lumora/camera/camera/CameraController.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/camera/DeviceCapabilityManagerTest.kt`

- [ ] **Step 1: Add an instrumentation contract test for the active camera.** Assert a camera ID/facing is returned, required ranges are either valid or explicitly absent, and no unavailable feature is represented by a fabricated default.
- [ ] **Step 2: Run the focused instrumentation test before wiring the manager.** Expected: it fails because no capability manager is exposed.
- [ ] **Step 3: Query CameraX `CameraInfo` and supported Camera2 characteristics.** Collect only values supported by the pinned APIs; catch camera-specific characteristic failures and keep those fields unavailable.
- [ ] **Step 4: Query CameraX extension availability asynchronously.** Return HDR/NIGHT/portrait-extension availability for the selected lens without blocking the UI thread.
- [ ] **Step 5: Re-run instrumentation on the emulator.** Verify a complete snapshot returns and unsupported emulator capabilities remain explicitly false/unavailable.

### Task 3: Gate manual controls on real ranges

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/camera/CameraController.kt`
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Test: `app/src/androidTest/java/com/lumora/camera/camera/ManualControlsTest.kt`

- [ ] **Step 1: Write a control-range test.** Assert ISO and exposure-time requests are rejected when absent and clamped only to reported device limits when present.
- [ ] **Step 2: Run the focused test.** Expected: it fails because the current UI uses fixed ISO `100..1600` and shutter `1..500ms` values.
- [ ] **Step 3: Replace fixed UI ranges with the active capability snapshot.** Show unavailable state instead of sliders when the capture path does not support the control.
- [ ] **Step 4: Verify the still-capture path receives manual exposure requests.** Use the appropriate Camera2 interop API for bound `ImageCapture`; do not treat changes to repeating preview requests as proof of still-capture control.
- [ ] **Step 5: Re-run tests and emulator capture.** Compare the requested capture setting with resulting EXIF when EXIF reports it; label ignored or unreported controls unavailable.

### Task 4: Add truthful camera modes and support tools

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/camera/CameraController.kt`
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Create: `app/src/main/java/com/lumora/camera/camera/CaptureMode.kt`
- Test: `app/src/test/java/com/lumora/camera/camera/CaptureModeTest.kt`

- [ ] **Step 1: Test mode labels against implementation type.** Verify hardware extension, single-frame profile, and assist modes have distinct labels and never describe assist as multi-frame output.
- [ ] **Step 2: Implement AUTO/PRO and available CameraX extension selection.** Refresh supported modes when the lens changes; preserve normal capture fallback.
- [ ] **Step 3: Implement timer and bounded burst as app-level sequences.** Disable duplicate shutter requests, report partial burst count on failure, and cancel cleanly on lifecycle stop.
- [ ] **Step 4: Add toggleable grid, level, histogram, clipping warnings, and focus peaking only where live frame analysis supports them.** Clearly mark analysis overlays as preview aids and never use them as the saved full-resolution photo.
- [ ] **Step 5: Run unit/instrumentation tests and exercise lens switching.** Confirm unsupported features are disabled with a reason and camera rebinding refreshes capabilities.

### Task 5: Report device support accurately

**Files:**
- Modify: `app/src/main/java/com/lumora/camera/MainActivity.kt`
- Modify: `app/src/main/java/com/lumora/camera/data/PhotoRepository.kt`
- Modify: `docs/TEST_REPORT.md`
- Modify: `README.md`

- [ ] **Step 1: Add the device compatibility/settings section.** Show selected camera's available capability summary without claiming untested physical behavior.
- [ ] **Step 2: Persist only user preferences, not stale hardware capability snapshots.** Re-query hardware after camera bind and process recreation.
- [ ] **Step 3: Record emulator capability results and physical-device requirements.** Keep RAW/DNG, optical stabilization, extension, and autofocus results unverified where no hardware evidence exists.
- [ ] **Step 4: Run `.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug`.** Record exact results and APK location.
