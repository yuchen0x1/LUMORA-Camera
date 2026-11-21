package com.lumora.camera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterDiagnosticsTest {
    @Test
    fun recordsCaptureConfigurationTimingAndObservedPreviewSeparately() {
        val line = ShutterDiagnostics(
            focus = FocusStatus.FOCUSED,
            configuredJpegQuality = 96,
            streamWidth = 4032,
            streamHeight = 3024,
            preparationMs = 318,
            saveMs = 742,
            preview = PreviewCaptureState(1_234, 2, 2, 2, 0.75f, 10_000_000L)
        ).toDiagnosticLine()

        assertTrue(line.contains("afOutcome=FOCUSED"))
        assertTrue(line.contains("captureJpegQuality=96"))
        assertTrue(line.contains("previewAF=PASSIVE_FOCUSED"))
        assertTrue(line.contains("previewAE=CONVERGED"))
        assertTrue(line.contains("previewAWB=CONVERGED"))
        assertTrue(line.contains("jpegStream=4032x3024"))
        assertTrue(line.contains("preparationMs=318 captureSaveMs=742"))
        assertTrue(line.contains("previewExposureNs=10000000"))
        assertFalse(line.contains("actualStill"))
    }

    @Test
    fun marksUnavailableCamera2PreviewFactsInsteadOfInventingValues() {
        val line = ShutterDiagnostics(
            focus = FocusStatus.UNAVAILABLE,
            configuredJpegQuality = 95,
            streamWidth = null,
            streamHeight = null,
            preparationMs = 0,
            saveMs = 0,
            preview = null
        ).toDiagnosticLine()

        assertTrue(line.contains("jpegStream=unavailable"))
        assertTrue(line.contains("previewAF=unavailable"))
        assertTrue(line.contains("previewExposureNs=unavailable"))
    }
}
