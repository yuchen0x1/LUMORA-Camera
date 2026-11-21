package com.lumora.camera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock

/** A preview result is context, not proof of what the still request used. JPEG EXIF stays authoritative. */
internal class PreviewCaptureObserver : CameraCaptureSession.CaptureCallback() {
    @Volatile private var latest: PreviewCaptureState? = null

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) != CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW) return
        latest = PreviewCaptureState(
            SystemClock.elapsedRealtime(), result.get(CaptureResult.CONTROL_AF_STATE),
            result.get(CaptureResult.CONTROL_AE_STATE), result.get(CaptureResult.CONTROL_AWB_STATE),
            result.get(CaptureResult.LENS_FOCUS_DISTANCE), result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        )
    }

    fun recent(nowMs: Long): PreviewCaptureState? = latest?.takeIf { nowMs - it.observedAtMs in 0..750 }
}

internal data class PreviewCaptureState(
    val observedAtMs: Long,
    val af: Int?,
    val ae: Int?,
    val awb: Int?,
    val focusDiopters: Float?,
    val exposureNs: Long?
)

internal data class ShutterDiagnostics(
    val focus: FocusStatus,
    val configuredJpegQuality: Int,
    val streamWidth: Int?,
    val streamHeight: Int?,
    val preparationMs: Long,
    val saveMs: Long,
    val preview: PreviewCaptureState?
) {
    fun toDiagnosticLine(): String = buildString {
        append("afOutcome=$focus captureJpegQuality=$configuredJpegQuality")
        val stream = if (streamWidth != null && streamHeight != null) "${streamWidth}x$streamHeight" else "unavailable"
        append(" jpegStream=$stream")
        append(" preparationMs=$preparationMs captureSaveMs=$saveMs")
        append(" previewAF=${preview?.af?.let(::afState) ?: "unavailable"}")
        append(" previewAE=${preview?.ae?.let(::aeState) ?: "unavailable"}")
        append(" previewAWB=${preview?.awb?.let(::awbState) ?: "unavailable"}")
        append(" previewFocusDiopters=${preview?.focusDiopters ?: "unavailable"}")
        append(" previewExposureNs=${preview?.exposureNs ?: "unavailable"}")
    }
}

private fun afState(value: Int): String = when (value) {
    CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
    else -> "UNKNOWN($value)"
}

private fun aeState(value: Int): String = when (value) {
    CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
    CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
    CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
    CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
    else -> "UNKNOWN($value)"
}

private fun awbState(value: Int): String = when (value) {
    CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
    CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
    CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
    else -> "UNKNOWN($value)"
}
