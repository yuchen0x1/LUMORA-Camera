package com.lumora.camera

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.FilmPresets
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class CameraPermissionNavigationTest {
    // Unconfined resumptions can run composition on a repository IO worker without a Looper.
    // Queue them for the Compose test clock, which advances its scheduler on the UI thread.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val namespace = "permission-navigation-test-${UUID.randomUUID()}"
    private val repository = PhotoRepository(application, namespace)
    private val file = File(application.cacheDir, "$namespace.jpg")
    private var permissionRequests = 0

    @After fun cleanup() {
        application.deleteSharedPreferences(namespace)
        file.delete()
    }

    @Test fun galleryAndSettingsRemainAvailableWithoutCameraPermission() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }
        val uri = Uri.fromFile(file).toString()
        repository.addRecord(PhotoRecord(uri, uri, "natural", 1, sourceOnly = true))
        showNavigation(openSystemSettings = false)

        compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        compose.onNodeWithText("打开作品库").performClick()
        compose.onNodeWithContentDescription("导入").assertIsDisplayed().assertIsEnabled()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("photo-$uri")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("photo-$uri").performClick()
        compose.onNodeWithText("编辑").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithText("打开应用设置").performClick()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("音量键快门").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.runOnIdle { assertEquals(0, permissionRequests) }
        compose.onNodeWithText("开启相机").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
    }

    @Test fun recordObservationResumesOnMainLooperAfterBackgroundWrite() {
        val observed = AtomicReference<List<PhotoRecord>?>(null)
        compose.setContent {
            LaunchedEffect(repository) {
                repository.observeRecords().collect { records ->
                    assertSame("Compose effects must resume on the main looper",
                        Looper.getMainLooper(), Looper.myLooper())
                    observed.set(records)
                }
            }
        }
        compose.waitUntil(5_000) { observed.get() == emptyList<PhotoRecord>() }

        val uri = Uri.fromFile(file).toString()
        val record = PhotoRecord(uri, uri, "natural", 1, sourceOnly = true)
        runBlocking(Dispatchers.IO) {
            assertNull("The record writer must exercise a worker without a looper", Looper.myLooper())
            repository.addRecord(record)
        }
        compose.waitUntil(5_000) { observed.get() == listOf(record) }
    }

    @Test fun permanentDenialStillOffersLocalNavigationAndSystemSettings() {
        showNavigation(openSystemSettings = true)
        compose.onNodeWithText("开启相机").assertDoesNotExist()
        compose.onNodeWithText("打开作品库").assertIsEnabled()
        compose.onNodeWithText("打开应用设置").assertIsEnabled()
        compose.onNodeWithText("前往系统设置").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
    }

    private fun showNavigation(openSystemSettings: Boolean) {
        val presets = FilmPresets.load(application)
        val device = CameraDeviceState()
        val updates = UpdateViewModel(application)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LumoraNavigation(repository, presets, device, updates, cameraGranted = false, openSystemSettings) {
                    permissionRequests++
                }
            }
        }
    }
}
