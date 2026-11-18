package com.lumora.camera.camera

data class CaptureQualityRecord(
    val width: Int,
    val height: Int,
    val bytes: Long,
    val mimeType: String,
    val cameraId: String?,
    val lensFacing: String?,
    val mode: String,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val focalLengthMm: Float?,
    val focusDistanceM: Float?,
    val processingMillis: Long?,
    val outputWidth: Int?,
    val outputHeight: Int?,
    val outputBytes: Long? = null,
    val outputMimeType: String? = null,
    val jpegQuality: Int? = null
) {
    fun toDiagnosticLine(): String = buildString {
        append("capture input=${width}x$height bytes=${bytes.takeIf { it >= 0 } ?: "unavailable"} mime=$mimeType")
        append(" camera=${cameraId ?: "unavailable"}")
        append(" lens=${lensFacing ?: "unavailable"} mode=$mode")
        append(" iso=${iso ?: "unavailable"}")
        append(" exposureNs=${exposureTimeNs ?: "unavailable"}")
        append(" focalMm=${focalLengthMm ?: "unavailable"}")
        append(" focusM=${focusDistanceM ?: "unavailable"}")
        append(" processingMs=${processingMillis ?: "unavailable"}")
        append(" output=${outputWidth?.toString() ?: "unavailable"}x${outputHeight?.toString() ?: "unavailable"}")

        append(" outputBytes=${outputBytes ?: "unavailable"} outputMime=${outputMimeType ?: "unavailable"}")
        append(" jpegQuality=${jpegQuality ?: "unavailable"}")
    }
}
