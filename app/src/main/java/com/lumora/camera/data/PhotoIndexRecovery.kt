package com.lumora.camera.data

import android.content.ContentResolver
import android.content.SharedPreferences
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader

/** Counts only; observing this state never opens media or changes the index. */
data class PhotoIndexRecoveryStatus(
    val backupCount: Int = 0,
    val currentIndexDamaged: Boolean = false,
    val writeUnconfirmed: Boolean = false
) {
    val hasEvidence: Boolean get() = backupCount > 0 || currentIndexDamaged || writeUnconfirmed
}

internal data class PhotoIndexSnapshot(val key: String, val raw: String?)

/** An in-memory review, deliberately not saved in an instance-state Bundle or exported. */
class PhotoIndexRecoveryPreview internal constructor(
    val backupCount: Int,
    val currentIndexDamaged: Boolean,
    val malformedSnapshotCount: Int,
    val invalidEntryCount: Int,
    val duplicateEntryCount: Int,
    val alreadyPresentCount: Int,
    val conflictingRecordCount: Int,
    val unreadableMediaCount: Int,
    val records: List<PhotoRecord>,
    val writeUnconfirmed: Boolean,
    internal val library: SharedPreferences,
    internal val snapshots: List<PhotoIndexSnapshot>
)

enum class PhotoIndexRecoveryOutcome { RESTORED, NOTHING_NEW, STALE_PREVIEW, WRITE_NOT_CONFIRMED, WRITE_CONFIRMED }

data class PhotoIndexRecoveryResult(
    val outcome: PhotoIndexRecoveryOutcome,
    val restoredCount: Int = 0,
    val alreadyPresentCount: Int = 0,
    val unreadableMediaCount: Int = 0
)

internal data class InspectedPhotoIndex(
    val records: List<PhotoRecord> = emptyList(),
    val invalidEntryCount: Int = 0,
    val malformed: Boolean = false
) {
    val damaged: Boolean get() = malformed || invalidEntryCount > 0
}

/**
 * Recovery is intentionally stricter than the legacy, read-only gallery decoder. It never
 * guesses objects out of truncated text, coerces field types, or silently repairs a recipe.
 * Rejected text stays in its original snapshot for a future, explicitly reviewed recovery.
 */
internal object PhotoIndexRecovery {
    fun inspect(
        raw: String?,
        decode: (JSONObject) -> PhotoRecord?,
        requireSupportedUris: Boolean = true
    ): InspectedPhotoIndex {
        val array = strictArray(raw) ?: return InspectedPhotoIndex(malformed = true)
        var invalid = 0
        val records = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null || !validFields(item, requireSupportedUris)) {
                    invalid++
                    continue
                }
                val record = decode(item)
                if (record == null) invalid++ else add(record)
            }
        }
        return InspectedPhotoIndex(records, invalid)
    }

    private fun strictArray(raw: String?): JSONArray? {
        if (raw == null) return null
        return try {
            // Android's JSONObject/JSONArray parser also accepts truncated/trailing and
            // non-JSON syntax. Validate the whole document before using that decoder.
            JsonReader(StringReader(raw)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) return null
            }
            JSONArray(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun validFields(item: JSONObject, requireSupportedUris: Boolean): Boolean {
        fun optionalString(key: String, nullable: Boolean = false): Boolean =
            !item.has(key) || (nullable && item.isNull(key)) ||
                (item.opt(key) is String && (item.opt(key) as String).isNotBlank())
        fun optionalBoolean(key: String): Boolean = !item.has(key) || item.opt(key) is Boolean
        fun wholeTime(key: String): Boolean {
            val value = item.opt(key) as? Number ?: return false
            if (value is Long || value is Int) return value.toLong() >= 0L
            val number = value.toDouble()
            return number.isFinite() && number >= 0.0 && number < Long.MAX_VALUE.toDouble() &&
                value.toLong().toDouble() == number
        }
        fun boundedNumber(json: JSONObject, key: String, min: Double, max: Double): Boolean {
            if (!json.has(key)) return true
            val value = (json.opt(key) as? Number)?.toDouble() ?: return false
            return value.isFinite() && value in min..max
        }
        if (!optionalString("originalUri") || !optionalString("enhancedUri")) return false
        val enhanced = item.optString("enhancedUri").ifBlank { item.optString("originalUri") }
        val original = item.optString("originalUri").ifBlank { enhanced }
        if (enhanced.isBlank() || original.isBlank()) return false
        if (requireSupportedUris && (!supportedUri(original) || !supportedUri(enhanced))) return false
        if (!wholeTime("createdAt") || (item.has("capturedAt") && !wholeTime("capturedAt"))) return false
        if (!optionalString("styleId") || !optionalString("parentUri", nullable = true) ||
            !optionalString("diagnostics", nullable = true)) return false
        if (requireSupportedUris && !item.isNull("parentUri") && item.has("parentUri") &&
            !supportedUri(item.getString("parentUri"))) return false
        if (item.has("strength") && OptimizationStrength.entries.none { it.name == item.opt("strength") }) return false
        if (!boundedNumber(item, "intensity", 0.0, 1.0)) return false
        if (listOf("favorite", "sourceOnly", "imported", "sourceIsRendered", "recipeKnown")
                .any { !optionalBoolean(it) }) return false
        if (item.optBoolean("sourceOnly") && original != enhanced) return false
        if (item.has("edits") && !item.isNull("edits")) {
            val edits = item.optJSONObject("edits") ?: return false
            if (!boundedNumber(edits, "exposure", -2.0, 2.0)) return false
            if (listOf("contrast", "highlights", "shadows", "temperature", "tint", "saturation")
                    .any { !boundedNumber(edits, it, -1.0, 1.0) }) return false
            if (listOf("detail", "sharpening", "denoise", "grain", "vignette")
                    .any { !boundedNumber(edits, it, 0.0, 1.0) }) return false
        }
        return true
    }

    private fun supportedUri(value: String): Boolean = try {
        val uri = Uri.parse(value)
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> !uri.authority.isNullOrBlank() && !uri.path.isNullOrBlank()
            ContentResolver.SCHEME_FILE -> uri.authority.isNullOrEmpty() && uri.path?.startsWith("/") == true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /** A placeholder and its initial render are the same capture, not two versions. */
    fun conflicts(left: PhotoRecord, right: PhotoRecord): Boolean =
        left.enhancedUri == right.enhancedUri ||
            (left.originalUri == right.originalUri && left.capturedAt == right.capturedAt &&
                left.parentUri == null && right.parentUri == null && (left.sourceOnly || right.sourceOnly))

    /**
     * A small, non-partial decode checks actual readability, not just a MIME label or query.
     * It cannot promise that a later full-resolution render will fit the device's memory.
     * Opening a cloud-backed URI may ask its provider to fetch data; nothing is uploaded here.
     */
    fun readableMedia(resolver: ContentResolver, value: String): Boolean {
        if (!supportedUri(value)) return false
        return try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, Uri.parse(value))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width <= 0 || height <= 0) throw IOException("Invalid image dimensions")
                val edge = maxOf(width, height).toLong()
                decoder.setTargetSize(
                    (width.toLong() * 64 / edge).toInt().coerceAtLeast(1),
                    (height.toLong() * 64 / edge).toInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
            try {
                bitmap.width > 0 && bitmap.height > 0
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }
}
