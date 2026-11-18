package com.lumora.camera.image

import com.lumora.camera.data.OptimizationStrength
import org.junit.Assert.*
import org.junit.Test

class PhotoEditParametersTest {
    @Test fun neutralBoundsCopyAndStrengthOrder() {
        val neutral = PhotoEditParameters.NATURAL
        assertEquals(neutral, PhotoEditParameters())
        assertEquals(neutral, neutral.copy(exposure = Float.NaN, denoise = Float.POSITIVE_INFINITY))
        val edits = neutral.copy(3f, -2f, 9f, -8f, -9f, 7f, -3f, 2f, 3f, -2f, 4f, 3f)
        assertEquals(PhotoEditParameters(2f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, 0f, 1f, 1f), edits)
        assertEquals(edits, edits.copy())
        assertEquals(edits.hashCode(), edits.copy().hashCode())
        assertTrue(OptimizationStrength.NATURAL.factor < OptimizationStrength.STANDARD.factor)
        assertTrue(OptimizationStrength.STRONG.factor > OptimizationStrength.STANDARD.factor)
    }

    @Test fun fullResolutionBudgetAccountsForRowsAndRejectsOverflowSizedInputs() {
        assertTrue(PhotoEnhancementEngine.fitsBitmapBudget(4000, 3000, 70_000_000))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(4000, 3000, 40_000_000))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(0, 200, Long.MAX_VALUE))
        assertTrue(PhotoEnhancementEngine.fitsBitmapBudget(640, 480, 2_000_000, 450_000))
        assertTrue(PhotoEnhancementEngine.fitsBitmapBudget(3024, 909, 16_000_000, 1_000_000))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(3024, 909, 16_000_000, 64_000))
        assertFalse(PhotoEnhancementEngine.fitsBitmapBudget(32768, 32768, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
