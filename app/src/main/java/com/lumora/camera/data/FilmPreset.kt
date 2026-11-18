package com.lumora.camera.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChannelCurve(val lowerMid: Float = 0f, val upperMid: Float = 0f)
data class RgbCurves(val red: ChannelCurve = ChannelCurve(), val green: ChannelCurve = ChannelCurve(),
                     val blue: ChannelCurve = ChannelCurve())
data class HueAdjustment(val center: Float, val radius: Float, val hueShift: Float,
                         val saturationShift: Float, val lightnessShift: Float)

data class FilmPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val temperature: Float,
    val tint: Float,
    val saturation: Float,
    val contrast: Float,
    val highlights: Float,
    val shadows: Float,
    val blacks: Float,
    val whites: Float,
    val grain: Float,
    val vignette: Float,
    val sharpness: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val curves: RgbCurves = RgbCurves(),
    val hueAdjustments: List<HueAdjustment> = emptyList()
)

object FilmPresets {
    private fun JSONObject.parameter(key: String): Float =
        getDouble(key).toFloat().also { require(it.isFinite()) { "Invalid preset parameter $key" } }

    private fun JSONObject.curve(name: String): ChannelCurve {
        val points = getJSONArray(name)
        require(points.length() == 2)
        fun point(index: Int) = points.getDouble(index).toFloat().also { require(it in -0.14f..0.14f) }
        return ChannelCurve(point(0), point(1))
    }

    private fun JSONObject.hueAdjustments(): List<HueAdjustment> {
        val ranges = getJSONArray("hsl")
        return List(ranges.length()) { index ->
            val values = ranges.getJSONArray(index)
            require(values.length() == 5)
            fun get(i: Int, range: ClosedFloatingPointRange<Float>) =
                values.getDouble(i).toFloat().also { require(it in range) }
            HueAdjustment(get(0, 0f..360f), get(1, 1f..180f), get(2, -60f..60f),
                get(3, -1f..1f), get(4, -0.5f..0.5f))
        }
    }

    fun load(context: Context): List<FilmPreset> {
        val json = context.assets.open("presets.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    FilmPreset(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        subtitle = item.getString("subtitle"),
                        temperature = item.parameter("temperature"),
                        tint = item.parameter("tint"),
                        saturation = item.parameter("saturation"),
                        contrast = item.parameter("contrast"),
                        highlights = item.parameter("highlights"),
                        shadows = item.parameter("shadows"),
                        blacks = item.parameter("blacks"),
                        whites = item.parameter("whites"),
                        grain = item.parameter("grain"),
                        vignette = item.parameter("vignette"),
                        sharpness = item.parameter("sharpness"),
                        red = item.parameter("red"),
                        green = item.parameter("green"),
                        blue = item.parameter("blue"),
                        curves = item.getJSONObject("curves").let { RgbCurves(it.curve("r"), it.curve("g"), it.curve("b")) },
                        hueAdjustments = item.hueAdjustments()
                    )
                )
            }
        }
    }
}
