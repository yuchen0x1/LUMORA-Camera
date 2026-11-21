package com.lumora.camera

import android.content.Context
import androidx.core.content.edit
import com.lumora.camera.ui.CompositionGrid
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class CameraSettings(context: Context, preferencesName: String = "lumora_preferences") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private fun toggle(initial: Boolean = false) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(property.name, initial)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(property.name, value).apply()
        }
    }
    var volumeShutter by toggle(true)
    var shutterSound by toggle()
    var histogram by toggle()
    var clipping by toggle()
    var peaking by toggle()
    var level by toggle()
    var compositionGrid: CompositionGrid
        get() {
            val name = prefs.getString("compositionGrid", null)
            return CompositionGrid.entries.firstOrNull { it.name == name }
                ?: if (prefs.getBoolean("goldenGrid", false)) CompositionGrid.GOLDEN else CompositionGrid.OFF
        }
        set(value) {
            prefs.edit {
                putString("compositionGrid", value.name)
                putBoolean("goldenGrid", value == CompositionGrid.GOLDEN)
            }
        }
    var goldenGrid: Boolean
        get() = compositionGrid == CompositionGrid.GOLDEN
        set(value) { compositionGrid = if (value) CompositionGrid.GOLDEN else CompositionGrid.OFF }
    var jpegQuality: Int
        get() = prefs.getInt("jpegQuality", 95).coerceIn(1, 100)
        set(value) { prefs.edit().putInt("jpegQuality", value.coerceIn(1, 100)).apply() }
}
