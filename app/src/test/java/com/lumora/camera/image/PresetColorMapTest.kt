package com.lumora.camera.image

import com.lumora.camera.data.ChannelCurve
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.HueAdjustment
import com.lumora.camera.data.RgbCurves
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PresetColorMapTest {
    private val base = FilmPreset("test", "", "", 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    @Test fun channelKnotsAdjustRedIndependentlyAndRespectIntensity() {
        val style = base.copy(curves = RgbCurves(red = ChannelCurve(0.10f, -0.04f)))
        assertNull(PresetColorMap.forStyle(style, 0f))
        val lut = requireNotNull(PresetColorMap.forStyle(style, 1f))
        val atLowerMid = lut.map(85, 85, 85)
        val atUpperMid = lut.map(170, 170, 170)
        assertTrue((atLowerMid ushr 16) > 105)
        assertTrue((atUpperMid ushr 16) < 164)
        assertTrue(abs((atLowerMid and 255) - 85) <= 1)
        assertTrue(abs(((atLowerMid ushr 8) and 255) - 85) <= 1)
    }

    @Test fun circularHueRangeModifiesHueSaturationAndLightnessSelectively() {
        fun mapped(h: Float, s: Float, l: Float, r: Int = 180, g: Int = 76, b: Int = 76): Int {
            val style = base.copy(hueAdjustments = listOf(HueAdjustment(355f, 50f, h, s, l)))
            return requireNotNull(PresetColorMap.forStyle(style, 1f)).map(r, g, b)
        }
        val shifted = mapped(25f, 0f, 0f)
        assertTrue(((shifted ushr 8) and 255) > 110)
        val muted = mapped(0f, -0.65f, 0f)
        assertTrue((muted ushr 16) < 158 && (muted and 255) > 95)
        val lifted = mapped(0f, 0f, 0.10f)
        assertTrue((lifted ushr 16) > 194 && (lifted and 255) > 92)
        val unrelated = mapped(25f, -0.65f, 0.1f, 76, 76, 180)
        assertTrue(abs((unrelated ushr 16) - 76) <= 1)
        assertTrue(abs(((unrelated ushr 8) and 255) - 76) <= 1)
        assertTrue(abs((unrelated and 255) - 180) <= 1)
    }
}
