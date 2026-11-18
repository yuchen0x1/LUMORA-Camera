package com.lumora.camera.image

class PhotoEditParameters(
    exposure: Float = 0f,
    contrast: Float = 0f,
    highlights: Float = 0f,
    shadows: Float = 0f,
    temperature: Float = 0f,
    tint: Float = 0f,
    saturation: Float = 0f,
    detail: Float = 0f,
    sharpening: Float = 0f,
    denoise: Float = 0f,
    grain: Float = 0f,
    vignette: Float = 0f
) {
    val exposure = exposure.bounded(-2f, 2f)
    val contrast = contrast.bounded(-1f, 1f)
    val highlights = highlights.bounded(-1f, 1f)
    val shadows = shadows.bounded(-1f, 1f)
    val temperature = temperature.bounded(-1f, 1f)
    val tint = tint.bounded(-1f, 1f)
    val saturation = saturation.bounded(-1f, 1f)
    val detail = detail.bounded(0f, 1f)
    val sharpening = sharpening.bounded(0f, 1f)
    val denoise = denoise.bounded(0f, 1f)
    val grain = grain.bounded(0f, 1f)
    val vignette = vignette.bounded(0f, 1f)

    private fun Float.bounded(min: Float, max: Float): Float =
        if (isFinite() && this != 0f) coerceIn(min, max) else 0f

    fun copy(
        exposure: Float = this.exposure,
        contrast: Float = this.contrast,
        highlights: Float = this.highlights,
        shadows: Float = this.shadows,
        temperature: Float = this.temperature,
        tint: Float = this.tint,
        saturation: Float = this.saturation,
        detail: Float = this.detail,
        sharpening: Float = this.sharpening,
        denoise: Float = this.denoise,
        grain: Float = this.grain,
        vignette: Float = this.vignette
    ) = PhotoEditParameters(exposure, contrast, highlights, shadows, temperature, tint,
        saturation, detail, sharpening, denoise, grain, vignette)

    override fun equals(other: Any?): Boolean = other is PhotoEditParameters &&
        exposure == other.exposure && contrast == other.contrast && highlights == other.highlights &&
        shadows == other.shadows && temperature == other.temperature && tint == other.tint &&
        saturation == other.saturation && detail == other.detail && sharpening == other.sharpening &&
        denoise == other.denoise && grain == other.grain && vignette == other.vignette

    override fun hashCode(): Int = listOf(
        exposure, contrast, highlights, shadows, temperature, tint, saturation,
        detail, sharpening, denoise, grain, vignette
    ).fold(1) { result, value -> 31 * result + value.hashCode() }

    companion object {
        val NATURAL = PhotoEditParameters()
    }
}
