package com.lumora.camera

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
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
                // The first camera toolbar begins at the safe top. Detect applying that inset twice.
                val toolbarPadding = with(compose.density) { 8.dp.toPx() }
                assertTrue("Extra gap above toolbar: target=$target safe=$safe", target.top - safe.top <= toolbarPadding + 1f)
            }
        }
    }
}
