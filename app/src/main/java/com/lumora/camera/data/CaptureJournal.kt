package com.lumora.camera.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal data class PendingCapture(
    val id: String,
    val originalUri: String?,
    val styleId: String,
    val strength: String,
    val intensity: Float,
    val capturedAt: Long,
    val renderedUri: String? = null,
    val sourceIndexed: Boolean = false
)

internal enum class RecoveryOutcome { RECOVERED, ALREADY_INDEXED, MISSING, RETRY, ACTIVE }

/** Resolves interrupted work without deleting media or rendering the same shutter twice. */
internal object CaptureRecovery {
    fun reconcile(
        pending: List<PendingCapture>,
        isActive: (String) -> Boolean = { false },
        resolveSource: (PendingCapture) -> String?,
        recover: (PendingCapture) -> RecoveryOutcome,
        forget: (String) -> Unit
    ): List<RecoveryOutcome> = pending.map { entry ->
        if (isActive(entry.id)) return@map RecoveryOutcome.ACTIVE
        try {
            val source = resolveSource(entry)
            val outcome = if (source == null) RecoveryOutcome.MISSING
                else recover(entry.copy(originalUri = source))
            if (outcome != RecoveryOutcome.RETRY && outcome != RecoveryOutcome.ACTIVE) forget(entry.id)
            outcome
        } catch (_: Exception) {
            // A locked provider or failed index write is not proof that the photo disappeared.
            RecoveryOutcome.RETRY
        }
    }
}

/** Small synchronous journal. Call mutating methods on IO, before invoking the shutter. */
internal class CaptureJournal(context: Context, namespace: String = "lumora_capture_journal") {
    private val prefs = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    fun begin(id: String, styleId: String, strength: String, intensity: Float, capturedAt: Long) = mutate { array ->
        check((0 until array.length()).none { array.optJSONObject(it)?.optString("id") == id })
        array.put(JSONObject().apply {
            put("id", id)
            put("styleId", styleId)
            put("strength", strength)
            put("intensity", (if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 1f).toDouble())
            put("capturedAt", capturedAt)
        })
    }

    fun attachSource(id: String, uri: String) = update(id) { put("originalUri", uri) }
    fun markIndexed(id: String) = update(id) { put("sourceIndexed", true) }
    fun attachRender(id: String, uri: String) = update(id) { put("renderedUri", uri) }

    fun finish(id: String) = mutate { array ->
        for (index in array.length() - 1 downTo 0) {
            if (array.optJSONObject(index)?.optString("id") == id) array.remove(index)
        }
    }

    fun pending(): List<PendingCapture> = synchronized(prefs) {
        val array = read()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.matches(Regex("LUMORA_[A-Za-z0-9_]+")) } ?: continue
                val intensity = item.optDouble("intensity", 1.0).toFloat()
                add(PendingCapture(
                    id, item.optString("originalUri").takeIf(String::isNotBlank),
                    item.optString("styleId", "natural"), item.optString("strength", "STANDARD"),
                    if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 1f,
                    item.optLong("capturedAt"), item.optString("renderedUri").takeIf(String::isNotBlank),
                    item.optBoolean("sourceIndexed")
                ))
            }
        }
    }

    private fun read() = JSONArray(prefs.getString("pending", "[]") ?: "[]")

    private fun update(id: String, change: JSONObject.() -> Unit) = mutate { array ->
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("id") == id) item.change()
        }
    }

    private fun mutate(transform: (JSONArray) -> Unit): Boolean = try {
        synchronized(prefs) {
            val array = read()
            transform(array)
            check(prefs.edit().putString("pending", array.toString()).commit()) { "Journal write failed" }
            true
        }
    } catch (error: Exception) {
        // Preserve a malformed journal verbatim; never clear it merely to resume capture.
        Log.w("LumoraCapture", "Capture journal unavailable (${error.javaClass.simpleName})")
        false
    }
}

internal object CaptureReconciliation {
    fun run(
        context: Context, journal: CaptureJournal, repository: PhotoRepository,
        isActive: (String) -> Boolean = { false }
    ): String {
        val resolver = context.applicationContext.contentResolver
        val outcomes = CaptureRecovery.reconcile(
            pending = journal.pending(), isActive = isActive,
            resolveSource = { entry ->
                val original = entry.originalUri
                if (original != null) original.takeIf { exists(resolver, it, "${entry.id}_ORIGINAL.jpg") }
                else findPublishedSource(resolver, "${entry.id}_ORIGINAL.jpg")
            },
            recover = { entry ->
                if (!repository.confirmPendingWrite()) return@reconcile RecoveryOutcome.RETRY
                val original = requireNotNull(entry.originalUri)
                val records = repository.getRecords()
                val versions = records.filter { it.originalUri == original && it.capturedAt == entry.capturedAt }
                val renderName = "${entry.id}_${entry.styleId}.jpg"
                val rendered = entry.renderedUri?.takeIf { exists(resolver, it, renderName) }
                    ?: findPublishedSource(resolver, renderName)
                when {
                    versions.isEmpty() && repository.wasCaptureRemoved(original, entry.capturedAt) -> RecoveryOutcome.ALREADY_INDEXED
                    versions.any { !it.sourceOnly } -> RecoveryOutcome.ALREADY_INDEXED
                    versions.isEmpty() && entry.sourceIndexed -> RecoveryOutcome.ALREADY_INDEXED
                    else -> {
                        val recovered = versions.firstOrNull()?.copy(
                            enhancedUri = rendered ?: original, sourceOnly = rendered == null
                        ) ?: PhotoRecord(
                            originalUri = original, enhancedUri = rendered ?: original,
                            styleId = entry.styleId, createdAt = entry.capturedAt,
                            strength = runCatching { OptimizationStrength.valueOf(entry.strength) }
                                .getOrDefault(OptimizationStrength.STANDARD),
                            intensity = entry.intensity, sourceOnly = rendered == null,
                            capturedAt = entry.capturedAt
                        )
                        if (repository.reconcileCapture(recovered, entry.sourceIndexed)) RecoveryOutcome.RECOVERED
                        else RecoveryOutcome.ALREADY_INDEXED
                    }
                }
            },
            forget = { check(journal.finish(it)) { "Recovery journal write failed" } }
        )
        return RecoveryOutcome.entries.joinToString(" ") { outcome ->
            "${outcome.name.lowercase()}=${outcomes.count { it == outcome }}"
        }
    }

    private fun exists(resolver: ContentResolver, value: String, expectedName: String): Boolean {
        val uri = Uri.parse(value)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
        return requireNotNull(resolver.query(uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null
        )) { "Media provider unavailable" }.use {
            it.moveToFirst() && it.getString(0) == expectedName && it.getInt(1) == 0
        }
    }

    private fun findPublishedSource(resolver: ContentResolver, name: String): String? =
        requireNotNull(resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf(name), null
        )) { "Media provider unavailable" }.use { cursor ->
            if (!cursor.moveToFirst()) null
            else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()
        }
}
