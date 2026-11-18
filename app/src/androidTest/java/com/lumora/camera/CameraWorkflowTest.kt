package com.lumora.camera

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumora.camera.data.OptimizationStrength
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraWorkflowTest : CameraUiTest() {
    @Test
    fun shutterToGalleryComparisonAndEditorRetainsOriginal() {
        val first = captureNewPhoto()
        val source = assertJpegPair(first)
        val facts = requireNotNull(first.diagnostics)
        assertTrue(facts, facts.contains("input=${source.width}x${source.height} bytes=${source.bytes} mime=image/jpeg"))
        assertTrue(facts, facts.contains("output=${source.width}x${source.height} "))
        val sourceDigest = sha256(first.originalUri)
        val firstRenderDigest = sha256(first.enhancedUri)
        // CameraOutput commits the finished record just before it re-enables the gallery.
        waitForStreamingShutter()
        control("作品").tapCenter()
        val tile = "photo-${first.enhancedUri}"
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(tile)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tile).tapCenter()
        val before = compose.onNodeWithTag("detail-before")
        val after = compose.onNodeWithTag("detail-after")
        before.tapCenter()
        before.assertIsSelected()
        after.tapCenter()
        after.assertIsSelected()
        control("收藏作品").tapCenter()
        awaitPhoto("Favorite was not saved") { it.enhancedUri == first.enhancedUri && it.favorite }
        control("取消收藏作品").assertIsDisplayed()
        compose.onNodeWithText("编辑").performScrollTo().tapCenter()
        val newStrength = OptimizationStrength.entries.first { it != first.strength }
        val strength = compose.onNodeWithTag("editor-strength-${newStrength.name}")
        strength.performScrollTo().tapCenter()
        strength.assertIsSelected()
        control("撤销调整").tapCenter()
        compose.onNodeWithTag("editor-strength-${first.strength.name}").assertIsSelected()
        control("重做调整").tapCenter()
        strength.assertIsSelected()
        compose.onNodeWithText("保存新版本").tapCenter()
        val edited = awaitPhoto("Editor did not append a version referencing this capture") {
            it.originalUri == first.originalUri && it.enhancedUri != first.enhancedUri && it.parentUri == first.enhancedUri
        }
        assertEquals(newStrength, edited.strength)
        assertJpegPair(edited)
        assertArrayEquals("Editor changed original bytes", sourceDigest, sha256(edited.originalUri))
        assertArrayEquals("Editor changed first render", firstRenderDigest, sha256(first.enhancedUri))
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("detail-after")).fetchSemanticsNodes().isNotEmpty()
        }
        control("返回").tapCenter()
        compose.onNodeWithTag("photo-${edited.enhancedUri}").assertIsDisplayed()
        control("返回").tapCenter()
        waitForStreamingShutter()
    }

    @Test
    fun cameraModesLensSwitchAndResumeProduceCapturablePreview() {
        waitForStreamingShutter()
        assertTrue(shutterState(), shutterState().endsWith(" · AUTO"))
        val lens = compose.runOnIdle { ViewModelProvider(compose.activity)[CameraDeviceState::class.java].lastLens }
        requireNotNull(lens)
        if (lens.availableCameras.size > 1) {
            repeat(4) {
                val previous = shutterState()
                control("切换镜头").tapCenter()
                compose.waitUntil(timeoutMillis = 30_000) {
                    val current = shutterState()
                    current.endsWith(" · AUTO") && current != previous
                }
                waitForStreamingShutter()
            }
        } else {
            control("切换镜头").assertIsNotEnabled()
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForStreamingShutter()
        control("拍摄模式").tapCenter()
        val currentLens = requireNotNull(compose.runOnIdle {
            ViewModelProvider(compose.activity)[CameraDeviceState::class.java].lastLens
        })
        for ((extension, available) in listOf("HDR 扩展" to currentLens.hdrExtensionAvailable,
            "夜景扩展" to currentLens.nightExtensionAvailable, "人像扩展" to currentLens.portraitExtensionAvailable)) {
            if (available) compose.onNodeWithText(extension).assertIsEnabled()
            else compose.onNodeWithText("$extension · 镜头不可用").assertIsNotEnabled()
        }
        compose.onNodeWithText("街拍 · 快速响应").performScrollTo().tapCenter()
        compose.waitUntil(timeoutMillis = 30_000) { shutterState().endsWith(" · STREET_PROFILE") }
        val street = captureNewPhoto()
        assertJpegPair(street)
        assertTrue(street.diagnostics.orEmpty(), street.diagnostics.orEmpty().contains(" mode=STREET_PROFILE "))
    }

    @Test
    fun settingsShowActiveLensAndKeepPreferenceWhenReopened() {
        waitForStreamingShutter()
        val lens = requireNotNull(compose.runOnIdle {
            ViewModelProvider(compose.activity)[CameraDeviceState::class.java].lastLens
        })
        control("设置").tapCenter()
        val toggle = compose.onNodeWithText("音量键快门")
        val before = toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        requireNotNull(before)
        toggle.tapCenter()
        try {
            for (title in listOf("拍摄", "摄影辅助", "成片", "存储", "设备能力", "${lens.lensFacing} · ID ${lens.cameraId}", "关于", "2.0.0")) {
                compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
            }
            control("返回").tapCenter()
            waitForStreamingShutter()
            control("设置").tapCenter()
            val after = requireNotNull(toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
            assertTrue("Volume-shutter preference lost", after != before)
        } finally {
            val setting = CameraSettings(context)
            setting.volumeShutter = before == ToggleableState.On
        }
    }
}
