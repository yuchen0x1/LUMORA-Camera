# LUMORA Camera V2.0 Design

**Date:** 2026-11-18  
**Status:** Approved by the user on 2026-11-18  
**Project:** LUMORA Camera for Android

## Goal

Upgrade the existing LUMORA Camera app toward V2.0 in reviewable stages, starting with system-safe camera controls and a full-resolution capture/processing path, then making camera controls, style processing, gallery/editor, and settings reflect real device capability. Preserve existing working behavior and original photographs. Treat hardware-only behavior and image quality as claims that require device evidence.

## Current Baseline

- Native Android app using Kotlin, Jetpack Compose, CameraX 1.6.2, minSdk 29, compileSdk 36, and targetSdk 35.
- `MainActivity.kt` contains camera, gallery, detail, and settings UI. Camera overlays account for navigation bars only; the activity is locked to portrait.
- `CameraController.kt` writes a CameraX JPEG to MediaStore, supports tap-to-meter/focus, zoom, exposure compensation, flash, and has a Camera2 request path whose actual still-capture effect is not verified.
- Capture currently uses `CAPTURE_MODE_MINIMIZE_LATENCY`. `PhotoEnhancementEngine.kt` downsamples images whose longest side exceeds 2400 pixels before writing a second JPEG.
- Twelve film presets already have distinct numeric parameters. The current camera strip uses generated color swatches, not sample photographs.
- Existing reports and screenshots document prior API 35 emulator work. No Android device is connected in this workspace, and this directory has no Git metadata.

## Approaches Considered

1. **Replace the application architecture and camera UI in one rewrite.** This allows broad redesign but makes it difficult to isolate capture regressions and risks losing existing gallery data and behavior.
2. **Incrementally harden the existing app and split only components that need separate responsibilities.** This preserves current CameraX, MediaStore, preset, and repository behavior while delivering each V2.0 phase with a distinct validation result.

**Selected:** Approach 2. It reduces regression risk and allows P0 safety and image-pipeline defects to be resolved before adding more controls. The requested V2.0 scope remains the target; phases are delivery and review boundaries, not a reduction of scope.

## Architecture and Behavior

### 1. System bars and adaptive layout

- Establish one Activity-level edge-to-edge and system-bar appearance policy that responds to light/dark system appearance and configuration changes.
- Ordinary pages place controls and scroll content inside the Compose safe drawing area. The camera preview may draw behind system bars, while its top and bottom controls use the applicable safe drawing/gesture insets exactly once.
- Use inset-aware layout rather than fixed status-bar heights. Keep touch targets aligned with their rendered controls. Support portrait, landscape, cutouts, gesture navigation, three-button navigation, compact phones, and wider screens with responsive arrangements.
- Remove the portrait-only manifest restriction. Re-evaluate camera controls and preview layout after orientation changes without leaving stale camera bindings.

### 2. Capture, enhancement, and diagnostics

- Keep CameraX `ImageCapture` as the source of saved photographs. Do not create final photos from `PreviewView`, screenshots, or preview-analysis frames.
- Prefer the quality capture mode and the largest supported still-output resolution available from the bound camera. Record the actual saved JPEG dimensions rather than presenting a requested size as a result.
- Preserve the original capture URI. Produce an enhanced image as a separate MediaStore item; a processing error must leave the source photograph available. Never intentionally downsample the final enhanced output. Bound temporary memory by processing pixel rows/chunks in place where feasible. If a device cannot safely process a full-resolution image within its available memory, retain the original, report the processing failure, and do not substitute a smaller image without clearly marking that behavior.
- Copy orientation and relevant capture metadata to the enhanced JPEG. Avoid unnecessary JPEG encode/decode cycles and use one final encode for the enhanced image.
- Record capture diagnostics: actual output dimensions, byte size, MIME/format, selected lens/camera identifier when available, selected mode, EXIF ISO and exposure time when present, EXIF focal length/focus distance when present, and enhancement duration/output dimensions. A missing value is reported as unavailable, not inferred.

