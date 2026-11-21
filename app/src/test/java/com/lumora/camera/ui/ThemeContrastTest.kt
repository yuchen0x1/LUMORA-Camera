package com.lumora.camera.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun lightTextAndControlsMeetContrastContract() = checkScheme(LightColors)
    @Test fun darkTextAndControlsMeetContrastContract() = checkScheme(DarkColors)

    @Test fun viewfinderOverlayRemainsReadableOverBrightScenes() {
        val panel = ViewfinderTokens.panel.compositeOver(Color.White)
        assertRatio("overlay text", ViewfinderTokens.ink, panel, 4.5)
        assertRatio("overlay secondary", ViewfinderTokens.muted, panel, 4.5)
        assertRatio("overlay accent", ViewfinderTokens.accent, panel, 4.5)
        assertRatio("system icons", Color.White, ViewfinderTokens.scrim.compositeOver(Color.White), 4.5)
    }

    private fun checkScheme(c: ColorScheme) {
        listOf(c.background, c.surface, c.surfaceContainerLow, c.surfaceContainer,
            c.surfaceContainerHigh, c.surfaceContainerHighest).forEach { surface ->
            assertRatio("body", c.onSurface, surface, 4.5)
            assertRatio("secondary text", c.onSurfaceVariant, surface, 4.5)
            assertRatio("interactive text", c.primary, surface, 4.5)
            assertRatio("error text", c.error, surface, 4.5)
            assertRatio("control outline", c.outline, surface, 3.0)
        }
        listOf(c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
            c.onSecondary to c.secondary, c.onSecondaryContainer to c.secondaryContainer,
            c.onTertiary to c.tertiary, c.onTertiaryContainer to c.tertiaryContainer,
            c.onError to c.error, c.onErrorContainer to c.errorContainer,
            c.inverseOnSurface to c.inverseSurface).forEach { (text, surface) ->
            assertRatio("semantic pair", text, surface, 4.5)
        }
        assertTrue("Surface tint must follow the brand, not Material's default purple", c.surfaceTint == c.primary)
    }

    private fun assertRatio(name: String, foreground: Color, background: Color, minimum: Double) {
        val a = foreground.luminance().toDouble()
        val b = background.luminance().toDouble()
        val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
        assertTrue("$name contrast $ratio < $minimum", ratio >= minimum)
    }
}
