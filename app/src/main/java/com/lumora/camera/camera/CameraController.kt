package com.lumora.camera.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.camera2.CaptureRequest as R
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lumora.camera.data.PhotoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal val RAW_OUTPUT_DIRECTORY = "${Environment.DIRECTORY_PICTURES}/LUMORA/RAW"
internal const val RAW_OUTPUT_MIME = "image/x-adobe-dng"

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val repository: PhotoRepository,
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
) {
    var camera: Camera? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var capabilities: DeviceCapabilities? = null
        private set
    var activeMode = CaptureMode.AUTO
        private set
    private val capabilityManager = DeviceCapabilityManager(context)
    private val rawSessionFailures = mutableSetOf<String>()
    private var removeRawStateObserver: (() -> Unit)? = null
    private var binding: Job? = null
    private var provider: ProcessCameraProvider? = null
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var displayListener: DisplayManager.DisplayListener? = null
    private var boundCases = emptyList<UseCase>()
    private var owner: LifecycleOwner? = null
    private var focusControl: CameraFocus? = null
    private var previewObserver: PreviewCaptureObserver? = null
    private var captureJpegQuality = 96
    private var controls = ManualControls()
    private var manualRequest: ListenableFuture<Void?>? = null
    private var manualAck: Job? = null
    private var acceptedOptions = CaptureRequestOptions.Builder().build()
    private var inFlight: Any? = null
    private var pendingShutter: Job? = null
    private var cancelUnsubmittedCapture: (() -> Unit)? = null
    private var sessionError: ((Throwable) -> Unit)? = null

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        onReady: (Camera) -> Unit,
        onError: (Throwable) -> Unit = {},
        cameraId: String? = null,
        mode: CaptureMode = CaptureMode.AUTO,
        wide: Boolean = false,
        jpegQuality: Int = 96
    ) {
        release()
        sessionError = onError
        binding = CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                val source = runInterruptible(Dispatchers.IO) { providerFuture.get(15, TimeUnit.SECONDS) }
                val infos = source.availableCameraInfos
                val choices = withContext(Dispatchers.IO) {
                    infos.mapNotNull { info -> runCatching { capabilityManager.lens(info) to info }.getOrNull() }
                }
                val lenses = choices.map { it.first }
                val selected = preferredLens(cameraId, lenses) ?: throw IllegalStateException("没有可用镜头")
                val lens = if (mode == CaptureMode.MACRO_ASSIST) closestFocusLens(selected, lenses) else selected
                val info = choices.first { it.first == lens }.second
                val base = info.cameraSelector
                val rawAvailability = capabilityManager.rawAvailability(info, lens.id in rawSessionFailures)
                if (mode == CaptureMode.RAW) {
                    rawAvailability.unavailableReason?.let { throw IllegalStateException(it) }
                }
                val manager = try {
                    val extensions = ExtensionsManager.getInstanceAsync(context, source)
                    runInterruptible(Dispatchers.IO) { extensions.get(5, TimeUnit.SECONDS) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                val available = withContext(Dispatchers.IO) { capabilityManager.availableExtensions(manager, base) }.toMutableSet()
                if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@launch
                val extension = mode.extension
                val enhanced = extension?.takeIf { it in available }?.let {
                    runCatching { manager?.getExtensionEnabledCameraSelector(base, it) }.getOrNull()
                }
                val selectedMode = if (extension != null && enhanced == null) CaptureMode.AUTO else mode
                this@CameraController.owner = owner
                provider = source
                val streamWide = wide && mode != CaptureMode.LANDSCAPE_PROFILE && mode != CaptureMode.RAW
                lateinit var livePreview: Preview
                fun bindStreams(selector: CameraSelector, usePreferredSizes: Boolean): Camera {
                    val observer = PreviewCaptureObserver()
                    val (preview, capture) = buildUseCases(previewView, activeMode, streamWide, jpegQuality, usePreferredSizes, observer)
                    val bound = try {
                        source.bindToLifecycle(owner, selector, preview, capture)
                    } catch (error: RuntimeException) {
                        source.unbind(preview, capture)
                        throw error
                    }
                    boundCases = listOf(preview, capture)
                    imageCapture = capture
                    captureJpegQuality = jpegQuality.coerceIn(1, 100)
                    previewObserver = observer
                    livePreview = preview
                    return bound
                }
                fun attach(selector: CameraSelector): Camera = try {
                    bindStreams(selector, true)
                } catch (_: IllegalArgumentException) {
                    // Some vendor sessions constrain preview/JPEG stream pairs to their defaults.
                    bindStreams(selector, false)
                }
                activeMode = selectedMode
                val bound = if (mode == CaptureMode.RAW) {
                    try {
                        attach(base)
                    } catch (error: RuntimeException) {
                        rawSessionFailures.add(lens.id)
                        Log.w("LumoraCapture", "RAW bind failed for camera ${lens.id}", error)
                        throw IllegalStateException(requireNotNull(RawAvailability.SESSION_UNAVAILABLE.unavailableReason))
                    }
                } else if (enhanced != null) {
                    try { attach(enhanced) } catch (_: RuntimeException) {
                        available.remove(extension)
                        activeMode = CaptureMode.AUTO
                        attach(base)
                    }
                } else attach(base)
                if (enhanced == null && extension != null) available.remove(extension)
                camera = bound
                val capture = requireNotNull(imageCapture)
                trackDisplayRotation(previewView, livePreview, capture, bound, streamWide)
                val facts = capabilityManager.snapshot(bound.cameraInfo, lens, lenses, available,
                    activeMode.extension != null, rawAvailability)
                capabilities = facts
                focusControl = CameraFocus(bound, previewView, facts.supportsAutoFocus, mainExecutor)
                watchSession(owner, bound, capture, lens.id, activeMode == CaptureMode.RAW, onError)
                onReady(bound)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                release()
                onError(error.cause ?: error)
            }
        }
    }

    private fun watchSession(
        owner: LifecycleOwner, bound: Camera, capture: ImageCapture, cameraId: String, raw: Boolean,
        onError: (Throwable) -> Unit
    ) {
        val states = bound.cameraInfo.cameraState
        val observer = Observer<CameraState> { state ->
            val error = state?.error
            if (error != null) {
                // Check identity and current error again: queued callbacks must not tear down a new session.
                Handler(Looper.getMainLooper()).post {
                    if (imageCapture !== capture || states.value?.error !== error) return@post
                    val rawFailure = raw && error.code == CameraState.ERROR_STREAM_CONFIG
                    if (rawFailure) rawSessionFailures.add(cameraId)
                    Log.w("LumoraCapture", "Camera session error code=${error.code} raw=$raw", error.cause)
                    release()
                    onError(IllegalStateException(
                        if (rawFailure) RawAvailability.SESSION_UNAVAILABLE.unavailableReason
                        else "相机连接中断，请重新连接", error.cause
                    ))
                }
            }
        }
        removeRawStateObserver = { states.removeObserver(observer) }
        states.observe(owner, observer)
    }

    private val CaptureMode.extension: Int?
        get() = when (this) {
            CaptureMode.HDR_EXTENSION -> ExtensionMode.HDR
            CaptureMode.NIGHT_EXTENSION -> ExtensionMode.NIGHT
            CaptureMode.PORTRAIT_EXTENSION -> ExtensionMode.BOKEH
            else -> null
        }

    private fun buildUseCases(
        view: PreviewView, mode: CaptureMode, wide: Boolean, quality: Int,
        usePreferredSizes: Boolean, observer: PreviewCaptureObserver
    ): Pair<Preview, ImageCapture> {
        val aspect = if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val shortEdge = when (repository.previewQuality) {
            "高质量" -> 1080
            "省电" -> 480
            else -> 720
        }
        val ratio = if (wide) Rational(16, 9) else Rational(4, 3)
        val previewSize = Size((shortEdge * ratio.toFloat()).toInt(), shortEdge)
        val previewSelector = ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
            .setResolutionStrategy(ResolutionStrategy(previewSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val previewBuilder = Preview.Builder().apply { if (usePreferredSizes) setResolutionSelector(previewSelector) }
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(observer)
        val preview = previewBuilder.build().also { it.surfaceProvider = view.surfaceProvider }
        val qualityCapture = mode != CaptureMode.STREET_PROFILE
        val stillSelector = ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAllowedResolutionMode(if (qualityCapture) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION).build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(if (qualityCapture) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .apply {
                if (mode == CaptureMode.RAW) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                    if (usePreferredSizes) setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                } else {
                    if (usePreferredSizes) setResolutionSelector(stillSelector)
                    setJpegQuality(quality.coerceIn(1, 100))
                }
                view.display?.let { setTargetRotation(it.rotation) }
            }.build()
        return preview to capture
    }

    private fun trackDisplayRotation(view: PreviewView, preview: Preview, capture: ImageCapture, bound: Camera, wide: Boolean) {
        var lastRotation = -1
        fun update() {
            val rotation = view.display?.rotation ?: capture.targetRotation
            if (rotation == lastRotation) return
            lastRotation = rotation
            preview.targetRotation = rotation
            capture.targetRotation = rotation
            if (capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) return
            val size = capture.resolutionInfo?.resolution
            val portrait = if (size != null) {
                portraitAfterRotation(size.width, size.height, bound.cameraInfo.getSensorRotationDegrees(rotation))
            } else context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val ratio = if (wide) Rational(16, 9) else Rational(4, 3)
            capture.setCropAspectRatio(if (portrait) Rational(ratio.denominator, ratio.numerator) else ratio)
        }
        update()
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (view.display?.displayId == displayId && imageCapture === capture) update()
            }
        }.also { displays.registerDisplayListener(it, Handler(Looper.getMainLooper())) }
    }

    internal fun abortTimedOutCapture(expected: ImageCapture?, error: Throwable) {
        if (expected == null || imageCapture !== expected) return
        val notify = sessionError
        release()
        notify?.invoke(error)
    }

    fun release() {
        sessionError = null
        binding?.cancel()
        binding = null
        owner = null
        removeRawStateObserver?.invoke()
        removeRawStateObserver = null
        displayListener?.let(displays::unregisterDisplayListener)
        displayListener = null
        cancelUnsubmittedCapture?.invoke()
        cancelUnsubmittedCapture = null
        inFlight = null
        pendingShutter?.cancel()
        pendingShutter = null
        focusControl?.cancel()
        focusControl = null
        manualAck?.cancel()
        manualAck = null
        if (manualRequest != null) {
            camera?.let { runCatching { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() } }
        }
        manualRequest = null
        acceptedOptions = CaptureRequestOptions.Builder().build()
        controls = ManualControls()
        if (boundCases.isNotEmpty()) provider?.unbind(*boundCases.toTypedArray())
        boundCases = emptyList()
        provider = null
        camera = null
        imageCapture = null
        capabilities = null
        previewObserver = null
        captureJpegQuality = 96
        activeMode = CaptureMode.AUTO
    }

    fun focus(previewView: PreviewView, x: Float, y: Float, onResult: (FocusStatus) -> Unit = {}) {
        if (inFlight != null) return
        // A completed request still needs its accepted controls published on main.
        // Failed or pending rollback options must not start AF with stale controls either.
        if (manualAck?.isCompleted == false || manualRequest?.let {
                !it.isDone || runCatching { it.get() }.isFailure
            } == true) return onResult(FocusStatus.UNAVAILABLE)
        val focus = focusControl ?: return onResult(FocusStatus.UNAVAILABLE)
        if (previewView.width <= 0 || previewView.height <= 0) return onResult(FocusStatus.UNAVAILABLE)
        focus.tap(x, y, controls, onResult)
    }

    fun setZoom(ratio: Float, onResult: (Throwable?) -> Unit = {}) {
        controlResult(onResult) {
            val facts = requireNotNull(capabilities)
            require(ratio.isFinite())
            requireNotNull(camera).cameraControl.setZoomRatio(ratio.coerceIn(requireNotNull(facts.zoomMin), requireNotNull(facts.zoomMax)))
        }
    }

    fun setExposure(index: Int, onResult: (Throwable?) -> Unit = {}) {
        controlResult(onResult) {
            require(controls.iso == null && !controls.aeLock) { "手动曝光或曝光锁定期间不可调整补偿" }
            val range = requireNotNull(capabilities?.exposureCompensationRange) { "曝光补偿不可用" }
            requireNotNull(camera).cameraControl.setExposureCompensationIndex(range.clamp(index))
        }
    }

    fun setFlashMode(mode: Int, onResult: (Throwable?) -> Unit = {}) {
        val result = runCatching {
            val capture = requireNotNull(imageCapture) { "相机尚未就绪" }
            if (mode != ImageCapture.FLASH_MODE_OFF) {
                require(capabilities?.supportsFlash == true && controls.iso == null &&
                    activeMode !in listOf(CaptureMode.LANDSCAPE_PROFILE, CaptureMode.STREET_PROFILE, CaptureMode.RAW)) { "当前模式无法使用闪光灯" }
            }
            capture.flashMode = mode
        }
        onResult(result.exceptionOrNull())
    }

    fun setManualControls(settings: ManualControls, onResult: (Throwable?) -> Unit = {}) {
        val current = camera ?: return onResult(IllegalStateException("相机尚未就绪"))
        val validated = runCatching {
            check(inFlight == null) { "正在拍摄" }
            check(activeMode == CaptureMode.PRO || settings == ManualControls()) { "请先选择 PRO" }
            settings.forCamera(requireNotNull(capabilities))
        }.getOrElse { return onResult(it) }
        val builder = CaptureRequestOptions.Builder()
        val previousFlash = imageCapture?.flashMode
        validated.iso?.let {
            builder.setCaptureRequestOption(R.CONTROL_AE_MODE, R.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(R.SENSOR_SENSITIVITY, it)
            builder.setCaptureRequestOption(R.SENSOR_EXPOSURE_TIME, requireNotNull(validated.exposureTimeNs))
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
        }
        validated.whiteBalanceMode?.let { builder.setCaptureRequestOption(R.CONTROL_AWB_MODE, it) }
        validated.focusDiopters?.let {
            builder.setCaptureRequestOption(R.CONTROL_AF_MODE, R.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(R.LENS_FOCUS_DISTANCE, it)
        }
        if (validated.aeLock) builder.setCaptureRequestOption(R.CONTROL_AE_LOCK, true)
        if (validated.awbLock) builder.setCaptureRequestOption(R.CONTROL_AWB_LOCK, true)
        val interop = Camera2CameraControl.from(current.cameraControl)
        val options = builder.build()
        // Cancel the previous AF action before changing focus mode, even if this request later fails.
        focusControl?.onControlsRequested(validated)
        val request = runCatching { interop.setCaptureRequestOptions(options) }.getOrElse {
            previousFlash?.let { mode -> imageCapture?.flashMode = mode }
            return onResult(it)
        }
        manualAck?.cancel()
        manualRequest = request
        manualAck = CoroutineScope(Dispatchers.Main.immediate).launch {
            // An acknowledgment can span more than one frame after changing the sensor exposure.
            val frameMillis = TimeUnit.NANOSECONDS.toMillis(maxOf(controls.exposureTimeNs ?: 0, validated.exposureTimeNs ?: 0))
            try {
                runInterruptible(Dispatchers.IO) { request.get(4_000L + 2 * frameMillis, TimeUnit.MILLISECONDS) }
                acceptedOptions = options
                controls = validated
                onResult(null)
            } catch (error: Exception) {
                ensureActive()
                previousFlash?.let { mode -> imageCapture?.flashMode = mode }
                // Roll back the entire request bundle. A failed rollback also blocks the next shutter.
                manualRequest = rollbackManualOptions { interop.setCaptureRequestOptions(acceptedOptions) }
                onResult(error.cause ?: error)
            }
        }
    }

    internal fun rollbackManualOptions(issue: () -> ListenableFuture<Void?>): ListenableFuture<Void?> =
        runCatching(issue).getOrElse { error ->
            // Never fall back to the timed-out original: it may still succeed later.
            Futures.immediateFailedFuture(error)
        }

    private fun controlResult(onResult: (Throwable?) -> Unit, issue: () -> ListenableFuture<*>) {
        val current = camera
        val request = runCatching(issue).getOrElse { return onResult(it) }
        request.addListener({
            if (camera === current) {
                val error = runCatching { request.get() }.exceptionOrNull()
                onResult(error?.cause ?: error)
            }
        }, mainExecutor)
    }

    internal fun takePhoto(
        resolver: ContentResolver,
        displayName: String,
        onSaved: (Uri, ShutterDiagnostics) -> Unit,
        onError: (Throwable) -> Unit,
        onFocus: (FocusStatus) -> Unit = {}
    ) = takeStill(resolver, displayName, raw = false, onSaved = { uri, diagnostics ->
        onSaved(uri, requireNotNull(diagnostics))
    }, onError = onError, onFocus = onFocus)

    internal fun takeRawPhoto(
        resolver: ContentResolver,
        displayName: String,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
        onFocus: (FocusStatus) -> Unit = {}
    ) = takeStill(resolver, displayName, raw = true, onSaved = { uri, _ -> onSaved(uri) },
        onError = onError, onFocus = onFocus)

    private fun takeStill(
        resolver: ContentResolver,
        displayName: String,
        raw: Boolean,
        onSaved: (Uri, ShutterDiagnostics?) -> Unit,
        onError: (Throwable) -> Unit,
        onFocus: (FocusStatus) -> Unit
    ) {
        val capture = imageCapture ?: return onError(IllegalStateException("相机尚未就绪"))
        val expected = if (raw) ImageCapture.OUTPUT_FORMAT_RAW else ImageCapture.OUTPUT_FORMAT_JPEG
        if (raw != (activeMode == CaptureMode.RAW) || capture.outputFormat != expected) {
            return onError(IllegalStateException("当前会话的拍摄格式不匹配"))
        }
        val focus = focusControl ?: return onError(IllegalStateException("相机尚未就绪"))
        if (inFlight != null) return onError(IllegalStateException("正在拍摄"))
        val shutter = Any()
        val preparationStarted = SystemClock.elapsedRealtime()
        val observer = previewObserver
        val jpegQuality = captureJpegQuality
        inFlight = shutter
        var completed = false
        fun finish(error: Throwable) {
            if (completed) return
            completed = true
            if (inFlight === shutter) {
                inFlight = null
                cancelUnsubmittedCapture = null
            }
            onError(error)
        }
        cancelUnsubmittedCapture = { finish(IllegalStateException("拍摄已取消")) }
        fun active() = inFlight === shutter && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
        pendingShutter = CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                manualAck?.join()
                val parameters = manualRequest
                if (parameters != null) runInterruptible(Dispatchers.IO) { parameters.get(4, TimeUnit.SECONDS) }
                check(active()) { "拍摄已取消" }
                focus.beforeCapture(controls) { status ->
                    if (completed) return@beforeCapture
                    onFocus(status)
                    if (status == FocusStatus.FOCUSING) return@beforeCapture
                    if (!active()) return@beforeCapture finish(IllegalStateException("拍摄已取消"))
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, if (raw) RAW_OUTPUT_MIME else "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, if (raw) RAW_OUTPUT_DIRECTORY else "${Environment.DIRECTORY_PICTURES}/LUMORA")
                    }
                    val output = ImageCapture.OutputFileOptions.Builder(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
                    try {
                        val captureStarted = SystemClock.elapsedRealtime()
                        val stream = capture.resolutionInfo?.resolution
                        val preCapture = observer?.recent(captureStarted)
                        cancelUnsubmittedCapture = null
                        capture.takePicture(output, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                if (completed) return
                                val uri = result.savedUri ?: return finish(IllegalStateException("未返回原图地址"))
                                if (raw && result.imageFormat != ImageFormat.RAW_SENSOR) {
                                    CoroutineScope(Dispatchers.IO).launch { runCatching { resolver.delete(uri, null, null) } }
                                    return finish(IllegalStateException("相机未返回 RAW 数据"))
                                }
                                val diagnostics = if (raw) null else ShutterDiagnostics(
                                    focus = status,
                                    configuredJpegQuality = jpegQuality,
                                    streamWidth = stream?.width,
                                    streamHeight = stream?.height,
                                    preparationMs = captureStarted - preparationStarted,
                                    saveMs = SystemClock.elapsedRealtime() - captureStarted,
                                    preview = preCapture
                                )
                                completed = true
                                if (inFlight === shutter) inFlight = null
                                onSaved(uri, diagnostics)
                            }

                            override fun onError(exception: ImageCaptureException) = finish(exception)
                        })
                    } catch (error: Exception) { finish(error) }
                }
            } catch (error: Exception) {
                finish(error.cause ?: error)
            }
        }
    }
}
