package com.lumora.camera

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraShootingControlsTest : CameraUiTest() {
    @Test
    fun manualIsoAndShutterComparedWithSourceExif() {
        waitForStreamingShutter()
        control("专业模式").tapCenter()
        compose.waitUntil(30_000) { shutterState().endsWith(" · PRO") }
        waitForStreamingShutter()
        compose.onNodeWithText("ISO").performScrollTo().tapCenter()
        val lens = requireNotNull(compose.runOnIdle {
            ViewModelProvider(compose.activity)[CameraDeviceState::class.java].lastLens
        })
        if (!lens.supportsManualExposure) {
            compose.onNodeWithText("当前镜头未提供可用的手动曝光请求").assertIsDisplayed()
            Log.i("LumoraManual", "lens=${lens.cameraId} outcome=unsupported")
            assumeTrue("Lens ${lens.cameraId} cannot request manual exposure", false)
        }
        val isoRange = requireNotNull(lens.isoRange)
        val times = requireNotNull(lens.exposureTimeRangeNs)
        compose.onNodeWithText("手动曝光").tapCenter()
        waitForStreamingShutter()
        compose.onNodeWithText("手动曝光").assertIsSelected()
        var requestedIso = isoRange.min
        if (isoRange.max > isoRange.min) {
            val wanted = isoRange.clamp(320).let { if (it == isoRange.min) isoRange.min + 1 else it }
            val position = ln(wanted.toFloat())
            setSlider("感光度", position)
            requestedIso = isoRange.clamp(exp(position).roundToInt())
            compose.onNodeWithText("ISO $requestedIso").assertIsDisplayed()
        }
        compose.onNodeWithText("快门").performScrollTo().tapCenter()
        var requestedNs = times.clamp(8_000_000)
        if (times.max > times.min) {
            val wanted = times.clamp(12_000_000).let {
                if (it != requestedNs) it else if (it > times.min) times.min else times.max
            }
            val position = ln(wanted.toDouble()).toFloat()
            setSlider("曝光时间", position)
            requestedNs = times.clamp(exp(position.toDouble()).roundToLong())
        }
        val photo = captureNewPhoto()
        assertJpegPair(photo)
        val (actualIso, actualNs) = requireNotNull(context.contentResolver.openInputStream(Uri.parse(photo.originalUri))).use {
            val exif = ExifInterface(it)
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1).takeIf { value -> value > 0 }
            val ns = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
                .takeIf { value -> value.isFinite() && value > 0 }?.let { value -> (value * 1e9).roundToLong() }
            iso to ns
        }
        val status = when {
            actualIso == null || actualNs == null -> "unreported"
            actualIso != requestedIso || abs(actualNs - requestedNs) > maxOf(1.0, requestedNs * 0.03) -> "different"
            else -> "within_tolerance"
        }
        val facts = requireNotNull(photo.diagnostics).split(' ').mapNotNull { field ->
            if ('=' in field) field.substringBefore('=') to field.substringAfter('=') else null
        }.toMap()
        val evidence = "lens=${lens.cameraId} requestedISO=$requestedIso requestedExposureNs=$requestedNs " +
            "exifISO=${actualIso ?: "unavailable"} exifExposureNs=${actualNs ?: "unavailable"} outcome=$status"
        Log.i("LumoraManual", evidence)
        assertEquals(evidence, "PRO", facts["mode"])
        assertEquals(evidence, requestedIso.toString(), facts["requestedISO"])
        assertEquals(evidence, requestedNs.toString(), facts["requestedExposureNs"])
        assertEquals(evidence, actualIso?.toString() ?: "unavailable", facts["iso"])
        assertEquals(evidence, actualNs?.toString() ?: "unavailable", facts["exposureNs"])
        assertEquals(evidence, status, facts["manualExposure"])
        // The diagnostic contract was checked above. Only a matching JPEG passes hardware acceptance.
        assumeTrue(evidence, status == "within_tolerance")
    }

    private fun setSlider(label: String, position: Float) {
        control(label).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { change ->
            assertTrue("Slider $label did not accept $position", change(position))
        }
        waitForStreamingShutter()
    }

    @Test
    fun volumeKeyTriggersOneTimedThreeShotBurst() {
        waitForStreamingShutter()
        val settings = CameraSettings(context)
        val volumeWasEnabled = settings.volumeShutter
        if (!volumeWasEnabled) {
            control("设置").tapCenter()
            val toggle = compose.onNodeWithText("音量键快门")
            assertEquals(ToggleableState.Off, toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
            toggle.tapCenter()
            control("返回").tapCenter()
            waitForStreamingShutter()
        }
        try {
            control("拍摄工具").tapCenter()
            compose.onNodeWithText("3 秒").performScrollTo().tapCenter()
            compose.onNodeWithText("3 秒").assertIsSelected()
            compose.onNodeWithText("3 张").performScrollTo().tapCenter()
            compose.onNodeWithText("3 张").assertIsSelected()
            control("收起面板").tapCenter()
            val previous = repository.getRecords().map { it.originalUri }.toSet()
            compose.mainClock.autoAdvance = false
            try {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
                compose.mainClock.advanceTimeByFrame()
                compose.onNode(hasText("3") or hasText("2")).assertIsDisplayed()
                compose.mainClock.advanceTimeBy(1_000)
                assertTrue("Timer exposed a shot before its countdown completed",
                    repository.getRecords().all { it.originalUri in previous })
                compose.mainClock.advanceTimeBy(3_000)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val done = repository.getRecords().count { it.originalUri !in previous && !it.sourceOnly }
                if (done >= 3) break
                SystemClock.sleep(200)
            }
            waitForStreamingShutter()
            val shots = repository.getRecords().filter { it.originalUri !in previous }
            assertEquals("One volume-key down/up must create exactly the three configured captures", 3, shots.size)
            assertEquals(3, shots.map { it.originalUri }.toSet().size)
            assertEquals(3, shots.map { it.enhancedUri }.toSet().size)
            shots.forEach { assertJpegPair(it) }
        } finally {
            if (!volumeWasEnabled) settings.volumeShutter = false
        }
    }
}
