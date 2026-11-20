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

    private companion object {
        // The snackbar holds the token only while undo is available; capture can finish in
        // another repository instance. No removed row or thumbnail is kept alive by this map.
        val pendingUndo = WeakHashMap<RemovedPhoto, Unit>()
    }

    private fun recordArray(raw: String = prefs.getString("records", "[]") ?: "[]"): JSONArray? = try {
        JSONArray(raw)
    } catch (_: JSONException) {
        null
    }

    // Reading a damaged index must neither crash the UI nor change the stored data.
    fun getRecords(): List<PhotoRecord> = readRecords(recordArray())

    private fun readRecords(array: JSONArray?): List<PhotoRecord> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val enhanced = item.optString("enhancedUri").ifBlank { item.optString("originalUri") }
                if (enhanced.isBlank()) continue
                val source = item.optString("originalUri").ifBlank { enhanced }
                val time = item.optLong("createdAt")
                val sourceOnly = item.optBoolean("sourceOnly")
                add(PhotoRecord(
                    originalUri = source, enhancedUri = enhanced, createdAt = time,
                    styleId = item.optString("styleId", "natural"),
                    strength = readStrength(item.optString("strength")),
                    intensity = normalizeIntensity(item.optDouble("intensity", 1.0).toFloat()),
                    edits = readEdits(item.optJSONObject("edits")), favorite = item.optBoolean("favorite"),
                    sourceOnly = sourceOnly, imported = item.optBoolean("imported"),
                    parentUri = item.optString("parentUri").takeIf(String::isNotBlank),
                    capturedAt = item.optLong("capturedAt", time),
                    diagnostics = item.optString("diagnostics").takeIf(String::isNotBlank),
                    sourceIsRendered = item.optBoolean("sourceIsRendered", !sourceOnly && source == enhanced),
                    recipeKnown = item.optBoolean("recipeKnown", item.has("strength"))
                ))
            }
        }.sortedByDescending { it.createdAt }
    }

    fun observeRecords() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "records" || key == null) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { getRecords() }.flowOn(Dispatchers.IO)

    fun addRecord(record: PhotoRecord) = updateRecords {
        listOf(record) + it.filterNot { old -> old.enhancedUri == record.enhancedUri }
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

    fun removeRecord(record: PhotoRecord): RemovedPhoto? = synchronized(prefs) {
        var removal: RemovedPhoto? = null
        updateRecords { list ->
            val current = list.find {
                it.enhancedUri == record.enhancedUri && (!record.sourceOnly || it.capturedAt == record.capturedAt)
            }
                ?: list.find { record.sourceOnly && it.isInitialVersionOf(record) }
                ?: return@updateRecords list
            removal = RemovedPhoto(prefs, current).also { synchronized(pendingUndo) { pendingUndo[it] = Unit } }
            list.filterNot { it.enhancedUri == current.enhancedUri }
        }
        removal
    }

    fun undoRemoval(removal: RemovedPhoto) = updateRecords { records ->
        if (removal.library !== prefs || synchronized(pendingUndo) { pendingUndo.remove(removal) } == null) {
            return@updateRecords records
        }
        val restore = removal.latest
        if (records.any { it.enhancedUri == restore.enhancedUri || (restore.sourceOnly && it.isInitialVersionOf(restore)) }) {
            records
        } else listOf(restore) + records
    }

    fun setFavorite(uri: String, favorite: Boolean) = updateRecords { records ->
        records.map { if (it.enhancedUri == uri) it.copy(favorite = favorite) else it }
    }

    private fun updateRecords(transform: (List<PhotoRecord>) -> List<PhotoRecord>) = synchronized(prefs) {
        val raw = prefs.getString("records", "[]") ?: "[]"
        val original = recordArray(raw)
        val current = readRecords(original)
        val updated = transform(current)
        if (updated == current) return@synchronized
        val array = JSONArray()
        updated.forEach { r ->
            array.put(JSONObject().apply {
                put("originalUri", r.originalUri)
                put("enhancedUri", r.enhancedUri)
                put("styleId", r.styleId)
                put("createdAt", r.createdAt)
                put("capturedAt", r.capturedAt)
                put("favorite", r.favorite)
                put("strength", r.strength.name)
                put("intensity", normalizeIntensity(r.intensity).toDouble())
                put("edits", editJson(r.edits))
                put("sourceOnly", r.sourceOnly)
                put("sourceIsRendered", r.sourceIsRendered)
                put("recipeKnown", r.recipeKnown)
                put("imported", r.imported)
                put("parentUri", r.parentUri)
                put("diagnostics", r.diagnostics)
            })
        }
        val editor = prefs.edit().putInt("recordSchema", 2).putString("records", array.toString())
        if (original == null || current.size != original.length()) {
            // Skipped entries are also damage, even when the outer array is valid JSON.
            // Preserve the exact snapshot in the same atomic edit as its replacement;
            // never overwrite earlier recovery data or delete underlying media.
            editor.putString("recordsRecovery.${UUID.randomUUID()}", raw)
        }
        editor.apply()
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

