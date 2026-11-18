package com.lumora.camera.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.FilmPresets
import com.lumora.camera.data.OptimizationStrength
import org.junit.Assert.*
import org.junit.Test

class FilmColorEngineTest {
    private val presets = FilmPresets.load(InstrumentationRegistry.getInstrumentation().targetContext)
    private val neutral = presets.first { it.id == "natural" }.copy(
        highlights = 0f, shadows = 0f, sharpness = 0f, vignette = 0f
    )
    private val standard = OptimizationStrength.STANDARD

    private fun colors(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test fun everyStyleHasADistinctDeterministicAppearanceAndSourceIsUnchanged() {
        val width = 45
        val height = 27
        val pixels = IntArray(width * height) { i ->
            Color.rgb((i * 11) % 256, (i / width * 15) % 256, (i * 7) % 256)
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val profiles = HashSet<List<Int>>()
        for (preset in presets) {
            val first = FilmColorEngine.apply(source, preset, standard)
            val second = FilmColorEngine.apply(source, preset, standard)
            assertEquals(width, first.width)
            assertEquals(height, first.height)
            assertTrue(first.sameAs(second))
            assertTrue(preset.id, profiles.add(colors(first).toList()))
            first.recycle()
            second.recycle()
        }
        assertEquals(20, profiles.size)
        assertArrayEquals(pixels, colors(source))
        source.recycle()
    }

    @Test fun eachEditingControlChangesItsOwnRender() {
        val pixels = IntArray(35 * 35) { i ->
            val luma = (i % 35) * 6 + (i * 13 % 17) + 9
            Color.rgb(luma, luma + 10, luma + 20)
        }
        val source = Bitmap.createBitmap(pixels, 35, 35, Bitmap.Config.ARGB_8888)
        val baseline = FilmColorEngine.apply(source, neutral, standard)
        listOf(
            PhotoEditParameters(exposure = 1f), PhotoEditParameters(contrast = 1f),
            PhotoEditParameters(highlights = -1f), PhotoEditParameters(shadows = 1f),
            PhotoEditParameters(temperature = 1f), PhotoEditParameters(tint = 1f),
            PhotoEditParameters(saturation = -1f), PhotoEditParameters(detail = 1f),
            PhotoEditParameters(sharpening = 1f), PhotoEditParameters(denoise = 1f),
            PhotoEditParameters(grain = 1f), PhotoEditParameters(vignette = 1f)
        ).forEachIndexed { index, edits ->
            val changed = FilmColorEngine.apply(source, neutral, standard, edits)
            assertFalse("control $index", baseline.sameAs(changed))
            changed.recycle()
        }
        baseline.recycle()
        source.recycle()
    }

    @Test fun automaticDenoiseReducesDarkRippleAndPreservesTheBrightEdge() {
        val pixels = IntArray(48 * 36) { i ->
            val x = i % 48
            val y = i / 48
            val gray = if (x < 24) 35 + (x + y) % 2 * 14 else 160
            Color.rgb(gray, gray, gray)
        }
        val bitmap = Bitmap.createBitmap(pixels, 48, 36, Bitmap.Config.ARGB_8888)
        val output = FilmColorEngine.apply(bitmap, neutral, standard)
        fun value(x: Int) = Color.green(output.getPixel(x, 12))
        assertTrue(kotlin.math.abs(value(8) - value(9)) < 10)
        assertTrue(value(24) - value(23) > 95)
        bitmap.recycle()
        output.recycle()
    }

    @Test fun automaticDetailRespondsToLocalStructureOnCleanMidtones() {
        val pixels = IntArray(32 * 16) { i ->
            val gray = when (i % 32) { 9 -> 86; 11 -> 140; else -> 130 }
            Color.rgb(gray, gray, gray)
        }
        val source = Bitmap.createBitmap(pixels, 32, 16, Bitmap.Config.ARGB_8888)
        val output = FilmColorEngine.apply(source, neutral, standard)
        assertTrue(Color.green(output.getPixel(10, 5)) > Color.green(output.getPixel(24, 5)))
        source.recycle()
        output.recycle()
    }

    @Test fun nearNeutralColorCastIsReducedWithoutIntroducingANewDominantColor() {
        val buffer = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        buffer.eraseColor(Color.rgb(145, 128, 118))
        val corrected = FilmColorEngine.applyInPlace(buffer, neutral, standard).getPixel(12, 12)
        assertTrue(Color.red(corrected) - Color.green(corrected) in 1..15)
        assertTrue(Color.green(corrected) - Color.blue(corrected) in 1..8)
        buffer.recycle()
    }

    @Test fun automaticCorrectionPreservesMiddleGrayAndScalesOnDarkFrames() {
        fun transform(gray: Int, level: OptimizationStrength): Int {
            val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(gray, gray, gray))
            val rendered = FilmColorEngine.applyInPlace(bitmap, neutral, level)
            val pixel = rendered.getPixel(0, 0)
            assertEquals(Color.red(pixel), Color.blue(pixel))
            assertEquals(Color.green(pixel), Color.blue(pixel))
            bitmap.recycle()
            return Color.green(pixel)
        }
        assertEquals(128, transform(128, standard))
        val levels = OptimizationStrength.values().map { transform(32, it) }
        assertTrue(levels.first() > 32 && levels.zipWithNext().all { (a, b) -> a < b })
    }
}
