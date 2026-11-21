package com.lumora.camera.camera

import org.junit.Assert.*
import org.junit.Test

class ManualRestoreTest {
    private val sensor = DeviceCapabilities("0", "BACK", isoRange = IntRangeValue(64, 800),
        exposureTimeRangeNs = LongRangeValue(100L, 1000L), manualSensorAvailable = true,
        minimumFocusDistance = 2.5f, supportsManualFocus = true)

    @Test fun rebindPreservesAcceptedManualExposure() {
        val desired = ManualControls(320, 900L, focusDiopters = 1.5f)
        assertEquals(desired, desired.forReboundCamera(sensor))
    }

    @Test fun differentLensClampsInsteadOfLosingTheWholeRecipe() {
        val desired = ManualControls(1600, 2000L, focusDiopters = 3f)
        assertEquals(ManualControls(800, 1000L, focusDiopters = 2.5f), desired.forReboundCamera(sensor))
    }

    @Test fun unsupportedLensReturnsOnlyUnavailableControlsToAuto() {
        val desired = ManualControls(320, 900L, focusDiopters = 1.5f)
        assertEquals(ManualControls(focusDiopters = 1.5f),
            desired.forReboundCamera(sensor.copy(manualSensorAvailable = false)))
        assertEquals(ManualControls(), desired.forReboundCamera(DeviceCapabilities("1", "FRONT")))
    }
}
