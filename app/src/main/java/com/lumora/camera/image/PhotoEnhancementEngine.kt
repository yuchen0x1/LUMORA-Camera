package com.lumora.camera.image

import android.app.ActivityManager
import android.content.Context
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.lumora.camera.camera.CaptureQualityRecord
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.OptimizationStrength
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class EnhancementResult(
    val source: Uri, val enhanced: Uri, val width: Int, val height: Int,
    val diagnostics: CaptureQualityRecord
)

enum class EnhancementStage(val description: String) {
    MEMORY("内存不足，无法处理全分辨率照片"),
    DECODE("无法读取原图像素或尺寸"),
    PROCESS("无法完成全分辨率图像处理"),
    ENCODE("无法写入 JPEG 成片"),
    METADATA("无法读取或保存拍摄元数据"),
    VERIFY("成片尺寸或格式校验失败"),
    PUBLISH("无法在相册中保存成片")
}

class EnhancementFailure(val stage: EnhancementStage) : IOException(stage.description)

object PhotoEnhancementEngine {
    private val sRGB get() = ColorSpace.get(ColorSpace.Named.SRGB)

    fun processAndSave(
        resolver: ContentResolver, source: Uri, preset: FilmPreset, strength: OptimizationStrength,
        displayName: String, cameraId: String? = null, lensFacing: String? = null,
        mode: String = "AUTO", edits: PhotoEditParameters = PhotoEditParameters.NATURAL,
        jpegQuality: Int = 95, intensity: Float = 1f
    ): EnhancementResult? = process(resolver, source, preset, strength, displayName, cameraId,
        lensFacing, mode, edits, jpegQuality, intensity).getOrNull()

    /**
     * Serializes full-sized allocations across capture, import and re-edit operations. Call on IO.
     * Pass the original source for each version to avoid accumulating JPEG generations. A JPEG
     * source still needs one lossy encode per render (even at quality 100).
     */
    @Synchronized
    fun process(
        resolver: ContentResolver, source: Uri, preset: FilmPreset, strength: OptimizationStrength,
        displayName: String, cameraId: String? = null, lensFacing: String? = null,
        mode: String = "AUTO", edits: PhotoEditParameters = PhotoEditParameters.NATURAL,
        jpegQuality: Int = 95, intensity: Float = 1f, memoryContext: Context? = null
    ): Result<EnhancementResult> {
        val started = System.nanoTime()
        var stage = EnhancementStage.ENCODE
        return try {
            require(jpegQuality in 1..100)
            stage = EnhancementStage.DECODE
            val input = measure(resolver, source)
            stage = EnhancementStage.METADATA
            val exif = readExif(resolver, source, input.mime)
            val facts = sourceRecord(input, exif, cameraId, lensFacing, mode)
            val runtime = Runtime.getRuntime()
            val usedHeap = runtime.totalMemory() - runtime.freeMemory()
            val headroom = runtime.maxMemory() - usedHeap
            val managedBudget = managedProcessingBudget(runtime.maxMemory(), usedHeap)
            val nativeBudget = nativeBudget(memoryContext, runtime.maxMemory())
            val accepted = fitsBitmapBudget(input.width, input.height, nativeBudget, managedBudget)
            Log.i("LumoraCapture", "memory size=${input.width}x${input.height} heapMax=${runtime.maxMemory()} " +
                "managedAvailable=$headroom managedBudget=$managedBudget nativeBudget=$nativeBudget accepted=$accepted")
            if (!accepted) {
                throw EnhancementFailure(EnhancementStage.MEMORY)
            }
            stage = EnhancementStage.DECODE
            val bitmap = decodeRaw(resolver, source, sampleSize = 1)
            try {
                check(bitmap.width == input.width && bitmap.height == input.height)
                stage = EnhancementStage.PROCESS
                FilmColorEngine.applyInPlace(bitmap, preset, strength, edits, intensity)
                stage = EnhancementStage.PUBLISH
                val result = withPendingJpeg(resolver, displayName) { destination ->
                    stage = EnhancementStage.ENCODE
                    resolver.openOutputStream(destination, "w")?.use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                            throw IOException("JPEG 编码失败")
                        }
                    } ?: throw IOException("无法写入成片")
                    bitmap.recycle()
                    stage = EnhancementStage.METADATA
                    copyExif(resolver, exif, destination)
                    stage = EnhancementStage.VERIFY
                    val saved = measure(resolver, destination)
                    if (saved.width != input.width || saved.height != input.height ||
                        saved.bytes <= 0 || saved.mime != "image/jpeg") {
                        throw IOException("成片尺寸或格式校验失败")
                    }
                    val diagnostics = facts.copy(
                        processingMillis = (System.nanoTime() - started) / 1_000_000,
                        outputWidth = saved.width, outputHeight = saved.height, outputBytes = saved.bytes,
                        outputMimeType = saved.mime, jpegQuality = jpegQuality
                    )
                    stage = EnhancementStage.PUBLISH
                    EnhancementResult(source, destination, saved.width, saved.height, diagnostics)
                }
                Log.i("LumoraCapture", result.diagnostics.toDiagnosticLine())
                Result.success(result)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (_: OutOfMemoryError) {
            Log.w("LumoraCapture", "Full-resolution allocation failed; source retained")
            Result.failure(EnhancementFailure(EnhancementStage.MEMORY))
        } catch (error: Exception) {
            val failure = error as? EnhancementFailure ?: EnhancementFailure(stage)
            Log.w("LumoraCapture", "Full-resolution output failed stage=${failure.stage} type=${error.javaClass.simpleName}")
            Result.failure(failure)
        }
    }

