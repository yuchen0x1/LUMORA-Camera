package com.lumora.camera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.OptimizationStrength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun PhotographyOverlay(
    preview: PreviewView,
    aids: PhotoAids,
    preset: FilmPreset,
    strength: OptimizationStrength,
    previewQuality: String,
    modifier: Modifier = Modifier,
    onAvailability: (String?) -> Unit = {},
    intensity: Float = 1f
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val availability by rememberUpdatedState(onAvailability)
    var frame by remember(preview, aids, preset, strength, intensity) { mutableStateOf<AidFrame?>(null) }
    var frameStatus by remember { mutableStateOf<String?>(null) }
    var sensorStatus by remember { mutableStateOf<String?>(null) }
    var roll by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(frameStatus, sensorStatus) {
        availability(listOfNotNull(frameStatus, sensorStatus).joinToString(" · ").ifEmpty { null })
    }
    LaunchedEffect(lifecycle, preview, aids, preset, strength, previewQuality, intensity) {
        frame = null
        frameStatus = null
        if (!aids.samplesFrames) return@LaunchedEffect
        val (side, interval) = when (previewQuality) {
            "省电" -> 160 to 550L
            "高质量" -> 400 to 220L
            else -> 256 to 320L
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            PreviewFrameSampler(side).use { sampler ->
                var lastTimestamp = 0L
                var lastFresh = SystemClock.elapsedRealtime()
                try {
                    while (isActive) {
                        try {
                            val sample = sampler.read(preview)
                            if (sample != null && sample.timeNanos != lastTimestamp) {
                                val result = withContext(Dispatchers.Default) {
                                    sampler.render(sample, aids, preset, strength, intensity)
                                }
                                if (sample.texture.parent === preview &&
                                    preview.previewStreamState.value == PreviewView.StreamState.STREAMING) {
                                    frame = result
                                    frameStatus = null
                                    lastFresh = SystemClock.elapsedRealtime()
                                    lastTimestamp = sample.timeNanos
                                } else frame = null
                            } else if (sample == null || SystemClock.elapsedRealtime() - lastFresh > 1600L) {
                                frame = null
                                frameStatus = "当前取景帧不可用"
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: RuntimeException) {
                            frame = null
                            frameStatus = "当前设备无法分析取景帧"
                        }
                        delay(interval)
                    }
                } finally {
                    frame = null
                }
            }
        }
    }
    LifecycleResumeEffect(preview, aids.level) {
        val manager = preview.context.getSystemService(SensorManager::class.java)
        val sensor = if (aids.level) manager?.let {
            it.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else null
        var gravityX: Float? = null
        var gravityY = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val previousX = gravityX
                val damping = if (sensor?.type == Sensor.TYPE_ACCELEROMETER) 0.83f else 0.5f
                gravityX = if (previousX == null) x else previousX * damping + x * (1f - damping)
                gravityY = if (previousX == null) y else gravityY * damping + y * (1f - damping)
                val angle = horizonAngle(gravityX!!, gravityY, preview.display?.rotation ?: 0)
                roll = angle
                sensorStatus = if (angle == null) "镜头接近垂直，水平参考不可用" else null
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = sensor != null &&
            manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) == true
        sensorStatus = when {
            !aids.level -> null
            registered -> "等待方向传感器"
            else -> "水平仪传感器不可用"
        }
        onPauseOrDispose {
            if (registered) manager?.unregisterListener(listener)
            roll = null
            sensorStatus = null
        }
    }
    val levelDescription = when {
        !aids.level -> ""
        roll == null -> sensorStatus ?: "等待方向传感器"
        abs(roll!!) <= 1.5f -> "已水平"
        else -> "倾斜 ${abs(roll!!).roundToInt()}°"
    }
    val levelSemantics = if (aids.level) Modifier.semantics {
        contentDescription = "水平仪"
        stateDescription = levelDescription
    } else Modifier
    Canvas(modifier.fillMaxSize().then(levelSemantics)) {
        val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val sample = frame
        sample?.style?.let { drawImage(it.asImageBitmap(), dstSize = target) }
        sample?.marks?.let { drawImage(it.asImageBitmap(), dstSize = target) }
        if (aids.grid != CompositionGrid.OFF) {
            val line = ViewfinderTokens.ink.copy(alpha = 0.92f)
            val outline = ViewfinderTokens.scrim
            val fraction = aids.grid.fraction
            for (part in listOf(fraction, 1f - fraction)) {
                val x = size.width * part
                val y = size.height * part
                drawLine(outline, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 0.8.dp.toPx())
                drawLine(outline, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                drawLine(line, Offset(0f, y), Offset(size.width, y), 0.8.dp.toPx())
            }
        }
        if (aids.level) roll?.let { angle ->
            val length = min(size.width * 0.18f, 62.dp.toPx())
            val guide = ViewfinderTokens.ink.copy(alpha = 0.9f)
            for (sign in listOf(-1f, 1f)) {
                val from = Offset(center.x + sign * length * 0.72f, center.y)
                val to = Offset(center.x + sign * length, center.y)
                drawLine(ViewfinderTokens.scrim, from, to, 3.dp.toPx())
                drawLine(guide, from, to, 1.5.dp.toPx())
            }
            rotate(angle, center) {
                val from = Offset(center.x - length * 0.68f, center.y)
                val to = Offset(center.x + length * 0.68f, center.y)
                drawLine(ViewfinderTokens.scrim, from, to, 4.dp.toPx())
                drawLine(if (abs(angle) <= 1.5f) ViewfinderTokens.focus else ViewfinderTokens.accent,
                    from, to, 2.dp.toPx())
            }
        }
        sample?.histogram?.let { drawHistogram(it) }
    }
}

private fun DrawScope.drawHistogram(bins: IntArray) {
    val width = min(104.dp.toPx(), size.width * 0.3f)
    val height = min(44.dp.toPx(), size.height * 0.15f)
    val left = 14.dp.toPx()
    val top = size.height * 0.28f
    drawRoundRect(ViewfinderTokens.scrim, Offset(left, top), Size(width, height),
        CornerRadius(4.dp.toPx()))
    val padding = 4.dp.toPx()
    val binWidth = (width - 2f * padding) / bins.size
    if (binWidth <= 0 || height <= padding * 2) return
    val peak = bins.maxOrNull()?.coerceAtLeast(1) ?: return
    for (i in bins.indices) {
        val bar = sqrt(bins[i].toFloat() / peak) * (height - 2f * padding)
        drawRect(ViewfinderTokens.ink, Offset(left + padding + i * binWidth, top + height - padding - bar),
            Size(binWidth, bar))
    }
}
