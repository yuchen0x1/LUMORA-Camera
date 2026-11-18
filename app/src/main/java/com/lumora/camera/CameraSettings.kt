package com.lumora.camera

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class CameraSettings(context: Context) {
    private val prefs = context.getSharedPreferences("lumora_preferences", Context.MODE_PRIVATE)
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
    var goldenGrid by toggle()
    var jpegQuality: Int
        get() = prefs.getInt("jpegQuality", 95).coerceIn(1, 100)
        set(value) { prefs.edit().putInt("jpegQuality", value.coerceIn(1, 100)).apply() }
}
