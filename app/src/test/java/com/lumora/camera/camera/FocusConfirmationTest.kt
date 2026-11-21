package com.lumora.camera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusConfirmationTest {
    private var now = 0L
    private val confirmation = FocusConfirmation { now }

    @Test fun noConfirmationIsInitiallyReusable() { assertFalse(confirmation.isRecent()) }

    @Test fun confirmationAtMonotonicZeroIsValidAndExpiresAtBoundary() {
        confirmation.confirm()
        assertTrue(confirmation.isRecent())
        now = 1_199
        assertTrue(confirmation.isRecent())
        now = 1_200
        assertFalse(confirmation.isRecent())
    }

    @Test fun manualThenAutoRequiresNewConfirmationEvenWithoutAnAcknowledgment() {
        confirmation.confirm()
        assertTrue(confirmation.controlsRequested(2f))
        assertFalse(confirmation.isRecent())
        assertTrue(confirmation.controlsRequested(null))
        assertFalse(confirmation.isRecent())
        confirmation.confirm()
        assertTrue(confirmation.isRecent())
    }

    @Test fun changingManualDistanceInvalidatesConfirmation() {
        confirmation.controlsRequested(1f)
        confirmation.confirm()
        assertTrue(confirmation.controlsRequested(2f))
        assertFalse(confirmation.isRecent())
    }

    @Test fun unchangedFocusRequestRetainsConfirmation() {
        confirmation.confirm()
        assertFalse(confirmation.controlsRequested(null))
        assertTrue(confirmation.isRecent())
    }

    @Test fun clearingAndBackwardClockCannotReuseConfirmation() {
        now = 10
        confirmation.confirm()
        now = 9
        assertFalse(confirmation.isRecent())
        now = 11
        confirmation.clear()
        assertFalse(confirmation.isRecent())
    }
}
