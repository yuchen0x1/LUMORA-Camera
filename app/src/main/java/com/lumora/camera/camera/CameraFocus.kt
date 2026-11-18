package com.lumora.camera.camera

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Main-thread focus state belongs to one bound camera and one PreviewView. */
internal class CameraFocus(
    private val camera: Camera,
    private val preview: PreviewView,
    private val supportsAutoFocus: Boolean,
    private val executor: Executor
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tap: PointF? = null
    private var tapTime = 0L
    private var confirmedAt = 0L
    private var attempt = 0L
    private val listeners = mutableListOf<(FocusStatus) -> Unit>()
    private var timeout: Runnable? = null

    fun tap(x: Float, y: Float, controls: ManualControls, listener: (FocusStatus) -> Unit) {
        cancel()
        tap = PointF(x, y)
        tapTime = SystemClock.elapsedRealtime()
        start(x, y, controls, listener)
    }

    fun beforeCapture(controls: ManualControls, listener: (FocusStatus) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (controls.focusDiopters != null) {
            listener(FocusStatus.UNAVAILABLE)
        } else if (listeners.isNotEmpty()) {
            listeners += listener
            listener(FocusStatus.FOCUSING)
        } else if (confirmedAt != 0L && now - confirmedAt < 1_200 && tap != null && now - tapTime < 2_500) {
            listener(FocusStatus.FOCUSED)
        } else {
            val target = tap?.takeIf { now - tapTime < 3_000 }
            start(target?.x ?: preview.width / 2f, target?.y ?: preview.height / 2f, controls, listener)
        }
    }

    fun cancel() {
        if (listeners.isNotEmpty()) runCatching { camera.cameraControl.cancelFocusAndMetering() }
        complete(FocusStatus.FAILED)
        confirmedAt = 0L
    }

    private fun start(x: Float, y: Float, controls: ManualControls, listener: (FocusStatus) -> Unit) {
        if (preview.width == 0 || preview.height == 0) return listener(FocusStatus.UNAVAILABLE)
        val point = preview.meteringPointFactory.createPoint(x, y)
        fun supported(action: FocusMeteringAction) = runCatching {
            camera.cameraInfo.isFocusMeteringSupported(action)
        }.getOrDefault(false)
        val af = supportsAutoFocus && controls.focusDiopters == null &&
            supported(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build())
        val ae = controls.iso == null && !controls.aeLock
        val flags = (if (af) FocusMeteringAction.FLAG_AF else 0) or (if (ae) FocusMeteringAction.FLAG_AE else 0)
        if (flags == 0) return listener(FocusStatus.UNAVAILABLE)
        val action = FocusMeteringAction.Builder(point, flags).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        if (!supported(action)) return listener(FocusStatus.UNAVAILABLE)

        val current = ++attempt
        listeners += listener
        listener(FocusStatus.FOCUSING)
        val result = runCatching { camera.cameraControl.startFocusAndMetering(action) }.getOrElse {
            return complete(FocusStatus.FAILED)
        }
        timeout = Runnable {
            if (current == attempt) {
                runCatching { camera.cameraControl.cancelFocusAndMetering() }
                complete(FocusStatus.TIMEOUT)
            }
        }.also { handler.postDelayed(it, 2_000) }
        result.addListener({
            if (current == attempt) {
                val status = when {
                    !af -> FocusStatus.UNAVAILABLE
                    runCatching { result.get().isFocusSuccessful }.getOrDefault(false) -> FocusStatus.FOCUSED
                    else -> FocusStatus.FAILED
                }
                if (status == FocusStatus.FOCUSED) confirmedAt = SystemClock.elapsedRealtime()
                complete(status)
            }
        }, executor)
    }

    private fun complete(status: FocusStatus) {
        attempt++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val callbacks = listeners.toList()
        listeners.clear()
        callbacks.forEach { it(status) }
    }
}
