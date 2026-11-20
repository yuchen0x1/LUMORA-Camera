package com.lumora.camera.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdateCheckPolicyTest {
    private val now = TimeUnit.DAYS.toMillis(100)

    @Test fun manualFrequencyNeverSchedulesAutomaticChecks() {
        assertFalse(UpdateCheckPolicy.isDue(UpdateFrequency.MANUAL, now, 0, 0))
    }

    @Test fun firstAutomaticCheckIsDue() {
        listOf(UpdateFrequency.DAILY, UpdateFrequency.WEEKLY).forEach {
            assertTrue(UpdateCheckPolicy.isDue(it, now, 0, 0))
        }
    }

    @Test fun successfulChecksRespectTheirFrequencyIncludingExactBoundary() {
        listOf(UpdateFrequency.DAILY, UpdateFrequency.WEEKLY).forEach {
            val interval = TimeUnit.DAYS.toMillis(it.days)
            assertFalse(UpdateCheckPolicy.isDue(it, now, now - interval + 1, 0))
            assertTrue(UpdateCheckPolicy.isDue(it, now, now - interval, 0))
        }
    }

    @Test fun failedCheckOnlyDelaysByShortBackoffNotAFullWeek() {
        val failure = now - UpdateCheckPolicy.failureBackoffMillis
        assertFalse(UpdateCheckPolicy.isDue(UpdateFrequency.WEEKLY, now - 1, 0, failure))
        assertTrue(UpdateCheckPolicy.isDue(UpdateFrequency.WEEKLY, now, 0, failure))
    }

    @Test fun failureDoesNotDiscardTheLastSuccessfulCadence() {
        assertFalse(UpdateCheckPolicy.isDue(UpdateFrequency.WEEKLY, now,
            now - TimeUnit.DAYS.toMillis(1), now - TimeUnit.HOURS.toMillis(1)))
    }

    @Test fun clockRollbackCannotSuppressChecksIndefinitely() {
        assertTrue(UpdateCheckPolicy.isDue(UpdateFrequency.DAILY, now, now + 1, now + 1))
    }
}
