package com.lumora.camera.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.lumora.camera.image.PhotoEditParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.WeakHashMap

enum class OptimizationStrength(val label: String, val factor: Float) {
    NATURAL("自然", 0.65f), STANDARD("标准", 1f), STRONG("强化", 1.35f)
}

data class PhotoRecord(
    val originalUri: String,
    val enhancedUri: String,
    val styleId: String,
    val createdAt: Long,
    val strength: OptimizationStrength = OptimizationStrength.STANDARD,
    val intensity: Float = 1f,
    val edits: PhotoEditParameters = PhotoEditParameters.NATURAL,
    val favorite: Boolean = false,
    val sourceOnly: Boolean = false,
    val imported: Boolean = false,
    val parentUri: String? = null,
    val capturedAt: Long = createdAt,
    val diagnostics: String? = null,
    // Legacy captures with "keep original" off used the rendered URI for both fields.
    val sourceIsRendered: Boolean = !sourceOnly && originalUri == enhancedUri,
    val recipeKnown: Boolean = true
)

class RemovedPhoto internal constructor(internal val library: SharedPreferences, internal var latest: PhotoRecord)

class PhotoRepository(context: Context, namespace: String = "lumora_preferences") {
    private val prefs = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    private companion object {
        const val RECOVERY_PREFIX = "recordsRecovery."
        const val REMOVED_CAPTURE_PREFIX = "captureRemoved."
        // The snackbar holds the token only while undo is available; capture can finish in
        // another repository instance. No removed row or thumbnail is kept alive by this map.
        val pendingUndo = WeakHashMap<RemovedPhoto, Unit>()
        // commit() can fail after changing SharedPreferences' in-memory map. A retry of the
        // same logical update must flush that map, not mistake equality for durable success.
        val unconfirmedWrites = WeakHashMap<SharedPreferences, Unit>()
    }

    private fun rawRecords(): String? {
        val all = prefs.all
        return if (all.containsKey("records")) all["records"] as? String else "[]"
    }

    private fun recordArray(raw: String? = rawRecords()): JSONArray? = try {
        raw?.let(::JSONArray)
    } catch (_: JSONException) {
        null
    }

    // Reading a damaged index must neither crash the UI nor change the stored data.
    fun getRecords(): List<PhotoRecord> = readRecords(recordArray())

    private fun readRecord(item: JSONObject): PhotoRecord? {
        val enhanced = item.optString("enhancedUri").ifBlank { item.optString("originalUri") }
        if (enhanced.isBlank()) return null
        val source = item.optString("originalUri").ifBlank { enhanced }
        val time = item.optLong("createdAt")
        val sourceOnly = item.optBoolean("sourceOnly")
        return PhotoRecord(
            originalUri = source, enhancedUri = enhanced, createdAt = time,
            styleId = item.optString("styleId", "natural"),
            strength = readStrength(item.optString("strength")),
            intensity = normalizeIntensity(item.optDouble("intensity", 1.0).toFloat()),
            edits = readEdits(item.optJSONObject("edits")), favorite = item.optBoolean("favorite"),
            sourceOnly = sourceOnly, imported = item.optBoolean("imported"),
            parentUri = item.optString("parentUri").takeIf { it.isNotBlank() && !item.isNull("parentUri") },
            capturedAt = item.optLong("capturedAt", time),
            diagnostics = item.optString("diagnostics").takeIf { it.isNotBlank() && !item.isNull("diagnostics") },
            sourceIsRendered = item.optBoolean("sourceIsRendered", !sourceOnly && source == enhanced),
            recipeKnown = item.optBoolean("recipeKnown", item.has("strength"))
        )
    }

