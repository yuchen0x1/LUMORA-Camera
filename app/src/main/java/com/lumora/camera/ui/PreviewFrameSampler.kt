package com.lumora.camera.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.TextureView
import androidx.camera.view.PreviewView
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.image.FilmColorEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class AidFrame(val style: Bitmap?, val marks: Bitmap?, val histogram: IntArray?)
internal data class PreviewSample(
    val buffer: Bitmap, val intoViewport: Matrix, val width: Int, val height: Int,
    val texture: TextureView, val timeNanos: Long
)

private fun boundedDimension(value: Int, scale: Float) = (value * scale).roundToInt().coerceAtLeast(1)

internal fun TextureView.mapReadbackToViewport(
    readback: Bitmap, preview: PreviewView, outputWidth: Int, outputHeight: Int
): Matrix = Matrix().apply {
    val texture = this@mapReadbackToViewport
    setScale(texture.width.toFloat() / readback.width, texture.height.toFloat() / readback.height)
    // CameraX 1.6.2's createTransformedBitmap applies this correction after getBitmap().
    // Readback preserves the producer's transform but omits TextureView's own layer matrix.
    postConcat(texture.getTransform(null))
    postConcat(texture.matrix)
    postTranslate(texture.left.toFloat(), texture.top.toFloat())
    postScale(outputWidth.toFloat() / preview.width, outputHeight.toFloat() / preview.height)
}

/** One producer and one consumer, synchronously handed off before the next texture readback. */
internal class PreviewFrameSampler(private val longestSide: Int) : AutoCloseable {
    private var readback: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Read back only the CameraX texture, never the surrounding Compose controls or overlays.
    // The readback API is UI-thread bound. Crop, rotation, analysis and film processing run off it.
    fun read(preview: PreviewView): PreviewSample? {
        if (preview.previewStreamState.value != PreviewView.StreamState.STREAMING) return null
        val texture = (0 until preview.childCount).firstNotNullOfOrNull { preview.getChildAt(it) as? TextureView }
            ?: return null
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0 ||
            preview.width <= 0 || preview.height <= 0) return null
        val timeNanos = texture.surfaceTexture?.timestamp ?: return null
        if (timeNanos <= 0L) return null
        val sourceScale = min(1f, longestSide.toFloat() / max(texture.width, texture.height))
        val sourceWidth = boundedDimension(texture.width, sourceScale)
        val sourceHeight = boundedDimension(texture.height, sourceScale)
        val reusable = readback
        val buffer = if (reusable != null && reusable.width == sourceWidth && reusable.height == sourceHeight) {
            reusable
        } else {
            reusable?.recycle()
            Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).also { readback = it }
        }
        // The platform leaves the destination unchanged on readback failure.
        buffer.eraseColor(android.graphics.Color.TRANSPARENT)
        texture.getBitmap(buffer)
        if (buffer.getPixel(sourceWidth / 2, sourceHeight / 2) ushr 24 == 0) return null

        val viewScale = min(1f, longestSide.toFloat() / max(preview.width, preview.height))
        val width = boundedDimension(preview.width, viewScale)
        val height = boundedDimension(preview.height, viewScale)
        val transform = texture.mapReadbackToViewport(buffer, preview, width, height)
        return PreviewSample(buffer, transform, width, height, texture, timeNanos)
    }

    fun render(
        sample: PreviewSample, aids: PhotoAids, preset: FilmPreset,
        strength: OptimizationStrength, intensity: Float
    ): AidFrame {
        val aligned = Bitmap.createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)
        var published = false
        try {
            Canvas(aligned).drawBitmap(sample.buffer, sample.intoViewport, paint)
            val analysis = if (aids.histogram || aids.clipping || aids.peaking) {
                val pixels = IntArray(aligned.width * aligned.height)
                aligned.getPixels(pixels, 0, aligned.width, 0, 0, aligned.width, aligned.height)
                analyzePreview(pixels, aligned.width, aligned.height, aids)
            } else null
            val marks = analysis?.overlayPixels?.let {
                Bitmap.createBitmap(it, aligned.width, aligned.height, Bitmap.Config.ARGB_8888)
            }
            val style = if (aids.styledPreview) {
                FilmColorEngine.applyInPlace(aligned, preset, strength, intensity = intensity)
                aligned
            } else null
            published = style != null
            return AidFrame(style, marks, analysis?.bins)
        } finally {
            if (!published) aligned.recycle()
        }
    }

    override fun close() {
        readback?.recycle()
        readback = null
    }
}
