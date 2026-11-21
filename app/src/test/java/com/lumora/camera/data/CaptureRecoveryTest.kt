package com.lumora.camera.data

import org.junit.Assert.*
import org.junit.Test

class CaptureRecoveryTest {
    private val entry = PendingCapture("LUMORA_test_1", null, "natural", "STANDARD", 1f, 12L)

    @Test fun resolvesTheWindowBeforeCameraXReportsItsUri() {
        var restored: PendingCapture? = null
        val forgotten = mutableListOf<String>()
        val outcomes = CaptureRecovery.reconcile(listOf(entry),
            resolveSource = { "content://media/external/images/media/12" },
            recover = { restored = it; RecoveryOutcome.RECOVERED }, forget = { forgotten += it })
        assertEquals(listOf(RecoveryOutcome.RECOVERED), outcomes)
        assertEquals("content://media/external/images/media/12", restored?.originalUri)
        assertEquals(listOf(entry.id), forgotten)
    }

    @Test fun transientProviderFailurePreservesTheJournal() {
        var forgotten = false
        val outcomes = CaptureRecovery.reconcile(listOf(entry),
            resolveSource = { throw IllegalStateException("provider unavailable") },
            recover = { error("must not recover") }, forget = { forgotten = true })
        assertEquals(listOf(RecoveryOutcome.RETRY), outcomes)
        assertFalse(forgotten)
    }

    @Test fun failedIndexCommitPreservesTheJournal() {
        var forgotten = false
        val outcomes = CaptureRecovery.reconcile(listOf(entry), resolveSource = { "content://source" },
            recover = { throw java.io.IOException("disk full") }, forget = { forgotten = true })
        assertEquals(listOf(RecoveryOutcome.RETRY), outcomes)
        assertFalse(forgotten)
    }

    @Test fun alreadyIndexedCaptureIsNotRestoredTwice() {
        var forgotten = 0
        val outcomes = CaptureRecovery.reconcile(listOf(entry), resolveSource = { "content://source" },
            recover = { RecoveryOutcome.ALREADY_INDEXED }, forget = { forgotten++ })
        assertEquals(listOf(RecoveryOutcome.ALREADY_INDEXED), outcomes)
        assertEquals(1, forgotten)
    }

    @Test fun currentProcessCaptureIsNeverReconciledWhileRendering() {
        val outcomes = CaptureRecovery.reconcile(listOf(entry), isActive = { true },
            resolveSource = { error("must not query") }, recover = { error("must not recover") },
            forget = { error("must not forget") })
        assertEquals(listOf(RecoveryOutcome.ACTIVE), outcomes)
    }

    @Test fun missingMediaNeverTriggersAWriteOrDelete() {
        var forgotten = false
        val outcomes = CaptureRecovery.reconcile(listOf(entry), resolveSource = { null },
            recover = { error("must not write") }, forget = { forgotten = true })
        assertEquals(listOf(RecoveryOutcome.MISSING), outcomes)
        assertTrue(forgotten)
    }
}