### 3. Device capability and camera controls

- Add a capability manager based on CameraX and Camera2 characteristics for available front/back cameras, focal lengths, JPEG output sizes, flash, zoom, exposure compensation, sensor sensitivity/exposure-time ranges, AF support, RAW capability, hardware level, stabilization characteristics when available, and supported CameraX extensions.
- Re-query capabilities when the selected lens changes. Controls are enabled only when the current camera reports support and the capture path applies the setting. ISO and shutter controls must use the reported range and must not show fixed sample values as hardware truth.
- Retain supported existing controls: lens switching, tap focus/metering, flash, exposure compensation, zoom, grid, and HDR availability. Add or expose other requested modes only when they map to a real CameraX extension, supported camera request, or explicitly identified assist behavior.
- The collapsible PRO panel covers exposure compensation, ISO, shutter time, white balance, manual focus, zoom/focal-length selection, and RAW/DNG only when each control has a verified capture path. Display reported values and unsupported reasons. Provide timer and burst controls as actual app-level capture sequences; stop or report partial results on capture failure.
- Provide AUTO and PRO directly. PORTRAIT, NIGHT, and HDR use available CameraX extensions when the selected lens reports them. MACRO can select a camera that reports an appropriate focal length/minimum focus distance; otherwise offer ordinary tap focus without claiming a macro lens. LANDSCAPE and STREET are named capture profiles only when their parameter choices are visible and real. LONG EXPOSURE is labeled as guidance/assist unless the camera and output path can actually capture the requested exposure.
- Keep composition grid; add level, live histogram, clipping warnings, and focus peaking only through live analysis of camera frames. These overlays must be toggleable and must not silently affect the saved full-resolution capture. If a tool cannot be computed reliably on a device, show it as unavailable.
- Do not label single-frame processing as multi-frame night mode, portrait depth, optical stabilization, RAW/DNG, or long exposure. Unsupported capabilities are hidden or disabled with a concise reason. RAW/DNG remains unavailable until a working output path is implemented and validated on a RAW-capable device.

### 4. Styles, automatic enhancement, and photo library

- Preserve the twelve existing preset IDs and their current saved-selection compatibility. Keep preset parameters independent; add Rainy Mood, Urban Night, Forest Green, Seaside Sunset, Flash Street, Low-Contrast Portrait, High-Contrast Monochrome, and Warm Documentary only with distinct parameters and an observable effect. Parameter groups cover tone/curves, color balance, saturation, highlights/shadows, black/white point, grain, vignette, and detail adjustments. Do not claim a GPU path unless one is implemented and measured.
- Run content-aware exposure and white-balance analysis, restrained highlight protection and shadow recovery, and explicit NATURAL/STANDARD/ENHANCED strength scaling. Keep processing deterministic and explainable; do not claim multi-frame denoising, scene segmentation, face-aware protection, or recovered clipped detail without an implementation that demonstrates it.
- The editor exposes before/after comparison and the requested controls where they are implemented: style/intensity, exposure, contrast, highlights, shadows, temperature, tint, saturation, clarity/detail, sharpening, denoise, grain, and vignette. Store edit parameters or a new output record so a later edit can be undone/reapplied without changing the original.
- Preserve the captured original and store each processed result separately. Natural, Standard, and Enhanced strengths remain explicit processing levels. Reprocessing creates a new result and never overwrites the source.
- Replace synthetic style swatches as evidence of photographic appearance with previews derived from a real camera/gallery photograph when one is available. Do not bundle generated or placeholder imagery and call it a sample. Until a real image is available, show the style name and an honest preview-unavailable state.
- Extend the existing gallery/detail flow into a photo library/editor flow for original-versus-result comparison, style/strength selection, reprocessing, delete/share, timestamps, source/result identification, and favorite state. Persist additions compatibly with the existing SharedPreferences JSON records; do not invalidate existing records.
- Keep filter controls and automatic enhancement on a background dispatcher. Ensure thumbnail decoding is bounded to display size; full-resolution editing must not run on the main thread.

