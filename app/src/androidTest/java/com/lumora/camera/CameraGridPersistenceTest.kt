package com.lumora.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGridPersistenceTest : CameraUiTest() {
    @Test fun unknownGridSurvivesHistogramToggleUntilGridSelection() {
        withPreservedAidsPreferences { prefs ->
            assertTrue(prefs.edit()
                .putString("compositionGrid", "future-grid")
                .putBoolean("goldenGrid", true)
                .putBoolean("histogram", false)
                .commit())
            compose.activityRule.scenario.recreate()
            waitForStreamingShutter()
            control("拍摄工具").tapCenter()
            compose.onNodeWithText("黄金分割").performScrollTo().assertIsSelected()

            compose.onNodeWithText("直方图").performScrollTo().tapCenter()
            compose.onNodeWithText("直方图").assertIsSelected()
            compose.runOnIdle {
                assertTrue(prefs.getBoolean("histogram", false))
                assertEquals("future-grid", prefs.getString("compositionGrid", null))
                assertTrue(prefs.getBoolean("goldenGrid", false))
            }

            compose.onNodeWithText("三分网格").performScrollTo().tapCenter()
            compose.onNodeWithText("三分网格").assertIsSelected()
            compose.runOnIdle {
                assertEquals("THIRDS", prefs.getString("compositionGrid", null))
                assertFalse(prefs.getBoolean("goldenGrid", true))
                assertTrue(prefs.getBoolean("histogram", false))
            }
        }
    }

    @Test fun thirdsGridSurvivesSettingsNavigationAndActivityRecreation() {
        withPreservedAidsPreferences { _ ->
            waitForStreamingShutter()
            control("拍摄工具").tapCenter()
            compose.onNodeWithText("三分网格").performScrollTo().tapCenter()
            compose.onNodeWithText("三分网格").assertIsSelected()
            control("收起面板").tapCenter()
            control("设置").tapCenter()
            control("返回").tapCenter()
            waitForStreamingShutter()
            control("拍摄工具").tapCenter()
            compose.onNodeWithText("三分网格").performScrollTo().assertIsSelected()
            control("收起面板").tapCenter()

            compose.activityRule.scenario.recreate()
            waitForStreamingShutter()
            control("拍摄工具").tapCenter()
            compose.onNodeWithText("三分网格").performScrollTo().assertIsSelected()

            listOf("黄金分割", "三分网格").forEach { grid ->
                control("收起面板").tapCenter()
                control("设置").tapCenter()
                compose.onNodeWithText("构图网格").performScrollTo().tapCenter()
                compose.onNodeWithText(grid).tapCenter()
                control("返回").tapCenter()
                waitForStreamingShutter()
                control("拍摄工具").tapCenter()
                compose.onNodeWithText(grid).performScrollTo().assertIsSelected()
            }
        }
    }

    private fun withPreservedAidsPreferences(test: (SharedPreferences) -> Unit) {
        val prefs = context.getSharedPreferences("lumora_preferences", Context.MODE_PRIVATE)
        val keys = listOf("goldenGrid", "compositionGrid", "level", "histogram", "clipping", "peaking")
        val previous = prefs.all.filterKeys { it in keys }
        try {
            test(prefs)
        } finally {
            // Restore only this test's settings; never clear the user's preferences or photos.
            val editor = prefs.edit()
            keys.forEach { key ->
                when (val value = previous[key]) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            editor.commit()
        }
    }
}
