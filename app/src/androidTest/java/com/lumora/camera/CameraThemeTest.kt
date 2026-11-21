package com.lumora.camera

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.FilmPresets
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.ui.AppThemeMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Real MainActivity/CameraX acceptance tests, not a setContent theme demo.
 * Samples and appended edits are deliberately retained. No import, share, update or remove action
 * is confirmed. Screenshots are device-local: externalFilesDir/uiux/{light,dark}-<surface>.png.
 * Run sequentially: the night-mode test temporarily changes device night mode and restores it.
 */
@RunWith(AndroidJUnit4::class)
class CameraThemeTest : CameraUiTest() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val prefs get() = context.getSharedPreferences("lumora_preferences", Context.MODE_PRIVATE)
    private val nightManager get() = context.getSystemService(UiModeManager::class.java)
    private var originalTheme: String? = null
    private var hadTheme = false
    private var originalNight = UiModeManager.MODE_NIGHT_AUTO
    private lateinit var oldRecords: List<PhotoRecord>

    companion object {
        // ClassRule is outside CameraUiTest's Activity RuleChain, so even the initial launch is
        // offline when a user's normal update frequency is DAILY/WEEKLY. Never clear app data.
        @ClassRule @JvmField
        val noAutomaticUpdates = object : ExternalResource() {
            private var hadFrequency = false
            private var frequency: String? = null
            override fun before() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val prefs = context.getSharedPreferences("lumora_updates", Context.MODE_PRIVATE)
                hadFrequency = prefs.contains("frequency")
                frequency = prefs.getString("frequency", null)
                check(prefs.edit().putString("frequency", "MANUAL").commit())
            }
            override fun after() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val editor = context.getSharedPreferences("lumora_updates", Context.MODE_PRIVATE).edit()
                if (hadFrequency) editor.putString("frequency", frequency) else editor.remove("frequency")
                check(editor.commit())
            }
        }
    }

    @Before
    fun rememberUserState() {
        hadTheme = prefs.contains("themeMode")
        originalTheme = prefs.getString("themeMode", null)
        originalNight = nightManager.nightMode
        oldRecords = repository.getRecords()
        waitForStreamingShutter()
    }

    @After
    fun restoreUserStateWithoutRemovingSamples() {
        try {
            if (nightManager.nightMode != originalNight) setNightMode(originalNight)
        } finally {
            val editor = prefs.edit()
            if (hadTheme) editor.putString("themeMode", originalTheme) else editor.remove("themeMode")
            assertTrue("Could not restore theme preference", editor.commit())
        }
        val remaining = repository.getRecords().associateBy { it.enhancedUri }
        oldRecords.forEach { assertEquals("Existing work was changed or removed", it, remaining[it.enhancedUri]) }
    }

    @Test
    fun themeMenuSelectionPersistsAcrossRecreationAndAcceptsKeyboardInput() {
        control("设置").tapCenter()
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.SYSTEM)) {
            chooseTheme(mode)
            compose.activityRule.scenario.recreate()
            waitForSettings()
            assertEquals(mode, CameraSettings(context).themeMode)
            assertThemeMenu(mode)
            assertPageBackground(resolved(mode))
            assertBars(resolved(mode) == AppThemeMode.LIGHT)
        }
        val choice = compose.onNodeWithTag("settings-choice-应用主题").performScrollTo()
        compose.waitUntil(10_000) { compose.runOnUiThread { compose.activity.hasWindowFocus() } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        choice.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        choice.assertIsFocused()
        // Actual Android keyboard events, not performClick masquerading as a keyboard test.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("settings-应用主题-跟随系统").assertIsDisplayed().assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onAllNodesWithTag("settings-应用主题-跟随系统").assertCountEquals(0)
        control("返回").tapCenter()
        waitForStreamingShutter()
        assertBars(lightIconsOnLightSurface = false)
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    fun systemModeFollowsNightNoAndYesButExplicitModesDoNot() {
        // API 29's emulator shell identity cannot persistently change UI mode; API 30+ is verified.
        control("设置").tapCenter()
        for (mode in listOf(AppThemeMode.SYSTEM, AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            chooseTheme(mode)
            for (night in listOf(false, true, false)) {
                setNightMode(if (night) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                compose.waitUntil(15_000) {
                    compose.runOnUiThread {
                        val actual = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        actual == if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                }
                waitForSettings()
                assertEquals(mode, CameraSettings(context).themeMode)
                assertThemeMenu(mode)
                val expected = if (mode == AppThemeMode.SYSTEM) {
                    if (night) AppThemeMode.DARK else AppThemeMode.LIGHT
                } else mode
                assertPageBackground(expected)
                assertBars(expected == AppThemeMode.LIGHT)
                control("返回").tapCenter()
                waitForStreamingShutter()
                assertBars(lightIconsOnLightSurface = false)
                control("设置").tapCenter()
            }
        }
    }

    @Test
    fun lightAndDarkTraverseCameraSettingsGalleryDialogsAndSaveRealVersions() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            waitForStreamingShutter()
            control("设置").tapCenter()
            chooseTheme(mode)
            assertPageBackground(mode)
            assertBars(mode == AppThemeMode.LIGHT)
            screenshot(mode, "surface")
            val quality = repository.previewQuality
            compose.onNodeWithTag("settings-choice-预览分辨率").performScrollTo().tapCenter()
            compose.onNodeWithTag("settings-预览分辨率-$quality").assertIsSelected()
            screenshot(mode, "settings-dropdown")
            // A real system Back press dismisses the popup window; assert it closes and the page stays usable.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings-预览分辨率-$quality").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("settings-choice-预览分辨率").assertIsDisplayed()
            assertEquals(quality, repository.previewQuality)
            control("返回").tapCenter()
            val sample = captureNewPhoto()
            assertFalse("Theme sample must come from CameraX, not import", sample.imported)
            assertJpegPair(sample)
            visitCameraPanels(mode)
            visitWorkAndSave(sample, mode)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun font200InNarrowActivityKeepsSettingsDeviceInfoAndEditorReachable() {
        val previousScale = android.provider.Settings.System.getString(
            context.contentResolver, android.provider.Settings.System.FONT_SCALE)
        val windowSize = compose.runOnIdle { compose.activity.window.attributes.let { it.width to it.height } }
        val originalFontScale = compose.runOnIdle { compose.activity.resources.configuration.fontScale }
        try {
            // Exercise manual DARK while the XML day resources remain LIGHT.
            if (Build.VERSION.SDK_INT >= 30) {
                setNightMode(UiModeManager.MODE_NIGHT_NO)
                compose.waitUntil(15_000) {
                    compose.runOnUiThread {
                        compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                            Configuration.UI_MODE_NIGHT_NO
                    }
                }
            }
            setSystemFontScale("2.0")
            compose.waitUntil(15_000) {
                compose.runOnUiThread { compose.activity.resources.configuration.fontScale >= 1.99f }
            }
            // Android already recreates for the font configuration change; a second explicit
            // recreate races ActivityScenario teardown on API 29.
            compose.waitForIdle()
            val activity = compose.activity
            compose.runOnIdle {
                // Keep MATCH_PARENT on a real narrow display so this remains a full-screen
                // activity rather than a synthetic floating window of the same pixel width.
                if (activity.window.decorView.width / activity.resources.displayMetrics.density > 361f) {
                    activity.window.setLayout((360 * activity.resources.displayMetrics.density).roundToInt(),
                        ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    activity.window.decorView.width / activity.resources.displayMetrics.density <= 361f
                }
            }
            assertTrue("Compose layout did not receive fontScale=2.0",
                compose.onRoot().fetchSemanticsNode().layoutInfo.density.fontScale >= 1.99f)
            assertTrue("Activity did not become narrow", compose.onRoot().fetchSemanticsNode().size.width /
                compose.density.density <= 361f)
            waitForStreamingShutter()
            for (name in listOf("设置", "拍照", "作品", "拍摄模式", "胶片风格")) assertTouchTarget(control(name))
            val sample = captureNewPhoto()
            assertJpegPair(sample)
            for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
                waitForStreamingShutter()
                control("设置").tapCenter()
                chooseTheme(mode)
                assertBars(mode == AppThemeMode.LIGHT)
                assertTouchTarget(compose.onNodeWithTag("settings-choice-应用主题").performScrollTo())
                screenshot(mode, "font200-settings")
                for (text in listOf("设备能力", "最大 JPEG 规格", "RAW / DNG", "关于")) {
                    compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
                    if (text == "RAW / DNG") screenshot(mode, "font200-device-info")
                }
                control("返回").tapCenter()
                waitForStreamingShutter()
                visitWorkAndSave(sample, mode, largeFont = true)
            }
        } finally {
            try {
                compose.runOnIdle { compose.activity.window.setLayout(windowSize.first, windowSize.second) }
            } finally {
                setSystemFontScale(previousScale)
            }
            compose.waitUntil(15_000) {
                compose.runOnUiThread {
                    abs(compose.activity.resources.configuration.fontScale - originalFontScale) < .01f
                }
            }
            compose.waitForIdle()
        }
    }

    private fun chooseTheme(mode: AppThemeMode) {
        compose.onNodeWithTag("settings-choice-应用主题").performScrollTo().tapCenter()
        compose.onNodeWithTag("settings-应用主题-${mode.label}").tapCenter()
        compose.waitForIdle()
        assertEquals(mode, CameraSettings(context).themeMode)
        assertThemeMenu(mode)
    }

    private fun assertThemeMenu(mode: AppThemeMode) {
        val row = compose.onNodeWithTag("settings-choice-应用主题").performScrollTo()
        assertEquals(mode.label, row.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        row.tapCenter()
        AppThemeMode.entries.forEach {
            val item = compose.onNodeWithTag("settings-应用主题-${it.label}").assertIsDisplayed()
            if (it == mode) item.assertIsSelected() else item.assertIsNotSelected()
        }
        // Select the same value to dismiss without modifying any other preference.
        compose.onNodeWithTag("settings-应用主题-${mode.label}").tapCenter()
    }

    private fun waitForSettings() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun visitCameraPanels(mode: AppThemeMode) {
        waitForStreamingShutter()
        assertBars(lightIconsOnLightSurface = false)
        val viewfinder = control("取景器").assertIsEnabled()
        assertEquals("中心对焦", viewfinder.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        viewfinder.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(requireNotNull(it)()) }
        control("拍摄模式").tapCenter()
        compose.onNodeWithText("自动").assertIsDisplayed()
        screenshot(mode, "camera-mode")
        compose.onNodeWithText("自动").tapCenter()
        control("专业模式").tapCenter()
        compose.waitUntil(30_000) { shutterState().endsWith(" · PRO") }
        waitForStreamingShutter()
        compose.onNodeWithText("专业控制").assertIsDisplayed()
        screenshot(mode, "camera-pro")
        control("收起面板").tapCenter()
        control("拍摄工具").tapCenter()
        compose.onNodeWithText("倒计时").performScrollTo().assertIsDisplayed()
        screenshot(mode, "camera-tools")
        control("收起面板").tapCenter()
        control("胶片风格").tapCenter()
        val slider = control("风格浓度").performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Film slider must expose its formatted value",
            slider.config.getOrNull(SemanticsProperties.StateDescription).orEmpty().isNotBlank())
        assertTrue(slider.config.contains(SemanticsProperties.ProgressBarRangeInfo))
        screenshot(mode, "camera-film")
        control("收起面板").tapCenter()
        control("拍摄模式").tapCenter()
        compose.onNodeWithText("自动").tapCenter()
        waitForStreamingShutter()
        assertBars(lightIconsOnLightSurface = false)
    }

    private fun visitWorkAndSave(sample: PhotoRecord, mode: AppThemeMode, largeFont: Boolean = false) {
        val originalDigest = sha256(sample.originalUri)
        val renderDigest = sha256(sample.enhancedUri)
        control("作品").tapCenter()
        val tile = scrollToPhoto(sample)
        assertPageBackground(mode)
        assertBars(mode == AppThemeMode.LIGHT)
        screenshot(mode, if (largeFont) "font200-gallery" else "gallery")
        tile.tapCenter()
        compose.onNodeWithTag("detail-before").performScrollTo().tapCenter()
        compose.onNodeWithTag("detail-before").assertIsSelected()
        compose.onNodeWithTag("detail-after").tapCenter()
        compose.onNodeWithTag("detail-after").assertIsSelected()
        assertPageBackground(mode)
        screenshot(mode, "detail")
        control("删除").tapCenter()
        compose.onNodeWithText("从作品中移除？").assertIsDisplayed()
        compose.onNodeWithText("移除").assertIsDisplayed() // Never confirm removal, even of our own sample.
        screenshot(mode, "remove-dialog")
        compose.onNodeWithText("取消").tapCenter()
        compose.onAllNodesWithText("从作品中移除？").assertCountEquals(0)
        compose.onNodeWithText("编辑").performScrollTo().tapCenter()
        assertPageBackground(mode, anchor = "取消")
        val style = FilmPresets.load(context).first { it.id == sample.styleId }
        val styleButton = compose.onNode(hasText(style.name) and hasClickAction()).performScrollTo()
        styleButton.tapCenter()
        screenshot(mode, "editor-style-dropdown")
        // Anchor and menu contain the same style name; only the popup item may be selected.
        compose.onNode(hasText(style.name) and hasAnyAncestor(isPopup())).performScrollTo().tapCenter()
        control("选择调整项").performScrollTo().tapCenter()
        compose.onNodeWithText("对比度").performScrollTo().assertIsDisplayed()
        screenshot(mode, "editor-adjustment-dropdown")
        compose.onNodeWithText("对比度").tapCenter()
        val next = OptimizationStrength.entries.first { it != sample.strength }
        if (largeFont) {
            OptimizationStrength.entries.forEach {
                assertTouchTarget(compose.onNodeWithTag("editor-strength-${it.name}").performScrollTo())
            }
            assertTouchTarget(control("取消"))
            assertTouchTarget(compose.onNodeWithText("保存新版本"))
        }
        val strength = compose.onNodeWithTag("editor-strength-${next.name}").performScrollTo()
        strength.tapCenter()
        strength.assertIsSelected()
        control("取消").tapCenter()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").assertIsDisplayed()
        screenshot(mode, "discard-dialog")
        compose.onNodeWithText("继续编辑").tapCenter()
        compose.onAllNodesWithText("放弃未保存的修改？").assertCountEquals(0)
        strength.performScrollTo().assertIsSelected()
        screenshot(mode, if (largeFont) "font200-editor" else "editor")
        val before = repository.getRecords().map { it.enhancedUri }.toSet()
        compose.onNodeWithText("保存新版本").assertIsEnabled().tapCenter()
        val edited = awaitPhoto("Theme editor did not append a real version") {
            it.originalUri == sample.originalUri && it.parentUri == sample.enhancedUri && it.enhancedUri !in before
        }
        assertEquals(next, edited.strength)
        assertJpegPair(edited)
        assertArrayEquals("Editor changed the original", originalDigest, sha256(sample.originalUri))
        assertArrayEquals("Editor overwrote the prior render", renderDigest, sha256(sample.enhancedUri))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail-after").fetchSemanticsNodes().isNotEmpty() }
        control("返回").tapCenter()
        scrollToPhoto(edited).assertIsDisplayed()
        control("返回").tapCenter()
        waitForStreamingShutter()
    }

    private fun scrollToPhoto(photo: PhotoRecord): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        val index = repository.getRecords().indexOfFirst { it.enhancedUri == photo.enhancedUri }
        check(index >= 0) { "Test capture/version is missing from the repository" }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        return compose.onNodeWithTag("photo-${photo.enhancedUri}").assertIsDisplayed()
    }

    private fun resolved(mode: AppThemeMode): AppThemeMode = if (mode != AppThemeMode.SYSTEM) mode else {
        val night = compose.runOnIdle {
            compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        if (night == Configuration.UI_MODE_NIGHT_YES) AppThemeMode.DARK else AppThemeMode.LIGHT
    }

    private fun assertBars(lightIconsOnLightSurface: Boolean) = compose.runOnIdle {
        val window = compose.activity.window
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        // true = dark foreground icons on a LIGHT surface; camera always requires false.
        assertEquals("Status-bar foreground policy", lightIconsOnLightSurface, bars.isAppearanceLightStatusBars)
        assertEquals("Navigation-bar foreground policy", lightIconsOnLightSurface, bars.isAppearanceLightNavigationBars)
    }

    private fun assertPageBackground(mode: AppThemeMode, anchor: String = "返回") {
        compose.waitForIdle()
        val bounds = control(anchor).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage()
        // A clear patch inside the navigation button's left padding. No text, photo, system bar,
        // popup, scrim or rounded photo matte is mistaken for the page's background.
        val x = (bounds.left + with(compose.density) { 2.dp.toPx() }).roundToInt()
        val y = bounds.center.y.roundToInt()
        val expected = if (mode == AppThemeMode.LIGHT) 0xFFF8F9F5.toInt() else 0xFF171918.toInt()
        // Avoid allocating a full-screen IntArray on the API 29 emulator's 16 MiB Java heap.
        val pixels = IntArray(9)
        image.readPixels(pixels, startX = x, startY = y - 1, width = 3, height = 3, stride = 3)
        for (actual in pixels) {
            for (shift in listOf(0, 8, 16)) assertTrue(
                "${mode.name} page background at ($x,$y): expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}",
                abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) <= 2)
        }
    }

    private fun assertTouchTarget(interaction: SemanticsNodeInteraction) {
        val node = interaction.assertIsDisplayed().fetchSemanticsNode()
        val min = with(compose.density) { 48.dp.toPx() }
        val bounds = node.boundsInWindow
        assertTrue("Core semantic target is below 48dp or clipped: ${node.config} bounds=$bounds minimum=$min",
            bounds.width >= min - 1 && bounds.height >= min - 1)
    }

    private fun screenshot(mode: AppThemeMode, surface: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400) // Allow Android popup transitions and the compositor to present the settled frame.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation screenshot failed" }
        try {
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "uiux")
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "${mode.name.lowercase()}-$surface.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Empty screenshot: $file", file.length() > 0)
            if (surface in setOf("surface", "font200-settings", "font200-device-info")) {
                assertStatusBarBackground(bitmap, mode)
            }
            Log.i("CameraThemeTest", "screenshot=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertStatusBarBackground(bitmap: Bitmap, mode: AppThemeMode) {
        val (top, fullWidthWindow) = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val inset = WindowInsetsCompat.toWindowInsetsCompat(checkNotNull(decor.rootWindowInsets), decor)
                .getInsets(WindowInsetsCompat.Type.statusBars()).top
            inset to (decor.width == bitmap.width &&
                compose.activity.window.attributes.width == ViewGroup.LayoutParams.MATCH_PARENT)
        }
        assertTrue("Expected a visible status bar", top > 2)
        // Screen coordinates, not Compose coordinates: the defect was outside the content.
        // The centered patch avoids the clock and status icons on these portrait AVDs.
        val x = bitmap.width / 2
        val y = top / 2
        val expected = if (mode == AppThemeMode.LIGHT) 0xFFF8F9F5.toInt() else 0xFF171918.toInt()
        for (dy in -1..1) for (dx in -1..1) {
            val actual = bitmap.getPixel(x + dx, y + dy)
            for (shift in listOf(0, 8, 16)) assertTrue(
                "${mode.name} status-bar backing at ($x,$y): expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}",
                abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) <= 2)
        }
        // Check display-wide clock pixels only when this window fills the display. The
        // floating-window harness is recorded separately; CI also uses a real 360dp display.
        if (fullWidthWindow) {
            var foreground = 0
            for (sy in 1 until top - 1 step 2) {
                for (sx in bitmap.width / 20 until bitmap.width / 3 step 2) {
                    val actual = bitmap.getPixel(sx, sy)
                    val channels = listOf(0, 8, 16).map { (actual shr it) and 255 }
                    if (if (mode == AppThemeMode.LIGHT) channels.all { it < 128 }
                        else channels.all { it > 180 }) foreground++
                }
            }
            assertTrue("${mode.name} status clock has no high-contrast foreground pixels", foreground > 10)
        }
    }

    private fun setSystemFontScale(value: String?) {
        val command = if (value == null) "settings delete system font_scale"
            else "settings put system font_scale ${value.toFloat()}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun setNightMode(mode: Int) {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
        try {
            nightManager.setNightMode(mode)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (nightManager.nightMode != mode && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertEquals("Device did not apply/restore night mode", mode, nightManager.nightMode)
    }
}