### 5. Product UI, settings, and versioning

- Keep the established dark camera-equipment visual direction with restrained warm accent color. Avoid dense decorative cards and keep camera controls legible over variable previews.
- Provide a compact camera screen with safe top controls, preview, optional grid, film selection, capture, gallery, lens switching, and a collapsible PRO panel. Adapt to short landscape height by reducing secondary controls before obscuring the preview or shutter.
- Keep flash, HDR/extension, aspect ratio, timer, settings, lens/focal information, and shutter controls discoverable without crowding the preview. Provide adequate touch targets and a volume-key shutter only when the activity can handle the key without disrupting system volume behavior.
- Keep gallery, editor/detail, and settings navigation coherent with the same color, typography, spacing, and safe-area rules. Group settings into camera, capture, output quality, storage, device support, and about sections where applicable.
- Update app version to `2.0.0`, generate `CHANGELOG.md`, update README and architecture notes, and maintain build/test, device compatibility, capture-quality diagnostics, and performance reports that separate emulator, physical-device, and unverified hardware results.

## Delivery Phases

1. **P0 layout:** centralize window/inset behavior, remove fixed-orientation lock, fix camera control safe areas, and verify orientation and navigation inset rendering.
2. **P0 capture quality:** use quality-oriented capture and supported still sizes; remove silent 2400-pixel downsampling; retain originals; add actual file/EXIF and processing diagnostics; verify failure paths and memory behavior.
3. **Capability-backed camera:** add device capability reporting, correct or disable unsupported PRO controls, and expose only verified lens/extension/mode options.
4. **Photography workflow:** preserve existing presets, implement the defined automatic enhancement strengths and editor parameters, add supported requested styles, derive previews from actual photographs, and complete gallery/editor/settings behavior without overwriting originals.
5. **Release and acceptance:** set version 2.0.0, update documentation and changelog, run unit/build checks, produce a Debug APK and an unsigned Release APK when signing credentials are not configured, and report emulator/physical-device test coverage honestly.

## Acceptance Evidence

- Camera controls remain inside safe drawing and gesture areas with no duplicated inset padding in portrait and landscape emulator runs; controls can be tapped at their rendered positions. Exercise compact/short, tall, and wide emulator profiles plus simulated cutout and both navigation modes where the installed emulator images support them.
- Saved original photographs are produced by CameraX `ImageCapture`, and diagnostics report actual dimensions. Enhanced output retains the source dimensions when processing succeeds; failures preserve the source and expose a diagnostic.
- ISO, exposure time, focal length, RAW, extension, and stabilization claims match queried device capabilities and the actual capture path. No unsupported control reports success.
- All twelve existing preset IDs and existing saved photo records remain readable. Reprocessing produces a separate output and leaves the original URI untouched.
- Exercise repeated lens switching, long preview, background/foreground resume, large-photo processing, and a 100-capture endurance run when the available emulator/device permits it. Record memory, failures, and thermal/device limits; do not state physical endurance passed based only on an emulator run.
- README, changelog, and verification report distinguish built artifacts from installed/run validation and distinguish emulator observations from physical-device results.
- Deliver Debug APK. Deliver Release APK only as signed when a configured signing setup exists; otherwise identify it as unsigned.

## Verification Boundaries

This design does not promise flagship-level image quality across devices. Emulator checks can validate layout, file flow, diagnostics, and interaction, but cannot establish lens autofocus, vendor ISP quality, OIS, RAW output, extension behavior, skin tone, or night-scene quality on physical cameras. A physical-device comparison requires an available device, a fixed scene/lens/exposure procedure, and captured source files. No such physical device is connected at design time.

The project folder is not a Git repository. The design document can be reviewed in place, but it cannot be committed unless the project is later placed in a Git repository.
