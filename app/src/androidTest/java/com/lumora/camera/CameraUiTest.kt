package com.lumora.camera

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain

abstract class CameraUiTest {
    protected val compose = createAndroidComposeRule<MainActivity>()
    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val repository by lazy { PhotoRepository(context) }

    @get:Rule
    val cameraRule: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
        .around(compose)

    protected fun control(description: String) = compose.onNodeWithContentDescription(description)

    protected fun SemanticsNodeInteraction.tapCenter() {
        assertIsDisplayed().performTouchInput { click(center) }
    }

    protected fun waitForStreamingShutter() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(hasContentDescription("拍照") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.runOnUiThread {
                    compose.activity.window.decorView.findCameraPreview()?.previewStreamState?.value ==
                        PreviewView.StreamState.STREAMING
                }
        }
    }

    private fun View.findCameraPreview(): PreviewView? {
        if (this is PreviewView) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).findCameraPreview()?.let { return it }
        }
        return null
    }

    protected fun shutterState(): String =
        control("拍照").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()

    protected fun captureNewPhoto(): PhotoRecord {
        waitForStreamingShutter()
        val previous = repository.getRecords().map { it.originalUri }.toSet()
        control("拍照").tapCenter()
        return awaitPhoto("Shutter did not produce a new original and processed result") {
            it.originalUri !in previous && it.originalUri.isNotBlank() &&
                it.enhancedUri.isNotBlank() && it.enhancedUri != it.originalUri
        }
    }

    protected fun awaitPhoto(message: String, matches: (PhotoRecord) -> Boolean): PhotoRecord {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            repository.getRecords().firstOrNull(matches)?.let { return it }
            SystemClock.sleep(150)
        }
        throw AssertionError(message)
    }

    protected data class Jpeg(val width: Int, val height: Int, val bytes: Long)

    protected fun assertJpegPair(photo: PhotoRecord): Jpeg {
        assertNotEquals(photo.originalUri, photo.enhancedUri)
        val original = jpeg(photo.originalUri)
        val output = jpeg(photo.enhancedUri)
        assertEquals("Processed width changed", original.width, output.width)
        assertEquals("Processed height changed", original.height, output.height)
        return original
    }

    private fun jpeg(value: String): Jpeg {
        val resolver = context.contentResolver
        val uri = Uri.parse(value)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        checkNotNull(resolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
        val bytes = checkNotNull(resolver.openFileDescriptor(uri, "r")).use { it.statSize }
        assertEquals("File header must be JPEG", "image/jpeg", bounds.outMimeType)
        assertEquals("Provider must publish JPEG", "image/jpeg", resolver.getType(uri))
        assertTrue("Unreadable JPEG geometry or byte length", bounds.outWidth > 0 && bounds.outHeight > 0 && bytes > 0)
        return Jpeg(bounds.outWidth, bounds.outHeight, bytes)
    }

    protected fun sha256(value: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        checkNotNull(context.contentResolver.openInputStream(Uri.parse(value))).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest()
    }
}
