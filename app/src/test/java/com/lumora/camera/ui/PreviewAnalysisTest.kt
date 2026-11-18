package com.lumora.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAnalysisTest {
    @Test fun histogramCountsDarkAndBrightSamplesAndZebrasMarkBothLimits() {
        val pixels = IntArray(64) { if (it < 32) 0xff010101.toInt() else 0xfffdfdfd.toInt() }
        val result = analyzePreview(pixels, 8, 8, PhotoAids(histogram = true, clipping = true))
        val bins = result.bins!!
        assertEquals(64, bins.sum())
        assertEquals(32, bins.first())
        assertEquals(32, bins.last())
        val mask = result.overlayPixels!!
        assertNotEquals(0, mask[0])
        assertNotEquals(0, mask[36])
        assertNotEquals(mask[0], mask[36])
        assertTrue(mask.any { it == 0 })
    }

    @Test fun peakingFindsContrastEdgesInsideTheImage() {
        val pixels = IntArray(25) { if (it % 5 < 2) 0xff505050.toInt() else 0xffa0a0a0.toInt() }
        val result = analyzePreview(pixels, 5, 5, PhotoAids(peaking = true))
        assertNull(result.bins)
        val mask = result.overlayPixels!!
        assertNotEquals(0, mask[2 * 5 + 2])
        assertEquals(0, mask[2 * 5 + 3])
        assertEquals(0, mask[0])
    }

    @Test fun levelCompensatesForAllDisplayRotationsAndRejectsFlatDevices() {
        assertEquals(0f, horizonAngle(0f, 9.8f, 0)!!, 0.001f)
        assertEquals(0f, horizonAngle(9.8f, 0f, 1)!!, 0.001f)
        assertEquals(0f, horizonAngle(0f, -9.8f, 2)!!, 0.001f)
        assertEquals(0f, horizonAngle(-9.8f, 0f, 3)!!, 0.001f)
        assertEquals(-45f, horizonAngle(-6.9f, 6.9f, 0)!!, 0.001f)
        assertNull(horizonAngle(0.1f, -0.1f, 0))
        assertFalse(PhotoAids(level = true, grid = CompositionGrid.GOLDEN).samplesFrames)
    }
}
