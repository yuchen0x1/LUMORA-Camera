package com.lumora.camera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModeTest {
    @Test
    fun assistModesDoNotClaimHardwareMultiFrameOutput() {
        assertTrue(CaptureMode.NIGHT_ASSIST.isAssist)
        assertFalse(CaptureMode.NIGHT_ASSIST.label.contains("多帧"))
        assertTrue(CaptureMode.HDR_EXTENSION.isHardwareBacked)
        assertTrue(CaptureMode.NIGHT_EXTENSION.isHardwareBacked)
        assertTrue(CaptureMode.PORTRAIT_EXTENSION.isHardwareBacked)
        assertFalse(CaptureMode.STREET_PROFILE.isHardwareBacked)
        assertFalse(CaptureMode.LONG_EXPOSURE_ASSIST.isHardwareBacked)
    }
}
