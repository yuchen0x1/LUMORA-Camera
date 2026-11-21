package com.lumora.camera.update

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.ui.AppThemeMode
import com.lumora.camera.ui.DarkColors
import com.lumora.camera.ui.LightColors
import com.lumora.camera.ui.LumoraTheme
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class UpdateDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allUpdateStatesRenderWithBothThemesAndSaveUiStateScreenshots() {
        val state = mutableStateOf<UpdateState>(UpdateState.Available(uiTestOffer))
        val mode = mutableStateOf(AppThemeMode.LIGHT)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale.value)) {
                LumoraTheme(mode.value) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    UpdateDialogContent(                        state = state.value,
                        installedVersion = "3.0.0",
                        incompatibility = { null },
                        onLater = {},
                        onDownload = {},
                        onCancelDownload = {},
                        onInstall = {}
                    )
                }
            }
        }

        val states = listOf(
            "available" to UpdateState.Available(uiTestOffer),
            "downloading" to UpdateState.Downloading(uiTestOffer, received = 128 * 1024, bytesPerSecond = 64 * 1024),
            "ready" to UpdateState.Ready(uiTestOffer),
            "installing" to UpdateState.Installing(uiTestOffer, "ui-session", InstallPhase.WAITING_FOR_RESULT)
        )
        for (theme in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            for ((name, updateState) in states) {
                compose.runOnIdle {
                    mode.value = theme
                    state.value = updateState
                    fontScale.value = 1f
                }
                compose.waitForIdle()
                // PixelCopy the native dialog window, not a possibly stale emulator display buffer.
                val bitmap = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
                    .assertIsDisplayed().captureToImage().asAndroidBitmap()
                try {
                    saveUiFixtureScreenshot(bitmap, theme, name)
                    val expected = if (theme == AppThemeMode.DARK) DarkColors.surfaceContainerHigh.toArgb()
                        else LightColors.surfaceContainerHigh.toArgb()
                    var matches = 0
                    for (y in 0 until bitmap.height step 5) for (x in 0 until bitmap.width step 5) {
                        val actual = bitmap.getPixel(x, y)
                        if (listOf(0, 8, 16).all { shift ->
                            kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) <= 2
                        }) matches++
                    }
                    assertTrue("$theme $name dialog surface token missing: $matches matching samples", matches > 100)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun dialogActionsAreDelegatedWithoutStartingInstallOrExternalIntents() {
        val state = mutableStateOf<UpdateState>(UpdateState.Available(uiTestOffer))
        val laterCalls = mutableListOf<UpdateOffer>()
        val downloadCalls = mutableListOf<UpdateOffer>()
        val cancelCalls = mutableListOf<Boolean>()
        val installCalls = mutableListOf<UpdateOffer>()
        compose.setContent {
            LumoraTheme(AppThemeMode.LIGHT) {
                UpdateDialogContent(
                    state = state.value,
                    installedVersion = "3.0.0",
                    incompatibility = { null },
                    onLater = { laterCalls.add(it) },
                    onDownload = { downloadCalls.add(it) },
                    onCancelDownload = { cancelCalls.add(true) },
                    onInstall = { installCalls.add(it) }
                )
            }
        }

        compose.onNodeWithText("立即更新").assertIsDisplayed().performClick()
        assertEquals(listOf(uiTestOffer), downloadCalls)
        compose.onNodeWithText("稍后").assertIsDisplayed().performClick()
        assertEquals(listOf(uiTestOffer), laterCalls)

        compose.runOnIdle { state.value = UpdateState.Downloading(uiTestOffer, 128 * 1024, 64 * 1024) }
        compose.onNodeWithText("取消下载").assertIsDisplayed().performClick()
        assertEquals(listOf(true), cancelCalls)

        compose.runOnIdle { state.value = UpdateState.Ready(uiTestOffer) }
        compose.onNodeWithText("安装更新").assertIsDisplayed().performClick()
        assertEquals(listOf(uiTestOffer), installCalls)
        compose.onNodeWithText("稍后").assertIsDisplayed().performClick()
        assertEquals(listOf(uiTestOffer, uiTestOffer), laterCalls)
    }

    @Test
    fun downloadingProgressExposesAccessibleByteState() {
        compose.setContent {
            LumoraTheme(AppThemeMode.LIGHT) {
                UpdateDialogContent(
                    state = UpdateState.Downloading(uiTestOffer, received = 128 * 1024, bytesPerSecond = 64 * 1024),
                    installedVersion = "3.0.0",
                    incompatibility = { null },
                    onLater = {},
                    onDownload = {},
                    onCancelDownload = {},
                    onInstall = {}
                )
            }
        }

        val description = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertTrue("Progress should announce received and total bytes: $description", description.contains("已下载") && description.contains("共"))
    }

    @Test
    fun largeFontScaleKeepsAvailableActionsUsableAndNotesScrollable() {
        val longNotesOffer = uiTestOffer.copy(
            release = uiTestOffer.release.copy(
                notes = (1..40).joinToString("\n") { "大字体更新说明第 $it 行" } + "\n大字体滚动末尾标记"
            )
        )
        val fontScale = mutableStateOf(1.8f)
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale.value)) {
                LumoraTheme(AppThemeMode.LIGHT) {
                    UpdateDialogContent(
                        state = UpdateState.Available(longNotesOffer),
                        installedVersion = "3.0.0",
                        incompatibility = { null },
                        onLater = {},
                        onDownload = {},
                        onCancelDownload = {},
                        onInstall = {}
                    )
                }
            }
        }

        compose.onNodeWithText("立即更新").assertIsDisplayed()
        compose.onNodeWithText("稍后").assertIsDisplayed()
        compose.onNodeWithText("大字体滚动末尾标记", substring = true).assertExists()
        val notes = compose.onNode(hasScrollAction())
        repeat(25) {
            val axis = notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (axis.value() < axis.maxValue()) notes.performTouchInput { swipeUp() }
        }
        val axis = notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Long notes did not scroll", axis.value() > 0f)
        assertTrue("Long notes did not reach the end", axis.maxValue() - axis.value() < 1f)
        compose.onNodeWithText("立即更新").assertIsDisplayed()
        compose.onNodeWithText("稍后").assertIsDisplayed()
    }

    private fun saveUiFixtureScreenshot(bitmap: Bitmap, theme: AppThemeMode, stateName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "uiux")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create UI fixture screenshot directory: $directory" }
        val screenshot = File(directory, "update-${theme.name.lowercase()}-$stateName.png")
        FileOutputStream(screenshot).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not write UI fixture screenshot: $screenshot" }
        }
    }

    private companion object {
        // Deliberately synthetic display data: these URIs are never opened by this presentation-only test.
        val uiTestOffer = UpdateOffer(
            release = StableRelease(
                name = "3.1.0",
                notes = "改进胶片色彩与相机操作体验。",
                published = Instant.parse("2026-01-02T03:04:05Z"),
                details = "https://example.invalid/releases/tag/v3.1.0",
                metadata = ReleaseAsset(URI("https://example.invalid/android-update.json"), 512, "a".repeat(64)),
                apk = ReleaseAsset(URI("https://example.invalid/LUMORA-Camera-3.1.0-Android-universal.apk"), 512 * 1024, "b".repeat(64))
            ),
            versionCode = 310,
            minSdk = 29,
            certificate = "c".repeat(64)
        )
    }
}