    /** Reads actual source bytes, encoded dimensions, format and available capture tags. */
    fun inspectSource(
        resolver: ContentResolver, source: Uri, cameraId: String?, lensFacing: String?, mode: String
    ): CaptureQualityRecord? = try {
        val file = measure(resolver, source)
        val exif = try { readExif(resolver, source, file.mime) } catch (_: IOException) { null }
        sourceRecord(file, exif, cameraId, lensFacing, mode)
    } catch (_: Exception) { null }

    private fun nativeBudget(context: Context?, fallback: Long): Long {
        val manager = context?.getSystemService(ActivityManager::class.java)
        if (manager != null) {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            // Leave the system's low-memory threshold and at least half the remaining capacity available.
            return minOf(info.totalMem / 8, (info.availMem - info.threshold).coerceAtLeast(0) / 2)
        }
        // Resolver-only compatibility callers have no Context. Use the kernel's free page count.
        return try {
            val page = Os.sysconf(OsConstants._SC_PAGESIZE)
            val free = Os.sysconf(OsConstants._SC_AVPHYS_PAGES)
            val total = Os.sysconf(OsConstants._SC_PHYS_PAGES)
            if (page > 0 && free >= 0 && total > 0) minOf(free * page / 2, total * page / 8) else fallback
        } catch (_: Exception) { fallback }
    }

    /** Spare heap excludes reclaimable objects. Let ART collect for small, bounded row allocations. */
    internal fun managedProcessingBudget(maxHeap: Long, usedHeap: Long): Long {
        val limit = maxHeap.coerceAtLeast(0)
        val headroom = (limit - usedHeap.coerceAtLeast(0)).coerceAtLeast(0)
        return maxOf(headroom, limit / 8)
    }

    internal fun fitsBitmapBudget(
        width: Int, height: Int, nativeAvailable: Long, managedAvailable: Long = nativeAvailable
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val pixels = width.toLong() * height
        // Android bitmap byte counts are Int-sized. Check before multiplying adversarial dimensions.
        if (pixels > Int.MAX_VALUE / 4L) return false
        val bitmapBytes = pixels * 4
        val nativeScratch = (bitmapBytes / 8).coerceIn(128L * 1024, 8L * 1024 * 1024)
        // Pixel storage is native on API 26+. Only row buffers, style maps and metadata use the VM heap.
        val managedScratch = width * 20L + 128L * 1024 + (pixels / 8).coerceAtMost(1024L * 1024)
        return nativeAvailable >= bitmapBytes + nativeScratch && managedAvailable >= managedScratch
    }

