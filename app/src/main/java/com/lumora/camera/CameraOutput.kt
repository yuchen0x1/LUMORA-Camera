package com.lumora.camera

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.lumora.camera.camera.CameraController
import com.lumora.camera.camera.CaptureMode
import com.lumora.camera.camera.CaptureWorkQueue
import com.lumora.camera.camera.CaptureQualityRecord
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.camera.FocusStatus
import com.lumora.camera.camera.ManualControls
import com.lumora.camera.camera.RAW_OUTPUT_DIRECTORY
import com.lumora.camera.camera.ShutterDiagnostics
import com.lumora.camera.data.CaptureJournal
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.image.EnhancementFailure
import com.lumora.camera.image.PhotoEnhancementEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

internal data class ShotOptions(
    val preset: FilmPreset,
    val strength: OptimizationStrength,
    val intensity: Float,
    val jpegQuality: Int,
    val camera: DeviceCapabilities,
    val mode: CaptureMode,
    val manual: ManualControls
)

internal data class ShotResult(val enhanced: Boolean, val notice: String? = null)

/**
 * One shutter press whose source is already published and whose render is still running.
 *
 * Burst capture uses this to fire the next frame as soon as the source is on disk instead of
 * waiting for the full-size render of the previous frame.
 */
internal class ShotSource(
    val record: PhotoRecord,
    val focus: FocusStatus,
    val render: Deferred<ShotResult>
)

