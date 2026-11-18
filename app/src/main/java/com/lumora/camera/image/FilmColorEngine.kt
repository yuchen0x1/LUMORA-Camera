package com.lumora.camera.image

import android.graphics.Bitmap
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.OptimizationStrength
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object FilmColorEngine {
    /** Returns an independent rendering. The caller retains its unmodified source. */
    fun apply(
        bitmap: Bitmap, preset: FilmPreset, strength: OptimizationStrength,
        edits: PhotoEditParameters = PhotoEditParameters.NATURAL, intensity: Float = 1f
    ): Bitmap {
        val output = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true))
        try {
            return applyInPlace(output, preset, strength, edits, intensity)
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    /** The caller exclusively owns this mutable bitmap. All scratch space is O(width). */
    fun applyInPlace(
        bitmap: Bitmap, preset: FilmPreset, strength: OptimizationStrength,
        edits: PhotoEditParameters = PhotoEditParameters.NATURAL, intensity: Float = 1f
    ): Bitmap {
        require(bitmap.isMutable)
        val width = bitmap.width
        val height = bitmap.height
        val level = strength.factor
        val mix = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 1f
        val style = mix * level
        val scene = analyze(bitmap)
        val colorMap = PresetColorMap.forStyle(preset, style)
        val gain = 2f.pow(edits.exposure) * (1f + scene.exposure * level)
        val contrast = (1f + (preset.contrast - 1f) * style) * (1f + edits.contrast * 0.8f)
        val saturation = ((1f + (preset.saturation - 1f) *
            (if (preset.saturation == 0f) mix else style)) * (1f + edits.saturation)).coerceAtLeast(0f)
        val warm = preset.temperature * style + edits.temperature
        val tint = preset.tint * style + edits.tint
        val rBias = scene.redBalance * level + (warm * 0.12f + tint * 0.04f + preset.red * style) * 255f
        val gBias = (-tint * 0.09f + preset.green * style) * 255f
        val bBias = scene.blueBalance * level + (-warm * 0.12f + tint * 0.04f + preset.blue * style) * 255f
        val highlights = preset.highlights * style + edits.highlights * 0.45f - scene.highlightProtection * level
        val shadows = preset.shadows * style + edits.shadows * 0.45f + scene.shadowLift * level
        val tone = FloatArray(256) { code ->
            val luma = code / 255f
            val shadowWeight = (1f - 2f * luma).coerceAtLeast(0f)
            val highlightWeight = (2f * luma - 1f).coerceAtLeast(0f)
            val blackWeight = shadowWeight * shadowWeight
            val whiteWeight = highlightWeight * highlightWeight
            127.5f * (1f - contrast) + 255f * (shadows * shadowWeight + highlights * highlightWeight +
                style * (preset.blacks * blackWeight + preset.whites * whiteWeight))
        }
        val scale = gain * contrast
        val sharp = (preset.sharpness * style + edits.sharpening).coerceAtMost(1.6f)
        val grain = (preset.grain * style + edits.grain * 0.22f)
        val vignette = (preset.vignette * style + edits.vignette * 0.80f).coerceAtMost(0.96f)
        val centerX = (width - 1) / 2f
        val centerY = (height - 1) / 2f
        val radialScale = vignette / max(1f, centerX * centerX + centerY * centerY)
        val radialX = FloatArray(width) { x -> (x - centerX) * (x - centerX) * radialScale }
        var previous = IntArray(width)
        var current = IntArray(width)
        var next = IntArray(width)
        val output = IntArray(width)
        bitmap.getPixels(current, 0, width, 0, 0, width, 1)
        current.copyInto(previous)

        // Keep original rows in the ring even after writing transformed rows back to the bitmap.
        for (y in 0 until height) {
            if (y + 1 < height) bitmap.getPixels(next, 0, width, 0, y + 1, width, 1)
            else current.copyInto(next)
            val radialY = (y - centerY) * (y - centerY) * radialScale
            for (x in 0 until width) {
                val pixel = current[x]
                val red = red(pixel)
                val green = green(pixel)
                val blue = blue(pixel)
                val luma = luma(pixel)
                val left = current[max(0, x - 1)]
                val right = current[min(width - 1, x + 1)]
                val up = previous[x]
                val down = next[x]
                val leftLuma = luma(left)
                val rightLuma = luma(right)
                val upLuma = luma(up)
                val downLuma = luma(down)
                val surround = (leftLuma + rightLuma + upLuma + downLuma) * 0.25f
                val edge = luma - surround
                // A wider local contrast radius for detail, and a smaller edge kernel for sharpening.
                val detail = if (edits.detail > 0f) {
                    val wide = (luma(current[max(0, x - 3)]) + luma(current[min(width - 1, x + 3)]) +
                        luma(up) + luma(down)) * 0.25f
                    (luma - wide).coerceIn(-22f, 22f) * edits.detail * 1.2f
                } else 0f
                val gradient = max(abs(leftLuma - rightLuma), abs(upLuma - downLuma))
                // Boost moderate, coherent edges. Smooth dark texture only when the sampled frame is noisy.
                val midtone = (1f - abs(luma - 128f) / 116f).coerceAtLeast(0f)
                val autoDetail = midtone * min(gradient / 28f, 1f) * (1f - scene.noise) * level * 0.32f
                val lift = tone[luma] + detail + edge.coerceIn(-24f, 24f) * (sharp + autoDetail)
                val dark = (1f - luma / 120f).coerceAtLeast(0f)
                val edgeProtection = (1f - gradient / 32f).coerceAtLeast(0f)
                val autoDenoise = dark * edgeProtection * scene.noise * level
                val blend = max(edits.denoise, autoDenoise).coerceAtMost(1f) * (1f - abs(edge) / 48f).coerceAtLeast(0f)
                var r = red.toFloat()
                var g = green.toFloat()
                var b = blue.toFloat()
                if (blend > 0f) {
                    r += ((red(left) + red(right) + red(up) + red(down)) * 0.25f - r) * blend
                    g += ((green(left) + green(right) + green(up) + green(down)) * 0.25f - g) * blend
                    b += ((blue(left) + blue(right) + blue(up) + blue(down)) * 0.25f - b) * blend
                }
                r = r * scale + rBias + lift
                g = g * scale + gBias + lift
                b = b * scale + bBias + lift
                if (colorMap != null) {
                    val mapped = colorMap.map(channel(r), channel(g), channel(b))
                    r = red(mapped).toFloat()
                    g = green(mapped).toFloat()
                    b = blue(mapped).toFloat()
                }
                val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val falloff = 1f - radialX[x] - radialY
                val noise = if (grain > 0f) {
                    var hash = (x * 73856093) xor (y * 19349663)
                    hash = (hash xor (hash ushr 13)) * 1274126177
                    ((hash ushr 24) - 127.5f) * grain
                } else 0f
                val outRed = channel((gray + (r - gray) * saturation) * falloff + noise)
                val outGreen = channel((gray + (g - gray) * saturation) * falloff + noise)
                val outBlue = channel((gray + (b - gray) * saturation) * falloff + noise)
                output[x] = (pixel and -0x1000000) or (outRed shl 16) or (outGreen shl 8) or outBlue
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1)
            val spare = previous
            previous = current
            current = next
            next = spare
        }
        return bitmap
    }

    private data class Scene(
        val exposure: Float, val redBalance: Float, val blueBalance: Float,
        val highlightProtection: Float, val shadowLift: Float, val noise: Float
    )

    private fun analyze(bitmap: Bitmap): Scene {
        val histogram = IntArray(256)
        val columns = min(48, bitmap.width)
        val rows = min(36, bitmap.height)
        var redCast = 0
        var blueCast = 0
        var neutrals = 0
        var darkSamples = 0
        var darkResidual = 0
        for (sy in 0 until rows) {
            val y = ((sy + 0.5) * bitmap.height / rows).toInt()
            for (sx in 0 until columns) {
                val x = ((sx + 0.5) * bitmap.width / columns).toInt()
                val pixel = bitmap.getPixel(x, y)
                val r = red(pixel)
                val g = green(pixel)
                val b = blue(pixel)
                val luma = luma(pixel)
                histogram[luma]++
                if (luma < 110 && x > 0 && x < bitmap.width - 1) {
                    val left = luma(bitmap.getPixel(x - 1, y))
                    val right = luma(bitmap.getPixel(x + 1, y))
                    if (abs(left - right) < 20 && abs(luma - left) < 25 && abs(luma - right) < 25) {
                        darkResidual += abs(2 * luma - left - right)
                        darkSamples++
                    }
                }
                if (luma in 32..222 && max(r, max(g, b)) - min(r, min(g, b)) < 50) {
                    redCast += g - r
                    blueCast += g - b
                    neutrals++
                }
            }
        }
        val count = columns * rows
        var sum = 0
        var median = 0
        for (i in histogram.indices) {
            sum += histogram[i]
            if (sum * 2 >= count) { median = i; break }
        }
        val balance = if (neutrals >= max(1, count / 12)) 0.40f / neutrals else 0f
        return Scene(
            ((128 - median) / 360f).coerceIn(-0.16f, 0.28f),
            (redCast * balance).coerceIn(-9f, 9f), (blueCast * balance).coerceIn(-9f, 9f),
            histogram.sliceArray(220..255).sum() * 0.08f / count,
            histogram.sliceArray(0..55).sum() * 0.06f / count,
            if (darkSamples < max(1, count / 32)) 0f else
                ((darkResidual / (2f * darkSamples) - 1f) / 15f).coerceIn(0f, 0.75f)
        )
    }

    private fun red(pixel: Int) = pixel ushr 16 and 255
    private fun green(pixel: Int) = pixel ushr 8 and 255
    private fun blue(pixel: Int) = pixel and 255
    private fun luma(pixel: Int) = (54 * red(pixel) + 183 * green(pixel) + 19 * blue(pixel) + 128) ushr 8
    private fun channel(value: Float) = value.roundToInt().coerceIn(0, 255)
}
