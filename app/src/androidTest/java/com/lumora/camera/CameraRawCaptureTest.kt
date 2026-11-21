package com.lumora.camera

import android.content.ContentUris
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore.Images.Media
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumora.camera.camera.DeviceCapabilityManager
import com.lumora.camera.camera.LensOption
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraRawCaptureTest : CameraUiTest() {
    @Test
    fun rawModeSavesOnlyRealDngOrIsClearlyDisabledWhenUnsupported() {
        waitForStreamingShutter()
        val source = ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
        val manager = DeviceCapabilityManager(context)
        val lenses = source.availableCameraInfos.map { info ->
            manager.lens(info) to (ImageCapture.OUTPUT_FORMAT_RAW in
                ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats)
        }
        val currentId = shutterState().substringAfter(" · ").substringBefore(" · ")
        val supported = lenses.filter { it.second }.map { it.first }
        val unsupported = lenses.filterNot { it.second }.map { it.first }
        val rawLens = supported.firstOrNull { it.id == currentId } ?: supported.firstOrNull()
        if (rawLens != null) {
            selectLens(rawLens)
            control("拍摄模式").tapCenter()
            compose.onNodeWithText("RAW / DNG").assertIsEnabled().tapCenter()
            waitForStreamingShutter()
            assertTrue("RAW must be bound, not a JPEG fallback", shutterState().endsWith(" · RAW"))
            compose.onNodeWithText("仅保存 DNG · Pictures/LUMORA/RAW · 无胶片成片").assertIsDisplayed()

            val records = repository.getRecords()
            val previous = images().map { it.uri }.toSet()
            try {
                control("拍照").tapCenter()
                compose.waitUntil(60_000) { images().any { it.uri !in previous && !it.pending } }
                waitForStreamingShutter()
                val shot = images().filter { it.uri !in previous }.single()
                assertTrue(shot.name, shot.name.endsWith("_RAW.dng"))
                assertEquals("Pictures/LUMORA/RAW/", shot.path)
                assertEquals("image/x-adobe-dng", shot.mime)
                assertEquals("image/x-adobe-dng", context.contentResolver.getType(shot.uri))
                assertTrue("The DNG must be published", !shot.pending)
                assertDng(shot.uri, rawLens.id)
                assertEquals("The RAW capture must not enter the JPEG library/pipeline", records, repository.getRecords())
            } finally {
                images().filter { it.uri !in previous }.forEach { context.contentResolver.delete(it.uri, null, null) }
            }
        }
        val noRawLens = unsupported.firstOrNull { it.id == currentId } ?: unsupported.firstOrNull()
        if (noRawLens != null) {
            if (shutterState().endsWith(" · RAW")) {
                control("拍摄模式").tapCenter()
                compose.onNodeWithText("自动").tapCenter()
                waitForStreamingShutter()
            }
            selectLens(noRawLens)
            control("拍摄模式").tapCenter()
            compose.onNodeWithText("RAW / DNG · 镜头不可用").assertIsDisplayed().assertIsNotEnabled()
        }
    }

    private fun selectLens(lens: LensOption) {
        if (shutterState().contains(" · ${lens.id} · ")) return
        val facing = when (lens.facing) {
            "BACK" -> "后置"
            "FRONT" -> "前置"
            "EXTERNAL" -> "外接"
            else -> "镜头"
        }
        val focal = lens.focalLengthsMm.joinToString("/") { "%.1f".format(Locale.US, it) }
        val label = "$facing ${lens.id}" + if (focal.isEmpty()) "" else " · $focal mm"
        control("拍摄工具").tapCenter()
        compose.onNodeWithText(label).performScrollTo().tapCenter()
        compose.waitUntil(30_000) { shutterState().contains(" · ${lens.id} · ") }
        waitForStreamingShutter()
    }

    private data class ImageRow(val uri: Uri, val name: String, val mime: String, val path: String, val pending: Boolean)

    private fun images(): List<ImageRow> {
        val collection = Media.EXTERNAL_CONTENT_URI
        return checkNotNull(context.contentResolver.query(collection,
            arrayOf(Media._ID, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.RELATIVE_PATH, Media.IS_PENDING),
            "${Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/LUMORA/%"), null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ImageRow(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getInt(4) != 0))
            }
        }
    }

    private fun assertDng(uri: Uri, cameraId: String) {
        val resolver = context.contentResolver
        val fd = requireNotNull(resolver.openFileDescriptor(uri, "r"))
        val length = fd.statSize
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { file ->
            val input = DataInputStream(file)
            val header = ByteArray(8).also(input::readFully)
            val order = when {
                header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
                header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() -> ByteOrder.BIG_ENDIAN
                else -> throw AssertionError("Missing TIFF byte-order signature")
            }
            val tiff = ByteBuffer.wrap(header).order(order)
            assertEquals("Missing TIFF magic", 42, tiff.getShort(2).toInt())
            val ifd0 = tiff.getInt(4).toLong() and 0xffffffffL
            assertTrue("Bad TIFF directory offset", ifd0 in 8 until (length - 2))
            file.channel.position(ifd0)
            val count = ByteArray(2).also(input::readFully)
            val entries = ByteBuffer.wrap(count).order(order).short.toInt() and 0xffff
            assertTrue("Truncated TIFF directory", ifd0 + 2 + entries * 12L <= length)
            val entry = ByteArray(12)
            var hasDngVersion = false
            repeat(entries) {
                input.readFully(entry)
                val tag = ByteBuffer.wrap(entry).order(order)
                if ((tag.short.toInt() and 0xffff) == 50706) { // DNGVersion: BYTE[4], inline.
                    hasDngVersion = tag.short.toInt() == 1 && tag.int == 4 && entry[8] == 1.toByte()
                }
            }
            assertTrue("Missing DNGVersion metadata", hasDngVersion)
        }
        val (width, height) = requireNotNull(resolver.openInputStream(uri)).use { input ->
            val exif = ExifInterface(input)
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) to
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        }
        val camera = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val rawSizes = camera.get(C.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
            .map { it.width to it.height }.toMutableSet()
        camera.get(C.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { rawSizes.add(it.width to it.height) }
        camera.get(C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.let { rawSizes.add(it.width() to it.height()) }
        camera.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rawSizes.add(it.width() to it.height()) }
        assertTrue("Unexpected RAW dimensions ${width}x$height (expected one of $rawSizes); $length bytes",
            width > 0 && height > 0 && (width to height) in rawSizes && length > width.toLong() * height)
    }
}
