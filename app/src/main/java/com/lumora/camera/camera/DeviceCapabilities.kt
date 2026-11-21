package com.lumora.camera.camera

data class IntRangeValue(val min: Int, val max: Int) {
    init { require(max >= min) }
    fun clamp(value: Int): Int = value.coerceIn(min, max)
    override fun toString(): String = "$min-$max"
}

data class LongRangeValue(val min: Long, val max: Long) {
    init { require(min >= 0L && max >= min) }
    fun clamp(value: Long): Long = value.coerceIn(min, max)
    override fun toString(): String = "$min-$max"
}

data class LensOption(
    val id: String,
    val facing: String,
    val focalLengthsMm: List<Float>,
    val calibratedMaxDiopters: Float? = null
)

/** Prefer a genuinely shorter calibrated focus distance among openable same-facing cameras. */
internal fun closestFocusLens(current: LensOption, choices: List<LensOption>): LensOption {
    val currentLimit = current.calibratedMaxDiopters ?: 0f
    return choices.filter {
        it.facing == current.facing && it.focalLengthsMm.isNotEmpty() &&
            (it.calibratedMaxDiopters ?: 0f) > currentLimit
    }.maxByOrNull { requireNotNull(it.calibratedMaxDiopters) } ?: current
}

internal fun preferredLens(id: String?, lenses: List<LensOption>): LensOption? =
    lenses.firstOrNull { it.id == id } ?: lenses.firstOrNull { it.facing == "BACK" } ?: lenses.firstOrNull()

internal fun portraitAfterRotation(streamWidth: Int, streamHeight: Int, degrees: Int): Boolean =
    if (degrees % 180 == 0) streamHeight > streamWidth else streamWidth > streamHeight

enum class RawAvailability(val unavailableReason: String?) {
    AVAILABLE(null),
    UNSUPPORTED("当前镜头不支持 RAW / DNG 输出"),
    SESSION_UNAVAILABLE("当前镜头无法建立 RAW / DNG 拍摄会话")
}

data class DeviceCapabilities(
    val cameraId: String,
    val lensFacing: String,
    val focalLengthsMm: List<Float> = emptyList(),
    val jpegSizes: List<Pair<Int, Int>> = emptyList(),
    val supportsFlash: Boolean = false,
    val supportsAutoFocus: Boolean = false,
    val zoomMin: Float? = null,
    val zoomMax: Float? = null,
    val exposureCompensationRange: IntRangeValue? = null,
    val exposureCompensationStep: Float? = null,
    val isoRange: IntRangeValue? = null,
    val exposureTimeRangeNs: LongRangeValue? = null,
    val minimumFocusDistance: Float? = null,
    val supportsRaw: Boolean = false,
    val supportsOpticalStabilization: Boolean = false,
    val hdrExtensionAvailable: Boolean = false,
    val nightExtensionAvailable: Boolean = false,
    val portraitExtensionAvailable: Boolean = false,
    val availableCameras: List<LensOption> = emptyList(),
    val hardwareLevel: String? = null,
    val supportsVideoStabilization: Boolean = false,
    val manualSensorAvailable: Boolean = false,
    val supportsManualFocus: Boolean = false,
    val whiteBalanceModes: List<Int> = emptyList(),
    val supportsAeLock: Boolean = false,
    val supportsAwbLock: Boolean = false,
    val physicalCameraIds: List<String> = emptyList(),
    val focusDistanceInDiopters: Boolean = false,
    val rawAvailability: RawAvailability = RawAvailability.UNSUPPORTED
) {
    val supportsManualExposure: Boolean
        get() = manualSensorAvailable && isoRange != null && isoRange.min > 0 &&
            exposureTimeRangeNs != null && exposureTimeRangeNs.min > 0

    fun clampIso(value: Int): Int? = isoRange?.clamp(value)
    fun clampExposureTimeNs(value: Long): Long? = exposureTimeRangeNs?.clamp(value)
    fun isoSummary(): String = isoRange?.toString() ?: "不可用"
    fun exposureSummary(): String = exposureTimeRangeNs?.let { "${it.min}-${it.max}ns" } ?: "不可用"
}