    private fun readRecords(array: JSONArray?): List<PhotoRecord> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::readRecord)?.let(::add)
            }
        }.sortedByDescending { it.createdAt }.distinctBy { it.enhancedUri }
    }

    fun observeRecords() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "records" || key == null) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { getRecords() }.flowOn(Dispatchers.IO)

    fun observeRecoveryStatus() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "records" || key == "recordWriteRevision" || key.startsWith(RECOVERY_PREFIX)) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { getRecoveryStatus() }.flowOn(Dispatchers.IO)

    fun getRecoveryStatus(): PhotoIndexRecoveryStatus = synchronized(prefs) {
        PhotoIndexRecoveryStatus(
            backupCount = prefs.all.keys.count { it.startsWith(RECOVERY_PREFIX) },
            currentIndexDamaged = indexDamaged(rawRecords()),
            writeUnconfirmed = synchronized(unconfirmedWrites) { unconfirmedWrites.containsKey(prefs) }
        )
    }

    /** Confirm an index write whose SharedPreferences commit result was uncertain. */
    internal fun confirmPendingWrite(): Boolean = synchronized(prefs) {
        if (synchronized(unconfirmedWrites) { !unconfirmedWrites.containsKey(prefs) }) return true
        return try {
            commitIndex(prefs.edit())
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun indexDamaged(raw: String?): Boolean {
        val inspected = PhotoIndexRecovery.inspect(raw, ::readRecord, requireSupportedUris = false)
        // Duplicate grid keys must not crash the recovery entry point. The hidden duplicate
        // still counts as damage and its exact text is retained before any rewrite.
        return inspected.damaged || inspected.records.distinctBy { it.enhancedUri }.size != inspected.records.size
    }

    private fun recoverySnapshots(): List<PhotoIndexSnapshot> = buildList {
        val current = rawRecords()
        if (indexDamaged(current)) add(PhotoIndexSnapshot("records", current))
        prefs.all.entries.filter { it.key.startsWith(RECOVERY_PREFIX) }.sortedBy { it.key }.forEach {
            add(PhotoIndexSnapshot(it.key, it.value as? String))
        }
    }

    /** Explicit user action only. Call on IO; this opens media but never writes or takes grants. */
    fun previewIndexRecovery(): PhotoIndexRecoveryPreview {
        val (snapshots, current) = synchronized(prefs) { recoverySnapshots() to getRecords() }
        var malformed = 0
        var invalid = 0
        var alreadyPresent = 0
        val candidates = buildList {
            snapshots.forEach { snapshot ->
                val inspected = PhotoIndexRecovery.inspect(snapshot.raw, ::readRecord)
                if (inspected.malformed) malformed++
                invalid += inspected.invalidEntryCount
                inspected.records.forEach { record ->
                    if (current.any { PhotoIndexRecovery.conflicts(it, record) }) alreadyPresent++ else add(record)
                }
            }
        }
        val distinct = candidates.distinct()
        // Do not pick an arbitrary recipe when two backups disagree about the same version.
        val ambiguous = distinct.filter { record ->
            distinct.any { it != record && PhotoIndexRecovery.conflicts(it, record) }
        }.toSet()
        val readable = mutableMapOf<String, Boolean>()
        var unreadable = 0
        val validated = distinct.filterNot { it in ambiguous }.filter { record ->
            listOf(record.originalUri, record.enhancedUri).distinct().all { uri ->
                readable.getOrPut(uri) { PhotoIndexRecovery.readableMedia(resolver, uri) }
            }.also { if (!it) unreadable++ }
        }
        return PhotoIndexRecoveryPreview(
            backupCount = snapshots.count { it.key.startsWith(RECOVERY_PREFIX) },
            currentIndexDamaged = snapshots.any { it.key == "records" },
            malformedSnapshotCount = malformed, invalidEntryCount = invalid,
            duplicateEntryCount = candidates.size - distinct.size, alreadyPresentCount = alreadyPresent,
            conflictingRecordCount = ambiguous.size, unreadableMediaCount = unreadable,
            records = validated,
            writeUnconfirmed = synchronized(unconfirmedWrites) { unconfirmedWrites.containsKey(prefs) },
            library = prefs, snapshots = snapshots
        )
    }

    /**
     * Merge only the reviewed, still-readable versions. No current row, raw backup, or photo
     * is replaced/deleted. Recheck under the library lock so capture/favorite/removal updates
     * from another repository cannot be lost between preview and confirmation.
     */
    fun restoreIndexRecovery(preview: PhotoIndexRecoveryPreview): PhotoIndexRecoveryResult {
        if (preview.library !== prefs || synchronized(prefs) { recoverySnapshots() != preview.snapshots }) {
            return PhotoIndexRecoveryResult(PhotoIndexRecoveryOutcome.STALE_PREVIEW)
        }
        val readable = mutableMapOf<String, Boolean>()
        var unreadable = 0
        val candidates = preview.records.filter { record ->
            listOf(record.originalUri, record.enhancedUri).distinct().all { uri ->
                readable.getOrPut(uri) { PhotoIndexRecovery.readableMedia(resolver, uri) }
            }.also { if (!it) unreadable++ }
        }
        return synchronized(prefs) {
            if (recoverySnapshots() != preview.snapshots) {
                return@synchronized PhotoIndexRecoveryResult(PhotoIndexRecoveryOutcome.STALE_PREVIEW)
            }
            val raw = rawRecords() ?: return@synchronized PhotoIndexRecoveryResult(
                PhotoIndexRecoveryOutcome.WRITE_NOT_CONFIRMED
            )
            val current = getRecords()
            val additions = candidates.filter { record -> current.none { PhotoIndexRecovery.conflicts(it, record) } }
            val skipped = candidates.size - additions.size
            if (additions.isEmpty()) {
                if (synchronized(unconfirmedWrites) { unconfirmedWrites.containsKey(prefs) }) {
                    try {
                        commitIndex(prefs.edit())
                        return@synchronized PhotoIndexRecoveryResult(
                            PhotoIndexRecoveryOutcome.WRITE_CONFIRMED, alreadyPresentCount = skipped,
                            unreadableMediaCount = unreadable
                        )
                    } catch (_: IOException) {
                        return@synchronized PhotoIndexRecoveryResult(PhotoIndexRecoveryOutcome.WRITE_NOT_CONFIRMED)
                    }
                }
                return@synchronized PhotoIndexRecoveryResult(
                    PhotoIndexRecoveryOutcome.NOTHING_NEW, alreadyPresentCount = skipped,
                    unreadableMediaCount = unreadable
                )
            }
            // Keep even unrecognized array entries and future metadata in current records.
            // If the outer array is damaged, preserve its exact text in the SAME disk edit.
            val merged = recordArray(raw) ?: JSONArray()
            additions.forEach { merged.put(recordJson(it)) }
            val editor = prefs.edit().putInt("recordSchema", 2).putString("records", merged.toString())
            preserveDamagedIndex(editor, raw)
            try {
                commitIndex(editor)
                PhotoIndexRecoveryResult(
                    PhotoIndexRecoveryOutcome.RESTORED, restoredCount = additions.size,
                    alreadyPresentCount = skipped, unreadableMediaCount = unreadable
                )
            } catch (_: IOException) {
                PhotoIndexRecoveryResult(PhotoIndexRecoveryOutcome.WRITE_NOT_CONFIRMED)
            }
        }
    }

    fun addRecord(record: PhotoRecord) = updateRecords {
        listOf(record) + it.filterNot { old -> old.enhancedUri == record.enhancedUri }
    }

    /** Recheck presence and durable removal intent under the same lock as index mutation. */
    internal fun reconcileCapture(record: PhotoRecord, sourceIndexed: Boolean): Boolean = synchronized(prefs) {
        val versions = getRecords().filter { it.isSameCaptureAs(record) }
        if (versions.any { !it.sourceOnly } ||
            versions.isEmpty() && (sourceIndexed || wasCaptureRemoved(record.originalUri, record.capturedAt))) {
            return@synchronized false
        }
        if (versions.isEmpty()) addRecord(record)
        else if (!record.sourceOnly) replaceSourceRecord(record)
        true
    }

    fun replaceSourceRecord(record: PhotoRecord) = updateRecords { records ->
        val source = records.find { it.sourceOnly && record.isInitialVersionOf(it) }
        if (source == null) {
            synchronized(pendingUndo) {
                pendingUndo.keys.find { it.library === prefs && it.latest.sourceOnly && record.isInitialVersionOf(it.latest) }
                    ?.let { it.latest = record.copy(favorite = it.latest.favorite) }
            }
            return@updateRecords records
        }
        val existing = records.find { it.enhancedUri == record.enhancedUri }
        val completed = record.copy(favorite = existing?.favorite ?: source.favorite)
        listOf(completed) + records.filterNot { it == source || it.enhancedUri == record.enhancedUri }
    }

    private fun PhotoRecord.isInitialVersionOf(source: PhotoRecord) =
        parentUri == null && originalUri == source.originalUri && capturedAt == source.capturedAt

    private fun PhotoRecord.isSameCaptureAs(other: PhotoRecord) =
        originalUri == other.originalUri && capturedAt == other.capturedAt

    private fun captureRemovalKey(originalUri: String, capturedAt: Long): String {
        // A fixed-size identity avoids copying a private URI into preference key names.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$originalUri\u0000$capturedAt".toByteArray(Charsets.UTF_8))
        return REMOVED_CAPTURE_PREFIX + digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * Durable removal intent for journal reconciliation, including after process death.
     * Check matching active records first: explicit user recovery may have restored a version.
     * Never infer removal merely from an absent row or release a media URI grant here.
     */
    fun wasCaptureRemoved(originalUri: String, capturedAt: Long): Boolean = synchronized(prefs) {
        prefs.contains(captureRemovalKey(originalUri, capturedAt))
    }

    fun removeRecord(record: PhotoRecord): RemovedPhoto? = synchronized(prefs) {
        var removal: RemovedPhoto? = null
        var tombstone: String? = null
        updateRecords(editIndexMetadata = { editor ->
            val key = tombstone
            if (key != null && !prefs.contains(key)) {
                editor.putBoolean(key, true)
                true
            } else false
        }) { list ->
            val current = list.find {
                it.enhancedUri == record.enhancedUri && (!record.sourceOnly || it.capturedAt == record.capturedAt)
            }
                ?: list.find { record.sourceOnly && it.isInitialVersionOf(record) }
                ?: return@updateRecords list
            removal = RemovedPhoto(prefs, current)
            list.filterNot { it.enhancedUri == current.enhancedUri }.also { remaining ->
                if (remaining.none { it.isSameCaptureAs(current) }) {
                    tombstone = captureRemovalKey(current.originalUri, current.capturedAt)
                }
            }
        }
        removal?.also { synchronized(pendingUndo) { pendingUndo[it] = Unit } }
    }

    fun undoRemoval(removal: RemovedPhoto) = synchronized(prefs) {
        var clearTombstone: String? = null
        updateRecords(editIndexMetadata = { editor ->
            val key = clearTombstone
            if (key != null && prefs.contains(key)) {
                editor.remove(key)
                true
            } else false
        }) { records ->
            if (removal.library !== prefs || synchronized(pendingUndo) { !pendingUndo.containsKey(removal) }) {
                return@updateRecords records
            }
            val restore = removal.latest
            val restored = if (records.any { it.enhancedUri == restore.enhancedUri || (restore.sourceOnly && it.isInitialVersionOf(restore)) }) {
                records
            } else listOf(restore) + records
            if (restored.any { it.isSameCaptureAs(restore) }) {
                clearTombstone = captureRemovalKey(restore.originalUri, restore.capturedAt)
            }
            restored
        }
        // A failed disk write must leave the undo token available for an explicit retry.
        if (removal.library === prefs) synchronized(pendingUndo) { pendingUndo.remove(removal) }
        Unit
    }

    fun setFavorite(uri: String, favorite: Boolean) = updateRecords { records ->
        records.map { if (it.enhancedUri == uri) it.copy(favorite = favorite) else it }
    }

    // All record mutations are synchronous, checked disk writes. Call these methods on IO.
    // Ordinary UI/settings preferences below intentionally keep their existing apply() policy.
    private fun updateRecords(
        editIndexMetadata: (SharedPreferences.Editor) -> Boolean = { false },
        transform: (List<PhotoRecord>) -> List<PhotoRecord>
    ) = synchronized(prefs) {
        val raw = rawRecords() ?: throw IOException("作品索引格式无法识别，未改写原始数据。")
        val current = readRecords(recordArray(raw))
        val updated = transform(current)
        val editor = prefs.edit()
        val metadataChanged = editIndexMetadata(editor)
        if (updated == current && !metadataChanged) {
            if (synchronized(unconfirmedWrites) { unconfirmedWrites.containsKey(prefs) }) commitIndex(editor)
            return@synchronized
        }
        if (updated != current) {
            val array = JSONArray()
            updated.forEach { array.put(recordJson(it)) }
            editor.putInt("recordSchema", 2).putString("records", array.toString())
            preserveDamagedIndex(editor, raw)
        }
        // Capture-removal intent/undo and the corresponding row change share this disk edit.
        commitIndex(editor)
    }

    private fun recordJson(record: PhotoRecord) = JSONObject().apply {
        put("originalUri", record.originalUri)
        put("enhancedUri", record.enhancedUri)
        put("styleId", record.styleId)
        put("createdAt", record.createdAt)
        put("capturedAt", record.capturedAt)
        put("favorite", record.favorite)
        put("strength", record.strength.name)
        put("intensity", normalizeIntensity(record.intensity).toDouble())
        put("edits", editJson(record.edits))
        put("sourceOnly", record.sourceOnly)
        put("sourceIsRendered", record.sourceIsRendered)
        put("recipeKnown", record.recipeKnown)
        put("imported", record.imported)
        put("parentUri", record.parentUri)
        put("diagnostics", record.diagnostics)
    }

    private fun preserveDamagedIndex(editor: SharedPreferences.Editor, raw: String) {
        if (!indexDamaged(raw)) return
        // Keep the exact original, including entries we cannot understand. Identical snapshots
        // need not be copied again, but no existing recovery key is ever overwritten or pruned.
        if (prefs.all.any { (key, value) -> key.startsWith(RECOVERY_PREFIX) && value == raw }) return
        var key: String
        do { key = "$RECOVERY_PREFIX${UUID.randomUUID()}" } while (prefs.contains(key))
        editor.putString(key, raw)
    }

    private fun commitIndex(editor: SharedPreferences.Editor) {
        // Mark before committing: both false and thrown failures leave durability uncertain.
        synchronized(unconfirmedWrites) { unconfirmedWrites[prefs] = Unit }
        val committed = try {
            // Also notify recovery observers when confirming an otherwise equal retry.
            val revision = ((prefs.all["recordWriteRevision"] as? Long) ?: 0L) + 1L
            editor.putLong("recordWriteRevision", revision)
            editor.commit()
        } catch (error: Exception) {
            throw IOException("作品索引尚未确认写入磁盘。照片文件未删除，请保留当前编辑并重试。", error)
        }
        if (!committed) throw IOException("作品索引尚未确认写入磁盘。照片文件未删除，请保留当前编辑并重试。")
        synchronized(unconfirmedWrites) { unconfirmedWrites.remove(prefs) }
    }

    val favoriteStyleIds: Set<String> get() = prefs.getStringSet("favoriteStyleIds", emptySet()).orEmpty().toSet()

    fun setStyleFavorite(id: String, favorite: Boolean) = synchronized(prefs) {
        val updated = if (favorite) favoriteStyleIds + id else favoriteStyleIds - id
        prefs.edit().putStringSet("favoriteStyleIds", updated).apply()
    }

    var selectedStyleId: String
        get() = prefs.getString("selectedStyleId", "natural") ?: "natural"
        set(value) { prefs.edit().putString("selectedStyleId", value).apply() }

    var styleSampleUri: String?
        get() = prefs.getString("styleSampleUri", null)
        set(value) { prefs.edit().putString("styleSampleUri", value).apply() }

    var styleIntensity: Float
        get() = normalizeIntensity(prefs.getFloat("styleIntensity", 1f))
        set(value) { prefs.edit().putFloat("styleIntensity", normalizeIntensity(value)).apply() }

    var optimizationStrength: OptimizationStrength
        get() = readStrength(prefs.getString("optimizationStrength", "STANDARD"))
        set(value) { prefs.edit().putString("optimizationStrength", value.name).apply() }

    var keepOriginal: Boolean
        get() = prefs.getBoolean("keepOriginal", true)
        set(value) { prefs.edit().putBoolean("keepOriginal", value).apply() }

    var rawEnabled: Boolean
        get() = prefs.getBoolean("rawEnabled", false)
        set(value) { prefs.edit().putBoolean("rawEnabled", value).apply() }

    var previewQuality: String
        get() = prefs.getString("previewQuality", "标准") ?: "标准"
        set(value) { prefs.edit().putString("previewQuality", value).apply() }

    fun uriOrNull(value: String): Uri? = value.takeIf { it.isNotBlank() }?.let(Uri::parse)

    private fun readStrength(name: String?): OptimizationStrength =
        OptimizationStrength.entries.firstOrNull { it.name == name } ?: OptimizationStrength.STANDARD

    private fun normalizeIntensity(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f

    private fun editJson(edits: PhotoEditParameters) = JSONObject().apply {
        put("exposure", edits.exposure.toDouble())
        put("contrast", edits.contrast.toDouble())
        put("highlights", edits.highlights.toDouble())
        put("shadows", edits.shadows.toDouble())
        put("temperature", edits.temperature.toDouble())
        put("tint", edits.tint.toDouble())
        put("saturation", edits.saturation.toDouble())
        put("detail", edits.detail.toDouble())
        put("sharpening", edits.sharpening.toDouble())
        put("denoise", edits.denoise.toDouble())
        put("grain", edits.grain.toDouble())
        put("vignette", edits.vignette.toDouble())
    }

    private fun readEdits(json: JSONObject?): PhotoEditParameters {
        if (json == null) return PhotoEditParameters.NATURAL
        fun get(key: String) = json.optDouble(key, 0.0).toFloat().takeIf { it.isFinite() } ?: 0f
        return PhotoEditParameters(
            exposure = get("exposure"), contrast = get("contrast"), highlights = get("highlights"),
            shadows = get("shadows"), temperature = get("temperature"), tint = get("tint"),
            saturation = get("saturation"), detail = get("detail"), sharpening = get("sharpening"),
            denoise = get("denoise"), grain = get("grain"), vignette = get("vignette")
        )
    }
}

