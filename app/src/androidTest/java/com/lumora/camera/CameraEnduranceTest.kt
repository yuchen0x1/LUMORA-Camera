package com.lumora.camera

import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraEnduranceTest : CameraUiTest() {
    // Run on a disposable device. Leaves the 100 photographed originals and results in its library.
    @Test
    fun oneHundredShutterCapturesRetainFullResolutionPairs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e endurance true to run 100 actual captures", args.getString("endurance") == "true")
        val previewSeconds = (args.getString("previewSeconds") ?: "60").toInt().also { require(it > 0) }
        val power = requireNotNull(context.getSystemService(PowerManager::class.java))
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        waitForStreamingShutter()
        val previewStarted = SystemClock.elapsedRealtime()
        repeat(previewSeconds) {
            SystemClock.sleep(1_000)
            waitForStreamingShutter()
        }
        val shotsStarted = SystemClock.elapsedRealtime()
        Log.i(TAG, "previewMs=${shotsStarted - previewStarted} shutter=${shutterState()}")
        val seen = mutableSetOf<String>()
        val durations = mutableListOf<Long>()
        var largestPssKb = 0
        fun sample(completed: Int) {
            val memory = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
            largestPssKb = maxOf(largestPssKb, memory.totalPss)
            val runtime = Runtime.getRuntime()
            val javaKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
            Log.i(TAG, "completed=$completed pssKb=${memory.totalPss} largestSampledPssKb=$largestPssKb " +
                "javaUsedKb=$javaKb thermalStatus=${power.currentThermalStatus} elapsedMs=${SystemClock.elapsedRealtime() - shotsStarted}")
        }
        sample(0)
        repeat(100) { index ->
            val start = SystemClock.elapsedRealtime()
            try {
                val shot = captureNewPhoto()
                assertTrue("Reused original URI on shot ${index + 1}", seen.add(shot.originalUri))
                assertTrue("Reused result URI on shot ${index + 1}", seen.add(shot.enhancedUri))
                val jpeg = assertJpegPair(shot)
                val facts = requireNotNull(shot.diagnostics)
                assertTrue(facts, facts.contains("input=${jpeg.width}x${jpeg.height} bytes=${jpeg.bytes} "))
                durations += SystemClock.elapsedRealtime() - start
                if ((index + 1) % 10 == 0) sample(index + 1)
            } catch (failure: Throwable) {
                Log.e(TAG, "failedShot=${index + 1} completed=$index", failure)
                throw failure
            }
        }
        val retained = repository.getRecords().filter { it.enhancedUri in seen }
        assertEquals("Library lost capture records", 100, retained.size)
        assertEquals("Library no longer references all 200 files", seen,
            retained.flatMap { listOf(it.originalUri, it.enhancedUri) }.toSet())
        // Reopen files at the end too: later captures must leave earlier items readable.
        retained.forEach { assertJpegPair(it) }
        val ordered = durations.sorted()
        Log.i(TAG, "PASS completed=100 retainedJpegs=200 medianMs=${(ordered[49] + ordered[50]) / 2} " +
            "p95Ms=${ordered[94]} maxMs=${ordered.last()} largestSampledPssKb=$largestPssKb " +
            "elapsedMs=${SystemClock.elapsedRealtime() - shotsStarted}")
    }

    companion object {
        private const val TAG = "LumoraEndurance"
    }
}
