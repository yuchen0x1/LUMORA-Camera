package com.lumora.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test
    fun manualExposureRequiresBothValidRanges() {
        val missing = DeviceCapabilities(cameraId = "0", lensFacing = "BACK")
        assertFalse(missing.supportsManualExposure)

        val valid = missing.copy(
            manualSensorAvailable = true,
            isoRange = IntRangeValue(100, 3200),
            exposureTimeRangeNs = LongRangeValue(1_000L, 100_000_000L)
        )
        assertTrue(valid.supportsManualExposure)
        assertFalse(valid.copy(manualSensorAvailable = false).supportsManualExposure)
        assertEquals(100, valid.clampIso(50))
        assertEquals(3200, valid.clampIso(5000))
        assertEquals(100_000_000L, valid.clampExposureTimeNs(500_000_000L))
    }

    @Test
    fun signedEvEndpointsCanCrossZero() {
        val ev = IntRangeValue(-12, 12)
        assertEquals(-12, ev.clamp(-13))
        assertEquals(0, ev.clamp(0))
        assertEquals(12, ev.clamp(16))
    }

    @Test
    fun closeFocusUsesCalibratedLensesOnTheSelectedSide() {
        val back = LensOption("0", "BACK", listOf(4f), 4f)
        val near = LensOption("2", "BACK", listOf(2f), 12f)
        val front = LensOption("1", "FRONT", listOf(2f), 20f)
        assertEquals(back, closestFocusLens(back, listOf(back, front, near.copy(calibratedMaxDiopters = null))))
        assertEquals(near, closestFocusLens(back, listOf(back, front, near)))
    }

    @Test
    fun missingOrDetachedLensFallsBackToAnOpenableCamera() {
        val front = LensOption("front", "FRONT", emptyList())
        val back = LensOption("main", "BACK", emptyList())
        assertEquals(front, preferredLens("front", listOf(back, front)))
        assertEquals(back, preferredLens("detached", listOf(front, back)))
        assertEquals(front, preferredLens("detached", listOf(front)))
        assertEquals(null, preferredLens(null, emptyList()))
    }

    @Test
    fun cropOrientationFollowsTheRotatedJpegStream() {
        for (rotation in listOf(0, 180)) {
            assertFalse(portraitAfterRotation(4000, 3000, rotation))
            assertTrue(portraitAfterRotation(3000, 4000, rotation))
        }
        for (rotation in listOf(90, 270)) {
            assertTrue(portraitAfterRotation(4000, 3000, rotation))
            assertFalse(portraitAfterRotation(3000, 4000, rotation))
        }
    }

    @Test
    fun completeManualRequestsAreBoundedAndCanReturnToAuto() {
        val sensor = DeviceCapabilities("0", "BACK", isoRange = IntRangeValue(64, 800),
            exposureTimeRangeNs = LongRangeValue(100L, 1000L), manualSensorAvailable = true,
            minimumFocusDistance = 2.5f, supportsManualFocus = true)
        assertEquals(ManualControls(64, 1000L, focusDiopters = 2.5f),
            ManualControls(1, 2000L, focusDiopters = 3f).forCamera(sensor))
        assertEquals(ManualControls(), ManualControls().forCamera(sensor))
        assertTrue(runCatching { ManualControls(iso = 100).forCamera(sensor) }.isFailure)
        assertTrue(runCatching { ManualControls(iso = 100, exposureTimeNs = 900, aeLock = true).forCamera(sensor) }.isFailure)
        assertTrue(runCatching { ManualControls(whiteBalanceMode = 4).forCamera(sensor) }.isFailure)
        assertTrue(runCatching { ManualControls(100, 900L).forCamera(sensor.copy(manualSensorAvailable = false)) }.isFailure)
    }

    @Test
    fun unavailableCapabilitiesStayExplicitlyUnavailable() {
        val capabilities = DeviceCapabilities(cameraId = "0", lensFacing = "FRONT")
        assertEquals("不可用", capabilities.isoSummary())
        assertEquals("不可用", capabilities.exposureSummary())
        assertFalse(capabilities.supportsRaw)
    }
}
