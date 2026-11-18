package com.lumora.camera.image

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.FilmPresets
import com.lumora.camera.data.OptimizationStrength
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test

class PhotoEnhancementEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val style = FilmPresets.load(context).first { it.id == "forest_green" }

    @Test fun fullSizeAndExifSurviveASeparateMeasuredExportAndOrientedPreview() {
        val source = File.createTempFile("lumora-quality-", ".jpg", context.cacheDir)
        val original = Uri.fromFile(source)
        var exported: Uri? = null
        try {
            val bitmap = Bitmap.createBitmap(3024, 909, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(74, 125, 96))
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:01 13:23:45")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
                setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "42/10")
                setAttribute(ExifInterface.TAG_SUBJECT_DISTANCE, "2.0")
                saveAttributes()
            }
            val before = source.readBytes()
            val saved = PhotoEnhancementEngine.process(resolver, original, style, OptimizationStrength.STANDARD,
                "lumora-quality-test-${System.nanoTime()}.jpg", jpegQuality = 88, memoryContext = context).getOrThrow()
            exported = saved.enhanced
            assertNotEquals(original, saved.enhanced)
            assertEquals(3024, saved.width)
            assertEquals(909, saved.height)
            assertEquals(88, saved.diagnostics.jpegQuality)
            assertEquals(before.size.toLong(), saved.diagnostics.bytes)
            assertEquals("image/jpeg", saved.diagnostics.outputMimeType)
            assertEquals(8_000_000L, saved.diagnostics.exposureTimeNs)
            assertEquals(400, saved.diagnostics.iso)
            val inspected = requireNotNull(PhotoEnhancementEngine.inspectSource(resolver, saved.enhanced, null, null, "TEST"))
            assertEquals(3024, inspected.width)
            assertEquals(909, inspected.height)
            assertEquals(inspected.bytes, saved.diagnostics.outputBytes)
            assertTrue(inspected.bytes > 0)
            assertEquals(4.2f, inspected.focalLengthMm)
            assertEquals(2f, inspected.focusDistanceM)
            resolver.openInputStream(saved.enhanced)!!.use {
                val exif = ExifInterface(it)
                assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
                assertEquals("2026:11:01 13:23:45", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            }
            val preview = requireNotNull(PhotoEnhancementEngine.renderPreview(resolver, saved.enhanced, 160, style))
            assertEquals(160, preview.height)
            assertTrue(preview.width in 47..49)
            preview.recycle()
            assertArrayEquals(before, source.readBytes())
        } finally {
            exported?.let { resolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun jpegQualityChangesEncodedBytesAcrossTwoSeparateVersions() {
        val source = File.createTempFile("lumora-texture-", ".png", context.cacheDir)
        val outputs = mutableListOf<Uri>()
        try {
            val texture = IntArray(180 * 120) { i ->
                Color.rgb((i * 11) and 255, (i * 23) and 255, (i * i) and 255)
            }
            val bitmap = Bitmap.createBitmap(texture, 180, 120, Bitmap.Config.ARGB_8888)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val sizes = listOf(72, 98).map { quality ->
                val result = PhotoEnhancementEngine.process(resolver, Uri.fromFile(source), style,
                    OptimizationStrength.NATURAL, "lumora-q$quality-${System.nanoTime()}.jpg",
                    jpegQuality = quality, memoryContext = context).getOrThrow()
                outputs.add(result.enhanced)
                assertEquals("image/png", result.diagnostics.mimeType)
                assertEquals(quality, result.diagnostics.jpegQuality)
                requireNotNull(result.diagnostics.outputBytes)
            }
            assertNotEquals(outputs[0], outputs[1])
            assertTrue(sizes[1] > sizes[0])
        } finally {
            outputs.forEach { resolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun validOversizedPngFailsBeforeFullSizeAllocationAndOriginalStillOpens() {
        val source = File.createTempFile("lumora-large-", ".png", context.cacheDir)
        try {
            // A real one-bit grayscale PNG. Its IDAT streams a single reusable row.
            val edge = 32768
            val header = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(edge)
                    out.writeInt(edge)
                    out.write(byteArrayOf(1, 0, 0, 0, 0))
                }
            }.toByteArray()
            val compressed = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_SPEED)
            try {
                DeflaterOutputStream(compressed, deflater).use { zip ->
                    val row = ByteArray(1 + edge / 8)
                    repeat(edge) { zip.write(row) }
                }
            } finally {
                deflater.end()
            }
            DataOutputStream(source.outputStream()).use { out ->
                fun chunk(type: String, data: ByteArray) {
                    val name = type.toByteArray(Charsets.US_ASCII)
                    out.writeInt(data.size)
                    out.write(name)
                    out.write(data)
                    out.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
                }
                out.writeLong(0x89504e470d0a1a0aUL.toLong())
                chunk("IHDR", header)
                chunk("IDAT", compressed.toByteArray())
                chunk("IEND", byteArrayOf())
            }
            val before = source.readBytes()
            val uri = Uri.fromFile(source)
            val failure = PhotoEnhancementEngine.process(resolver, uri, style, OptimizationStrength.STANDARD,
                "lumora-oversize.jpg", memoryContext = context).exceptionOrNull()
            assertEquals(EnhancementStage.MEMORY, (failure as EnhancementFailure).stage)
            val facts = requireNotNull(PhotoEnhancementEngine.inspectSource(resolver, uri, null, null, "TEST"))
            assertEquals(edge, facts.width)
            assertEquals(edge, facts.height)
            assertArrayEquals(before, source.readBytes())
        } finally {
            source.delete()
        }
    }

    @Test fun failedWriteRemovesOnlyTheNewPendingItem() {
        val source = File.createTempFile("lumora-original-", ".jpeg", context.cacheDir)
        var pending: Uri? = null
        try {
            val data = byteArrayOf(1, 2, 3, 4)
            source.writeBytes(data)
            val failure = runCatching {
                PhotoEnhancementEngine.withPendingJpeg(resolver, "lumora-fail-${System.nanoTime()}.jpg") { uri ->
                    pending = uri
                    resolver.openOutputStream(uri)!!.use { it.write(data) }
                    throw IOException("controlled failure after partial write")
                }
            }
            assertTrue(failure.exceptionOrNull() is IOException)
            val incomplete = requireNotNull(pending)
            val collection = MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            val remains = resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media._ID}=?", arrayOf(ContentUris.parseId(incomplete).toString()), null)
                ?.use { it.moveToFirst() } ?: false
            if (!remains) pending = null
            assertFalse(remains)
            val failed = PhotoEnhancementEngine.process(resolver, Uri.fromFile(source), style,
                OptimizationStrength.STANDARD, "lumora-no-output.jpg").exceptionOrNull()
            assertEquals(EnhancementStage.DECODE, (failed as EnhancementFailure).stage)
            assertArrayEquals(data, source.readBytes())
        } finally {
            pending?.let { runCatching { resolver.delete(it, null, null) } }
            source.delete()
        }
    }
}
