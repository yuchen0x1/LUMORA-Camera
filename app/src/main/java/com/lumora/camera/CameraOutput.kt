package com.lumora.camera

import android.content.Context
import com.lumora.camera.camera.CameraController
import com.lumora.camera.camera.CaptureMode
import com.lumora.camera.camera.CaptureQualityRecord
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.camera.FocusStatus
import com.lumora.camera.camera.ManualControls
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.image.EnhancementFailure
import com.lumora.camera.image.PhotoEnhancementEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

/** Once CameraX publishes the source, its library entry and processing outlive the viewfinder. */
internal object CameraOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun take(
        context: Context, repository: PhotoRepository, controller: CameraController, options: ShotOptions,
        onFocus: (FocusStatus) -> Unit, onSourceSaved: () -> Unit
    ): ShotResult {
        val resolver = context.applicationContext.contentResolver
        val result = CompletableDeferred<ShotResult>()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val base = "LUMORA_${stamp}_${UUID.randomUUID().toString().take(8)}"
        controller.takePhoto(resolver, "${base}_ORIGINAL.jpg", onSaved = { uri ->
            val sourceRecord = PhotoRecord(
                originalUri = uri.toString(), enhancedUri = uri.toString(), styleId = options.preset.id,
                createdAt = now, strength = options.strength, intensity = options.intensity, sourceOnly = true
            )
            scope.launch {
                // The image engine serializes full-size renders. Index the source before entering it.
                try {
                    repository.addRecord(sourceRecord)
                } catch (error: Exception) {
                    android.util.Log.e("LumoraCapture", "Failed to index saved source (${error.javaClass.simpleName})")
                    result.complete(ShotResult(false, "原片已保存到系统相册 · 作品库未能记录"))
                    return@launch
                }
                runCatching {
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
                        manual?.let { append(" ${it.diagnostics}") }
                        if (render.isFailure) append(" enhancementFailure=${failure?.stage?.name ?: "PROCESS"}")
                    }
                    val completed = sourceRecord.copy(
                        enhancedUri = output?.enhanced?.toString() ?: sourceRecord.originalUri,
                        sourceOnly = output == null, diagnostics = diagnostics
                    )
                    repository.replaceSourceRecord(completed)
                    if (output == null) ShotResult(false, "原片已保存 · ${failure?.message ?: "成片处理失败"}")
                    else ShotResult(true, manual?.notice)
                }.onSuccess { result.complete(it) }.onFailure {
                    android.util.Log.w("LumoraCapture", "Failed to finalize library record (${it.javaClass.simpleName})")
                    result.complete(ShotResult(false, "原片已保存 · 成片记录未能完成"))
                }
            }
            runCatching(onSourceSaved)
        }, onError = { result.completeExceptionally(it) }, onFocus = onFocus)
        return result.await()
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