/** Once CameraX publishes the source, its library entry and processing outlive the viewfinder. */
internal object CameraOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = CaptureWorkQueue(scope)
    private val activeCaptures = ConcurrentHashMap.newKeySet<String>()

    internal fun isCaptureActive(id: String) = id in activeCaptures

    suspend fun take(
        context: Context, repository: PhotoRepository, controller: CameraController, options: ShotOptions,
        onFocus: (FocusStatus) -> Unit, onSourceSaved: () -> Unit
    ): ShotResult {
        if (options.mode == CaptureMode.RAW) {
            return queue.submit {
                takeRaw(context.applicationContext, repository, controller, options, onFocus, onSourceSaved)
            }.await()
        }
        return captureSource(context, repository, controller, options, onFocus, onSourceSaved).render.await()
    }

    /** At most two captures/renders are admitted; full-size processing remains serialized. */
    suspend fun captureSource(
        context: Context, repository: PhotoRepository, controller: CameraController, options: ShotOptions,
        onFocus: (FocusStatus) -> Unit, onSourceSaved: () -> Unit
    ): ShotSource {
        val application = context.applicationContext
        val resolver = application.contentResolver
        val journal = CaptureJournal(application)
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val id = "LUMORA_${stamp}_${UUID.randomUUID().toString().take(8)}"
        val source = CompletableDeferred<PhotoRecord>()
        var focus = FocusStatus.UNAVAILABLE
        val render = queue.submit {
            activeCaptures.add(id)
            try {
                withContext(Dispatchers.IO) {
                    check(journal.begin(id, options.preset.id, options.strength.name, options.intensity, now)) {
                        "无法保存拍摄恢复记录，请检查可用空间后重试"
                    }
                }
                val captured = CompletableDeferred<Pair<Uri, ShutterDiagnostics>>()
                val captureSession = controller.imageCapture
                controller.takePhoto(resolver, "${id}_ORIGINAL.jpg", onSaved = { uri, shutter ->
                    captured.complete(uri to shutter)
                    runCatching(onSourceSaved)
                }, onError = { captured.completeExceptionally(it) }, onFocus = {
                    focus = it
                    runCatching { onFocus(it) }
                })
                val (uri, shutter) = try {
                    withTimeout(120_000) { captured.await() }
                } catch (timeout: TimeoutCancellationException) {
                    val error = IllegalStateException("相机保存超时，请重新连接；已落盘原片将在下次启动核对", timeout)
                    controller.abortTimedOutCapture(captureSession, error)
                    throw error
                }
                val record = PhotoRecord(
                    originalUri = uri.toString(), enhancedUri = uri.toString(), styleId = options.preset.id,
                    createdAt = now, strength = options.strength, intensity = options.intensity, sourceOnly = true
                )
                withContext(Dispatchers.IO) {
                    check(journal.attachSource(id, uri.toString())) { "原片已保存，恢复记录未能更新，请重试启动应用" }
                    try {
                        repository.addRecord(record)
                        check(journal.markIndexed(id)) { "恢复记录未能确认" }
                    } catch (error: Exception) {
                        throw IllegalStateException("原片已保存到系统相册 · 作品库未能记录，将在下次启动重试", error)
                    }
                }
                source.complete(record)
                withContext(Dispatchers.IO) {
                    renderAndCommit(application, repository, journal, id, id, record, uri, shutter, options)
                }
            } catch (error: Exception) {
                // Keep the hint on failure: CameraX may have published a row before its callback.
                source.completeExceptionally(error)
                throw error
            } finally {
                activeCaptures.remove(id)
            }
        }
        return ShotSource(source.await(), focus, render)
    }

    private suspend fun renderAndCommit(
        context: Context, repository: PhotoRepository, journal: CaptureJournal, id: String, base: String,
        sourceRecord: PhotoRecord, uri: Uri, shutter: ShutterDiagnostics, options: ShotOptions
    ): ShotResult {
        val resolver = context.applicationContext.contentResolver
        // The image engine serializes full-size renders, so the source is indexed before entering it.
        return try {
            val render = PhotoEnhancementEngine.process(
                resolver, uri, options.preset, options.strength, "${base}_${options.preset.id}.jpg",
                cameraId = options.camera.cameraId, lensFacing = options.camera.lensFacing,
                mode = options.mode.name, jpegQuality = options.jpegQuality, intensity = options.intensity,
                memoryContext = context.applicationContext
            )
            val output = render.getOrNull()
            val facts = output?.diagnostics ?: PhotoEnhancementEngine.inspectSource(
                resolver, uri, options.camera.cameraId, options.camera.lensFacing, options.mode.name
            )
            val failure = render.exceptionOrNull() as? EnhancementFailure
            val manual = manualComparison(options.manual, facts)
            val diagnostics = buildString {
                append(facts?.toDiagnosticLine() ?: "capture input=unavailable mode=${options.mode.name}")
                append(" ${shutter.toDiagnosticLine()}")
                manual?.let { append(" ${it.diagnostics}") }
                if (render.isFailure) append(" enhancementFailure=${failure?.stage?.name ?: "PROCESS"}")
            }
            android.util.Log.i("LumoraCapture", diagnostics)
            val completed = sourceRecord.copy(
                enhancedUri = output?.enhanced?.toString() ?: sourceRecord.originalUri,
                sourceOnly = output == null, diagnostics = diagnostics
            )
            output?.let { check(journal.attachRender(id, it.enhanced.toString())) { "成片恢复记录写入失败" } }
            repository.replaceSourceRecord(completed)
            val journalCleared = journal.finish(id)
            if (output == null) ShotResult(false, "原片已保存 · ${failure?.message ?: "成片处理失败"}")
            else ShotResult(true, manual?.notice ?: if (journalCleared) null else "作品已保存 · 恢复记录将在下次启动核对")
        } catch (error: Exception) {
            android.util.Log.w("LumoraCapture", "Failed to finalize library record (${error.javaClass.simpleName})")
            ShotResult(false, "原片已保存 · 成片记录将在下次启动重试")
        }
    }

    private suspend fun takeRaw(
        context: Context, repository: PhotoRepository, controller: CameraController, options: ShotOptions,
        onFocus: (FocusStatus) -> Unit, onSourceSaved: () -> Unit
    ): ShotResult {
        val resolver = context.applicationContext.contentResolver
        val result = CompletableDeferred<ShotResult>()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val base = "LUMORA_${stamp}_${UUID.randomUUID().toString().take(8)}"
        val started = SystemClock.elapsedRealtime()
        var focus = FocusStatus.UNAVAILABLE
        val captureSession = controller.imageCapture
        controller.takeRawPhoto(resolver, "${base}_RAW.dng", onSaved = { uri ->
            val captureMillis = SystemClock.elapsedRealtime() - started
            runCatching(onSourceSaved)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                    val exif = requireNotNull(resolver.openInputStream(uri)).use { ExifInterface(it) }
                    fun tag(name: String) = exif.getAttribute(name) ?: "unavailable"
                    android.util.Log.i("LumoraCapture", "capture format=DNG mode=RAW camera=${options.camera.cameraId}" +
                        " input=${tag(ExifInterface.TAG_IMAGE_WIDTH)}x${tag(ExifInterface.TAG_IMAGE_LENGTH)}" +
                        " bytes=${bytes ?: "unavailable"} mime=${resolver.getType(uri) ?: "unavailable"}" +
                        " iso=${tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)}" +
                        " exposureSeconds=${tag(ExifInterface.TAG_EXPOSURE_TIME)}" +
                        " focalMm=${tag(ExifInterface.TAG_FOCAL_LENGTH)} afOutcome=$focus" +
                        " captureTotalMs=$captureMillis processingMs=0 jpegQuality=not-applicable")
                }.onFailure {
                    android.util.Log.w("LumoraCapture", "DNG saved; metadata unavailable (${it.javaClass.simpleName})")
                }
                result.complete(ShotResult(false, "DNG 原图已保存到系统相册 · $RAW_OUTPUT_DIRECTORY · 无胶片成片"))
            }
        }, onError = { result.completeExceptionally(it) }, onFocus = { focus = it; onFocus(it) })
        return try {
            withTimeout(120_000) { result.await() }
        } catch (timeout: TimeoutCancellationException) {
            val error = IllegalStateException(
                "RAW 相机保存超时，请重新连接；已落盘原片需手动核对", timeout)
            controller.abortTimedOutCapture(captureSession, error)
            throw error
        }
    }

    private data class ManualComparison(val diagnostics: String, val notice: String?)

    private fun manualComparison(manual: ManualControls, facts: CaptureQualityRecord?): ManualComparison? {
        val iso = manual.iso ?: return null
        val shutter = manual.exposureTimeNs ?: return null
        val actualIso = facts?.iso
        val actualTime = facts?.exposureTimeNs
        val requests = "requestedISO=$iso requestedExposureNs=$shutter manualExposure="
        if (actualIso == null || actualTime == null) {
            return ManualComparison(requests + "unreported", "原片未报告完整手动曝光数据")
        }
        if (actualIso != iso || abs(actualTime - shutter) > maxOf(1.0, shutter * 0.03)) {
            val measured = "原片实际 ISO $actualIso · ${"%.2f".format(Locale.US, actualTime / 1e6)} ms"
            return ManualComparison(requests + "different", measured)
        }
        return ManualComparison(requests + "within_tolerance", null)
    }
}
