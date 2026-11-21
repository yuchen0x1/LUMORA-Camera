package com.lumora.camera

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.ui.CompositionGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CameraSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "camera-settings-test-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private fun settings() = CameraSettings(context, namespace)

    @After fun cleanup() { context.deleteSharedPreferences(namespace) }

    @Test fun freshSettingsDefaultToNoGridWithoutWriting() {
        assertEquals(CompositionGrid.OFF, settings().compositionGrid)
        assertFalse(settings().goldenGrid)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun everyGridRoundTripsAcrossSettingsInstances() {
        CompositionGrid.entries.forEach { grid ->
            settings().compositionGrid = grid
            assertEquals(grid, settings().compositionGrid)
            assertEquals(grid.name, prefs.getString("compositionGrid", null))
            assertEquals(grid == CompositionGrid.GOLDEN, prefs.getBoolean("goldenGrid", false))
        }
    }

    @Test fun legacyGoldenPreferenceRemainsReadableWithoutDestructiveMigration() {
        prefs.edit().putBoolean("goldenGrid", true).commit()
        assertEquals(CompositionGrid.GOLDEN, settings().compositionGrid)
        assertFalse(prefs.contains("compositionGrid"))
        prefs.edit().putBoolean("goldenGrid", false).commit()
        assertEquals(CompositionGrid.OFF, settings().compositionGrid)
    }

    @Test fun namedGridTakesPrecedenceOverLegacyBoolean() {
        prefs.edit().putString("compositionGrid", "THIRDS").putBoolean("goldenGrid", true).commit()
        assertEquals(CompositionGrid.THIRDS, settings().compositionGrid)
        assertFalse(settings().goldenGrid)
    }

    @Test fun unknownGridFallsBackToLegacyChoiceWithoutRewritingIt() {
        prefs.edit().putString("compositionGrid", "future-grid").putBoolean("goldenGrid", true).commit()
        assertEquals(CompositionGrid.GOLDEN, settings().compositionGrid)
        assertEquals("future-grid", prefs.getString("compositionGrid", null))
        prefs.edit().putBoolean("goldenGrid", false).commit()
        assertEquals(CompositionGrid.OFF, settings().compositionGrid)
    }

    @Test fun legacySetterUpdatesBothRepresentations() {
        val settings = settings()
        settings.compositionGrid = CompositionGrid.THIRDS
        settings.goldenGrid = true
        assertEquals(CompositionGrid.GOLDEN, settings().compositionGrid)
        settings.goldenGrid = false
        assertEquals(CompositionGrid.OFF, settings().compositionGrid)
        assertFalse(prefs.getBoolean("goldenGrid", true))
    }

    @Test fun choosingGridDoesNotChangeUnrelatedSettings() {
        val settings = settings()
        settings.volumeShutter = false
        settings.histogram = true
        settings.jpegQuality = 90
        settings.compositionGrid = CompositionGrid.THIRDS
        val reopened = settings()
        assertFalse(reopened.volumeShutter)
        assertTrue(reopened.histogram)
        assertEquals(90, reopened.jpegQuality)
        assertEquals(CompositionGrid.THIRDS, reopened.compositionGrid)
    }
}