    /** Only previews downsample. Film effects run in encoded coordinates before EXIF orientation. */
    fun renderPreview(
        resolver: ContentResolver, source: Uri, maxEdge: Int, preset: FilmPreset? = null,
        strength: OptimizationStrength = OptimizationStrength.STANDARD,
        edits: PhotoEditParameters = PhotoEditParameters.NATURAL, intensity: Float = 1f
    ): Bitmap? = previewWithFallback(maxEdge) { edge ->
        if (preset == null) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                val (width, height) = previewSize(info.size.width, info.size.height, edge)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(sRGB)
            }
        } else {
            renderStyledPreview(resolver, source, edge, preset, strength, edits, intensity)
        }
    }

    /** Allocation failures may degrade a preview, never a full-size export. Each attempt owns its bitmaps. */
    internal fun <T> previewWithFallback(maxEdge: Int, render: (Int) -> T): T? {
        require(maxEdge > 0)
        var edge = maxEdge
        while (true) {
            try {
                return render(edge)
            } catch (_: OutOfMemoryError) {
                if (edge == 1) return null
                edge = (edge / 2).coerceAtLeast(1)
            } catch (_: Exception) {
                return null
            }
        }
    }

    private fun renderStyledPreview(
        resolver: ContentResolver, source: Uri, maxEdge: Int, preset: FilmPreset,
        strength: OptimizationStrength, edits: PhotoEditParameters, intensity: Float
    ): Bitmap {
        val bounds = decodeBounds(resolver, source)
        val (width, height) = previewSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val orientation = readExif(resolver, source, bounds.outMimeType)
            ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        // Subsample at decode; the intermediate's longest edge is roughly at most 2 * maxEdge.
        val sampleSize = Integer.highestOneBit((max(bounds.outWidth, bounds.outHeight) / maxEdge).coerceAtLeast(1))
        var working = decodeRaw(resolver, source, sampleSize)
        var result: Bitmap? = null
        try {
            if (working.width != width || working.height != height) {
                val sampled = working
                working = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(working).drawBitmap(sampled, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                } finally {
                    sampled.recycle()
                }
            }
            FilmColorEngine.applyInPlace(working, preset, strength, edits, intensity)
            // An export keeps the raw pixel order and puts this same transform in its EXIF tag.
            val oriented = if (orientation == null) working else orient(working, orientation)
            result = oriented
            return oriented
        } finally {
            if (working !== result) working.recycle()
        }
    }

    private fun previewSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxEdge.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                else -> return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    internal fun <T> withPendingJpeg(resolver: ContentResolver, name: String, write: (Uri) -> T): T {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LUMORA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建成片")
        var published = false
        try {
            val result = write(destination)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(destination, values, null, null) != 1) throw IOException("无法发布成片")
            published = true
            return result
        } finally {
            if (!published) {
                try {
                    if (resolver.delete(destination, null, null) != 1) Log.w("LumoraCapture", "Pending item cleanup incomplete")
                } catch (_: Exception) { Log.w("LumoraCapture", "Pending item cleanup failed") }
            }
        }
    }

    private data class FileFacts(val width: Int, val height: Int, val mime: String, val bytes: Long)

    private fun decodeBounds(resolver: ContentResolver, uri: Uri): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) throw IOException("图像格式无法识别")
        return bounds
    }

    private fun decodeRaw(resolver: ContentResolver, uri: Uri, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = sRGB
            inMutable = true
            inSampleSize = sampleSize
            inScaled = false
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("无法读取原图像素")
    }

    private fun measure(resolver: ContentResolver, uri: Uri): FileFacts {
        val bounds = decodeBounds(resolver, uri)
        val descriptorBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        // Pipe-backed document providers are allowed to omit the descriptor length.
        val bytes = descriptorBytes?.takeIf { it >= 0L } ?: resolver.openInputStream(uri)?.use { stream ->
            val chunk = ByteArray(8192)
            var count = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                count += read
            }
            count
        } ?: throw IOException("无法读取文件大小")
        return FileFacts(bounds.outWidth, bounds.outHeight, bounds.outMimeType, bytes)
    }

    private fun sourceRecord(file: FileFacts, exif: ExifInterface?, camera: String?, lens: String?, mode: String): CaptureQualityRecord {
        fun positive(tag: String) = exif?.getAttributeDouble(tag, Double.NaN)?.takeIf { it.isFinite() && it > 0 }
        return CaptureQualityRecord(
            file.width, file.height, file.bytes, file.mime, camera, lens, mode,
            exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)?.takeIf { it > 0 },
            positive(ExifInterface.TAG_EXPOSURE_TIME)?.let { (it * 1_000_000_000).roundToLong() },
            positive(ExifInterface.TAG_FOCAL_LENGTH)?.toFloat(),
            positive(ExifInterface.TAG_SUBJECT_DISTANCE)?.takeIf { it < 0xffffffffL }?.toFloat(),
            null, null, null
        )
    }

    private val exifMimes = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic", "image/x-adobe-dng")

    private fun readExif(resolver: ContentResolver, uri: Uri, mime: String): ExifInterface? =
        if (mime in exifMimes) {
            resolver.openInputStream(uri)?.use { ExifInterface(it) } ?: throw IOException("无法读取图像元数据")
        } else null

    // Only capture tags; don't carry over location, thumbnails, or source pixel dimensions.
    private val copyTags = listOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_SUBJECT_DISTANCE
    )

    private fun copyExif(resolver: ContentResolver, input: ExifInterface?, destination: Uri) {
        if (input == null) return
        val tags = copyTags.mapNotNull { tag -> input.getAttribute(tag)?.let { tag to it } }
        if (tags.isEmpty()) return
        resolver.openFileDescriptor(destination, "rw")?.use { fd ->
            val out = ExifInterface(fd.fileDescriptor)
            tags.forEach { (key, value) -> out.setAttribute(key, value) }
            out.saveAttributes()
        } ?: throw IOException("无法写入成片元数据")
    }
}
