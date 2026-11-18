package com.lumora.camera.camera

import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityRecordTest {
    @Test
    fun formatsMeasuredCaptureFactsAndMarksMissingValuesUnavailable() {
        val record = CaptureQualityRecord(
            width = 4032,
            height = 3024,
            bytes = 1_234_567,
            mimeType = "image/jpeg",
            cameraId = "0",
            lensFacing = "BACK",
            mode = "AUTO",
            iso = null,
            exposureTimeNs = 10_000_000L,
            focalLengthMm = null,
            focusDistanceM = null,
            processingMillis = 148L,
            outputWidth = 4032,
            outputHeight = 3024
        )

        val text = record.toDiagnosticLine()

        assertTrue(text.contains("input=4032x3024"))
        assertTrue(text.contains("bytes=1234567"))
        assertTrue(text.contains("iso=unavailable"))
        assertTrue(text.contains("exposureNs=10000000"))
        assertTrue(text.contains("output=4032x3024"))
    }
}
