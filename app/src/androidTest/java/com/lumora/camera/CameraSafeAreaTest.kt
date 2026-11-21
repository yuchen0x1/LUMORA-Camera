package com.lumora.camera

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraSafeAreaTest : CameraUiTest() {
    @Test
    fun launcherActivityAllowsLandscapeOrientation() {
        val activity = compose.activity
        val info = activity.packageManager.getActivityInfo(ComponentName(activity, MainActivity::class.java), 0)
        assertTrue(
            "Launcher orientation is locked: ${info.screenOrientation}",
            info.screenOrientation in setOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, ActivityInfo.SCREEN_ORIENTATION_SENSOR)
        )
    }

    @Test
    fun systemBarsUseTheCentralDarkSurfacePolicy() {
        compose.runOnIdle {
            val window = compose.activity.window
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            assertEquals(Color.TRANSPARENT, window.statusBarColor)
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun cameraControlsRemainInsideSystemSafeArea() {
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        checkControlsAndSettingsTap()
    }

    @Test
    fun cameraControlsRemainSafeAfterLandscapeRotation() {
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        checkControlsAndSettingsTap()
    }

    @Test
    fun proControlsDoNotPushTheShutterIntoTheLandscapeGestureArea() {
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        control("专业模式").tapCenter()
        compose.onNodeWithText("专业控制").assertIsDisplayed()
        checkControlBounds("拍照", "作品", "切换镜头")
    }

    @Test
    fun cameraMenuAndSettingsPopupStayInsideSafeAreaInANarrowWindow() {
        waitForStreamingShutter()
        narrowActivityWindow()
        waitForStreamingShutter()
        checkControlBounds("设置", "拍照", "作品", "切换镜头", "拍摄模式")

        control("拍摄模式").tapCenter()
        compose.onNodeWithText("街拍 · 快速响应").assertIsDisplayed().tapCenter()
        waitForStreamingShutter()
        assertTrue("Mode menu selection did not apply", shutterState().endsWith(" · STREET_PROFILE"))

        val previous = repository.previewQuality
        val next = if (previous == "高质量") "标准" else "高质量"
        try {
            control("设置").tapCenter()
            compose.onNodeWithText("预览分辨率").tapCenter()
            compose.onNodeWithTag("settings-预览分辨率-$next").tapCenter()
            compose.waitUntil(timeoutMillis = 5_000) { repository.previewQuality == next }
            assertTextBoundsWithinSafeArea("设置")
        } finally {
            repository.previewQuality = previous
        }
    }

    @Test
    fun galleryDeleteDialogRemainsUsableInANarrowWindow() {
        waitForStreamingShutter()
        narrowActivityWindow()
        waitForStreamingShutter()
        val photo = captureNewPhoto()
        waitForStreamingShutter()
        control("作品").tapCenter()
        val tile = compose.onNodeWithTag("photo-${photo.enhancedUri}")
        compose.waitUntil(timeoutMillis = 10_000) { tile.isDisplayed() }
        tile.tapCenter()
        compose.waitUntil(timeoutMillis = 5_000) { control("删除").isDisplayed() }
        control("删除").tapCenter()

        compose.onNodeWithText("从作品中移除？").assertIsDisplayed()
        compose.onNodeWithText("移除").assertIsDisplayed()
        compose.onNodeWithText("取消").tapCenter()
        compose.onAllNodesWithText("从作品中移除？").assertCountEquals(0)
    }

    @Test
    fun foldPaneSelectionHandlesFoldsAtWindowEdges() {
        assertEquals(CameraPane(520, 1_000), unobstructedPane(1_000, 400, 520))
        assertEquals(CameraPane(0, 520), unobstructedPane(1_000, 520, 620))
        assertEquals(CameraPane(120, 500), unobstructedPane(500, 0, 120))
        assertEquals(CameraPane(0, 400), unobstructedPane(500, 400, 500))
        assertEquals(CameraPane(240, 500), unobstructedPane(500, 240, 240))
        assertEquals(CameraPane(0, 500), unobstructedPane(500, 0, 0))
    }

    private fun narrowActivityWindow() {
        val targetWidth = compose.runOnIdle {
            (360 * compose.activity.resources.displayMetrics.density).roundToInt()
        }
        compose.runOnIdle {
            compose.activity.window.setLayout(targetWidth, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { compose.activity.window.decorView.width in 1..targetWidth }
        }
        val actualWidth = compose.runOnIdle { compose.activity.window.decorView.width }
        assertTrue("Activity window was not narrowed: target=$targetWidth actual=$actualWidth", actualWidth <= targetWidth + 1)
    }

    private fun assertTextBoundsWithinSafeArea(text: String) {
        val safe = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(
                Type.systemBars() or Type.displayCutout() or Type.systemGestures()
            )
            Rect(insets.left.toFloat(), insets.top.toFloat(),
                (decor.width - insets.right).toFloat(), (decor.height - insets.bottom).toFloat())
        }
        val node = compose.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode()
        val position = node.positionInWindow
        val target = Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
        assertTrue("$text lies outside the safe window: target=$target safe=$safe",
            target.left >= safe.left - 1f && target.top >= safe.top - 1f &&
                target.right <= safe.right + 1f && target.bottom <= safe.bottom + 1f)
    }

    private fun rotate(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.activity.resources.configuration.orientation == expected
        }
        waitForStreamingShutter()
    }

    private fun checkControlsAndSettingsTap() {
        checkControlBounds("设置", "拍照", "作品", "切换镜头", "拍摄模式")
        control("设置").tapCenter()
        compose.onNodeWithText("设置").assertIsDisplayed()
        assertTextBoundsWithinSafeArea("设置")
    }

    private fun checkControlBounds(vararg controls: String) {
        val safe = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(
                Type.systemBars() or Type.displayCutout() or Type.systemGestures()
            )
            Rect(insets.left.toFloat(), insets.top.toFloat(),
                (decor.width - insets.right).toFloat(), (decor.height - insets.bottom).toFloat())
        }
        for (description in controls) {
            val node = control(description).assertIsDisplayed().fetchSemanticsNode()
            // Position, layout size and window insets all use physical pixels in the activity window.
            val position = node.positionInWindow
            val size = node.size
            val target = Rect(position.x, position.y, position.x + size.width, position.y + size.height)
            val visible = node.boundsInWindow
            Log.i("CameraSafeAreaTest", "$description targetPx=$target visiblePx=$visible safePx=$safe")
            assertTrue("$description target $target outside safe window $safe",
                target.left >= safe.left - 1f && target.top >= safe.top - 1f &&
                    target.right <= safe.right + 1f && target.bottom <= safe.bottom + 1f)
            assertTrue("$description is clipped: target=$target visible=$visible",
                target.width > 0 && target.height > 0 && visible.width >= target.width - 1f &&
                    visible.height >= target.height - 1f)
            if (description == "设置") {
                // Settings moves to the second row in compact layouts; the mode stays in the first.
                val firstRowTop = control("拍摄模式").fetchSemanticsNode().positionInWindow.y
                val toolbarPadding = with(compose.density) { 8.dp.toPx() }
                assertTrue("Extra gap above toolbar: firstRowTop=$firstRowTop safe=$safe",
                    firstRowTop - safe.top <= toolbarPadding + 1f)
            }
        }
    }
}
