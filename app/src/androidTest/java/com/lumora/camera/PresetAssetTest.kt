package com.lumora.camera

import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.FilmPresets
import org.junit.Assert.*
import org.junit.Test

class PresetAssetTest {
    @Test fun installedAssetProfilesAreFiniteDistinctAndUsable() {
        val presets = FilmPresets.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(20, presets.map { it.id }.distinct().size)
        val profiles = presets.map { preset ->
            assertTrue(preset.name.isNotBlank() && preset.subtitle.isNotBlank())
            listOf(preset.temperature, preset.tint, preset.saturation, preset.contrast,
                preset.highlights, preset.shadows, preset.blacks, preset.whites,
                preset.grain, preset.vignette, preset.sharpness, preset.red, preset.green, preset.blue
            ).also { assertTrue(preset.id, it.all(Float::isFinite)) }
        }
        assertEquals(20, profiles.distinct().size)
    }
}
