package com.lumora.camera

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CameraCreativeStateTest : CameraUiTest() {
    @Test fun manualExposureSurvivesActivityRecreationAndViewfinderNavigation() {
        waitForStreamingShutter()
        control("专业模式").tapCenter()
        compose.waitUntil(30_000) { shutterState().endsWith(" · PRO") }
        waitForStreamingShutter()
        val lens = requireNotNull(compose.runOnIdle {
            ViewModelProvider(compose.activity)[CameraDeviceState::class.java].lastLens
        })
        assumeTrue("This test needs manual sensor support", lens.supportsManualExposure)
        compose.onNodeWithText("ISO").performScrollTo().tapCenter()
        compose.onNodeWithText("手动曝光").tapCenter()
        waitForStreamingShutter()
        val position = ln(requireNotNull(lens.isoRange).clamp(320).toFloat())
        control("感光度").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(position))
        }
        waitForStreamingShutter()
        val expectedIso = lens.clampIso(exp(position).roundToInt())
        compose.activityRule.scenario.recreate()
        waitForStreamingShutter()
        assertTrue(shutterState(), shutterState().endsWith(" · PRO"))
        control("设置").tapCenter()
        control("返回").tapCenter()
        waitForStreamingShutter()
        assertTrue(shutterState(), shutterState().endsWith(" · PRO"))
        val captured = captureNewPhoto()
        val diagnostic = requireNotNull(captured.diagnostics)
        assertTrue(diagnostic, diagnostic.contains("requestedISO=$expectedIso "))
        assertJpegPair(captured)
    }
}
