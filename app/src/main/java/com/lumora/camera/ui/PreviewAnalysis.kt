package com.lumora.camera.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

enum class CompositionGrid(val label: String, val fraction: Float) {
    OFF("关闭", 0f), THIRDS("三分网格", 1f / 3f), GOLDEN("黄金分割", 0.381966f)
}

data class PhotoAids(
    val grid: CompositionGrid = CompositionGrid.OFF,
    val level: Boolean = false,
    val histogram: Boolean = false,
    val clipping: Boolean = false,
    val peaking: Boolean = false,
    val styledPreview: Boolean = false
) {
    internal val samplesFrames get() = histogram || clipping || peaking || styledPreview
}

internal data class PreviewAnalysis(val bins: IntArray?, val overlayPixels: IntArray?)

/** Works on the unstyled visible crop, so moving a film slider does not move the clip threshold. */
internal fun analyzePreview(pixels: IntArray, width: Int, height: Int, aids: PhotoAids): PreviewAnalysis {
    val bins = if (aids.histogram) IntArray(64) else null
    val overlay = if (aids.clipping || aids.peaking) IntArray(pixels.size) else null
    val luma = if (aids.peaking) IntArray(pixels.size) else null
    for (i in pixels.indices) {
        val color = pixels[i]
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        val y = (54 * r + 183 * g + 19 * b + 128) ushr 8
        luma?.set(i, y)
        if (bins != null) bins[y ushr 2]++
        if (aids.clipping && (i % width + i / width) % 8 < 3) {
            // Amber: at least one channel is near saturation. Blue: all channels near black.
            if (max(r, max(g, b)) >= 250) overlay!![i] = 0xDDFCB236.toInt()
            else if (max(r, max(g, b)) <= 5) overlay!![i] = 0xDD5CADFF.toInt()
        }
    }
    if (luma != null && overlay != null) {
        for (row in 1 until height - 1) {
            for (column in 1 until width - 1) {
                val i = row * width + column
                if (overlay[i] != 0) continue
                val gx = luma[i - width + 1] + 2 * luma[i + 1] + luma[i + width + 1] -
                    luma[i - width - 1] - 2 * luma[i - 1] - luma[i + width - 1]
                val gy = luma[i + width - 1] + 2 * luma[i + width] + luma[i + width + 1] -
                    luma[i - width - 1] - 2 * luma[i - width] - luma[i - width + 1]
                if (abs(gx) + abs(gy) > 235) overlay[i] = 0xDF50FF92.toInt()
            }
        }
    }
    return PreviewAnalysis(bins, overlay)
}

/** Horizon in screen coordinates. Gravity parallel to the optical axis gives no reliable roll. */
internal fun horizonAngle(gravityX: Float, gravityY: Float, displayQuarterTurns: Int): Float? {
    if (gravityX * gravityX + gravityY * gravityY < 1.5f * 1.5f) return null
    val angle = Math.toDegrees(atan2(gravityX.toDouble(), gravityY.toDouble())).toFloat()
    return ((angle - displayQuarterTurns * 90f + 630f) % 180f) - 90f
}
