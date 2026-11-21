package com.lumora.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BitmapBudgetPolicyTest {
    @Test
    fun transientGarbageDoesNotRejectSmallRowBuffersBeforeArtCanCollect() {
        val heap = 16L * 1024 * 1024
        val budget = PhotoEnhancementEngine.managedProcessingBudget(heap, heap - 60_160)
        assertEquals(heap / 8, budget)
        assertTrue(PhotoEnhancementEngine.fitsBitmapBudget(640, 480, 32L * 1024 * 1024, budget))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(32768, 32768, 256L * 1024 * 1024, budget))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(640, 480, 1_000, budget))
    }

    @Test
    fun successfulPreviewKeepsItsRequestedResolution() {
        val attempts = mutableListOf<Int>()
        val edge = PhotoEnhancementEngine.previewWithFallback(997) {
            attempts += it
            it
        }
        assertEquals(997, edge)
        assertEquals(listOf(997), attempts)
    }

    @Test
    fun previewAllocationFailuresRetryAtSmallerEdges() {
        val attempts = mutableListOf<Int>()
        val edge = PhotoEnhancementEngine.previewWithFallback(1601) {
            attempts += it
            if (it > 400) throw OutOfMemoryError("controlled preview allocation failure")
            it
        }
        assertEquals(400, edge)
        assertEquals(listOf(1601, 800, 400), attempts)
    }

    @Test
    fun previewAllocationFallbackStopsAfterOnePixelFails() {
        val attempts = mutableListOf<Int>()
        val result = PhotoEnhancementEngine.previewWithFallback<Int>(3) {
            attempts += it
            throw OutOfMemoryError("controlled preview allocation failure")
        }
        assertNull(result)
        assertEquals(listOf(3, 1), attempts)
    }

    @Test
    fun unreadablePreviewDoesNotRetryWithSmallerDimensions() {
        var attempts = 0
        val result = PhotoEnhancementEngine.previewWithFallback<Int>(640) {
            attempts++
            throw IOException("controlled source read failure")
        }
        assertNull(result)
        assertEquals(1, attempts)
    }

    @Test
    fun availableHeadroomIsUsedWithoutInventingMoreThanTheHeapLimit() {
        val heap = 16L * 1024 * 1024
        assertEquals(heap / 2, PhotoEnhancementEngine.managedProcessingBudget(heap, heap / 2))
        assertEquals(heap / 8, PhotoEnhancementEngine.managedProcessingBudget(heap, heap + 1))
        assertEquals(0L, PhotoEnhancementEngine.managedProcessingBudget(0, 0))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(640, 480, 32L * 1024 * 1024, 0))
    }
}
