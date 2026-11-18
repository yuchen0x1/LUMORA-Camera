package com.lumora.camera.image

import com.lumora.camera.data.ChannelCurve
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.RgbCurves
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A 17³ RGB lattice shared by previews and exports. Interpolate inside one tetrahedron per pixel. */
internal class PresetColorMap private constructor(preset: FilmPreset, strength: Float) {
    private val grid = IntArray(17 * 17 * 17)
    private val coordinates = IntArray(256) { (it * 256 + 127) / 255 }

    init {
        fun curve(index: Int, curve: ChannelCurve): Float {
            val value = index / 16f
            val low = (1f - abs(value * 3f - 1f)).coerceAtLeast(0f)
            val high = (1f - abs(value * 3f - 2f)).coerceAtLeast(0f)
            return (value + (low * curve.lowerMid + high * curve.upperMid) * strength).coerceIn(0f, 1f)
        }
        for (r in 0..16) for (g in 0..16) for (b in 0..16) {
            grid[r * 289 + g * 17 + b] = mapHue(curve(r, preset.curves.red), curve(g, preset.curves.green),
                curve(b, preset.curves.blue), preset, strength)
        }
    }

    private fun mapHue(r: Float, g: Float, b: Float, preset: FilmPreset, strength: Float): Int {
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val chroma = high - low
        if (chroma < 0.0001f) return pack(r, g, b)
        val light = (high + low) / 2
        val hue = ((when (high) {
            r -> (g - b) / chroma
            g -> (b - r) / chroma + 2
            else -> (r - g) / chroma + 4
        }) * 60 + 360) % 360
        var hueDelta = 0f
        var saturationDelta = 0f
        var lightDelta = 0f
        for (range in preset.hueAdjustments) {
            val distance = abs(hue - range.center)
            val proximity = (1f - min(distance, 360f - distance) / range.radius).coerceAtLeast(0f)
            val weight = proximity * proximity * (3 - 2 * proximity) * strength
            hueDelta += weight * range.hueShift
            saturationDelta += weight * range.saturationShift
            // Fade targeted lightness changes near the neutral axis.
            lightDelta += weight * range.lightnessShift * min(1f, chroma * 5)
        }
        if (hueDelta == 0f && saturationDelta == 0f && lightDelta == 0f) return pack(r, g, b)
        val newLight = (light + lightDelta).coerceIn(0f, 1f)
        val saturation = (chroma / (1 - abs(2 * light - 1)) * (1 + saturationDelta)).coerceIn(0f, 1f)
        val newChroma = (1 - abs(2 * newLight - 1)) * saturation
        val sector = ((hue + hueDelta + 360) % 360) / 60
        val mid = newChroma * (1 - abs(sector % 2 - 1))
        val base = newLight - newChroma / 2
        return when (sector.toInt()) {
            0 -> pack(base + newChroma, base + mid, base)
            1 -> pack(base + mid, base + newChroma, base)
            2 -> pack(base, base + newChroma, base + mid)
            3 -> pack(base, base + mid, base + newChroma)
            4 -> pack(base + mid, base, base + newChroma)
            else -> pack(base + newChroma, base, base + mid)
        }
    }

    private fun pack(r: Float, g: Float, b: Float) =
        ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()

    fun map(r: Int, g: Int, b: Int): Int {
        val red = coordinates[r]
        val green = coordinates[g]
        val blue = coordinates[b]
        val ri = min(red, 255) ushr 4
        val gi = min(green, 255) ushr 4
        val bi = min(blue, 255) ushr 4
        val index = ri * 289 + gi * 17 + bi
        val rf = red - ri * 16
        val gf = green - gi * 16
        val bf = blue - bi * 16
        return if (rf >= gf) {
            when {
                gf >= bf -> interpolate(index, 289, 17, rf, gf, bf)
                rf >= bf -> interpolate(index, 289, 1, rf, bf, gf)
                else -> interpolate(index, 1, 289, bf, rf, gf)
            }
        } else {
            when {
                rf >= bf -> interpolate(index, 17, 289, gf, rf, bf)
                gf >= bf -> interpolate(index, 17, 1, gf, bf, rf)
                else -> interpolate(index, 1, 17, bf, gf, rf)
            }
        }
    }

    private fun interpolate(i: Int, first: Int, second: Int, high: Int, mid: Int, low: Int): Int {
        val a = grid[i]
        val b = grid[i + first]
        val c = grid[i + first + second]
        val d = grid[i + 307]
        val wa = 16 - high
        val wb = high - mid
        val wc = mid - low
        // Four fixed-point weights sum to 16; red and blue can accumulate in separate bit lanes.
        val rb = (((a and 0xff00ff) * wa + (b and 0xff00ff) * wb +
            (c and 0xff00ff) * wc + (d and 0xff00ff) * low + 0x80008) ushr 4) and 0xff00ff
        val green = (((a and 0xff00) * wa + (b and 0xff00) * wb +
            (c and 0xff00) * wc + (d and 0xff00) * low + 0x800) ushr 4) and 0xff00
        return rb or green
    }

    companion object {
        private val identity = RgbCurves()
        private val cache = object : LinkedHashMap<Pair<FilmPreset, Float>, PresetColorMap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<FilmPreset, Float>, PresetColorMap>?) = size > 8
        }

        @Synchronized
        fun forStyle(preset: FilmPreset, strength: Float): PresetColorMap? {
            if (strength == 0f || (preset.curves == identity && preset.hueAdjustments.isEmpty())) return null
            return cache.getOrPut(preset to strength) { PresetColorMap(preset, strength) }
        }
    }
}
