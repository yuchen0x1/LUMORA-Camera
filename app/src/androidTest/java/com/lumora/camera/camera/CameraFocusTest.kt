package com.lumora.camera.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringResult
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.PhotoRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/** Runs the real focus coordinator on main, with controllable CameraX completions and time. */
class CameraFocusTest {
    @Test fun recentFocusIsReusedOnlyUntilExpiry() = onMain { h ->
        h.confirmTap()
        h.focus.beforeCapture(ManualControls(), h.statuses::add)
        assertEquals(1, h.requests.size)
        assertEquals(FocusStatus.FOCUSED, h.statuses.last())
        h.now += 1_200
        h.focus.beforeCapture(ManualControls(), h.statuses::add)
        assertEquals(2, h.requests.size)
        assertEquals(FocusStatus.FOCUSING, h.statuses.last())
    }

    @Test fun manualThenAutoWithinCacheIntervalRequiresFreshAf() = onMain { h ->
        h.confirmTap()
        h.focus.onControlsRequested(ManualControls(focusDiopters = 2f))
        h.focus.onControlsRequested(ManualControls())
        assertEquals(2, h.cancellations)
        h.focus.beforeCapture(ManualControls(), h.statuses::add)
        assertEquals(2, h.requests.size)
        assertEquals(FocusStatus.FOCUSING, h.statuses.last())
    }

    @Test fun unacknowledgedManualRequestCannotReuseOldFocus() = onMain { h ->
        h.confirmTap()
        h.focus.onControlsRequested(ManualControls(focusDiopters = 2f))
        // Simulate a rejected request: accepted controls are still automatic at the shutter.
        h.focus.beforeCapture(ManualControls(), h.statuses::add)
        assertEquals(2, h.requests.size)
        assertEquals(FocusStatus.FOCUSING, h.statuses.last())
    }

    @Test fun changedFocusCancelsPendingAfAndIgnoresItsLateSuccess() = onMain { h ->
        h.focus.tap(100f, 100f, ManualControls(), h.statuses::add)
        h.focus.onControlsRequested(ManualControls(focusDiopters = 1f))
        assertEquals(1, h.cancellations)
        assertEquals(FocusStatus.FAILED, h.statuses.last())
        val statusesAfterCancellation = h.statuses.toList()
        h.requests.first().set(FocusMeteringResult.create(true))
        assertEquals(statusesAfterCancellation, h.statuses)
        assertFalse(h.statuses.contains(FocusStatus.FOCUSED))
        h.focus.onControlsRequested(ManualControls())
        h.focus.beforeCapture(ManualControls(), h.statuses::add)
        assertEquals(2, h.requests.size)
    }

    @Test fun unchangedFocusWhileChangingWhiteBalanceRetainsRecentAf() = onMain { h ->
        h.confirmTap()
        h.focus.onControlsRequested(ManualControls(whiteBalanceMode = 1))
        h.focus.beforeCapture(ManualControls(whiteBalanceMode = 1), h.statuses::add)
        assertEquals(0, h.cancellations)
        assertEquals(1, h.requests.size)
        assertEquals(FocusStatus.FOCUSED, h.statuses.last())
    }

    @Test fun tapWaitsForPendingFocusRequestAndMainThreadAcknowledgment() = onMain { h ->
        h.confirmTap()
        h.focus.onControlsRequested(ManualControls(focusDiopters = 2f))
        val acknowledgment = Job()
        val (request, completion) = pendingOptions()
        val controller = h.controller(request, acknowledgment)
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.UNAVAILABLE, h.statuses.last())
        assertEquals(1, h.requests.size)
        completion.set(null)
        // Future completion can precede publishing accepted controls on main.
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.UNAVAILABLE, h.statuses.last())
        assertEquals(1, h.requests.size)
        acknowledgment.complete()
        // Simulate settled rollback to automatic controls. Only now may a tap start AF.
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.FOCUSING, h.statuses.last())
        assertEquals(2, h.requests.size)
    }

    @Test fun tapWaitsForRollbackEvenAfterAcknowledgmentJobCompletes() = onMain { h ->
        val (rollback, completion) = pendingOptions()
        val controller = h.controller(rollback, Job().apply { complete() })
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.UNAVAILABLE, h.statuses.last())
        assertEquals(0, h.requests.size)
        completion.set(null)
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.FOCUSING, h.statuses.last())
        assertEquals(1, h.requests.size)
    }

    @Test fun failedRollbackDoesNotPermitTapAf() = onMain { h ->
        val (rollback, completion) = pendingOptions()
        completion.setException(IllegalStateException("rollback failed"))
        val controller = h.controller(rollback, Job().apply { complete() })
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.UNAVAILABLE, h.statuses.last())
        assertEquals(0, h.requests.size)
    }

    @Test fun synchronousRollbackFailureCannotReuseLateOriginalSuccess() = onMain { h ->
        val (original, completion) = pendingOptions()
        val controller = h.controller(original, Job().apply { complete() })
        val rollback = controller.rollbackManualOptions { throw IllegalStateException("cannot issue rollback") }
        CameraController::class.java.getDeclaredField("manualRequest").apply { isAccessible = true }
            .set(controller, rollback)
        completion.set(null)
        assertFalse(runCatching { rollback.get() }.isSuccess)
        controller.focus(h.preview, 100f, 100f, h.statuses::add)
        assertEquals(FocusStatus.UNAVAILABLE, h.statuses.last())
        assertEquals(0, h.requests.size)
    }

    private fun pendingOptions(): Pair<ListenableFuture<Void?>, CallbackToFutureAdapter.Completer<Void?>> {
        lateinit var completion: CallbackToFutureAdapter.Completer<Void?>
        val future = CallbackToFutureAdapter.getFuture<Void?> { completion = it; "manual-options" }
        return future to completion
    }

    private fun onMain(test: (Harness) -> Unit) {
        val task = FutureTask {
            val harness = Harness()
            try { test(harness) } finally { harness.focus.cancel(resetMetering = true) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        task.get()
    }

    private class Harness {
        var now = 10_000L
        var cancellations = 0
        val requests = mutableListOf<CallbackToFutureAdapter.Completer<FocusMeteringResult>>()
        val statuses = mutableListOf<FocusStatus>()
        private val control = fake<CameraControl> { method ->
            when (method) {
                "startFocusAndMetering" -> CallbackToFutureAdapter.getFuture<FocusMeteringResult> {
                    requests += it
                    "focus-${requests.size}"
                }
                "cancelFocusAndMetering" -> {
                    cancellations++
                    CallbackToFutureAdapter.getFuture<Void> { it.set(null); "cancel-focus" }
                }
                else -> error("Unexpected CameraControl call: $method")
            }
        }
        private val info = fake<CameraInfo> { method ->
            when (method) {
                "isFocusMeteringSupported" -> true
                else -> error("Unexpected CameraInfo call: $method")
            }
        }
        private val camera = fake<Camera> { method ->
            when (method) {
                "getCameraControl" -> control
                "getCameraInfo" -> info
                else -> error("Unexpected Camera call: $method")
            }
        }
        val preview = PreviewView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
            layout(0, 0, 300, 400)
        }
        val focus = CameraFocus(camera, preview, true, Executor { it.run() }) { now }

        fun controller(request: ListenableFuture<Void?>, acknowledgment: Job): CameraController {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val controller = CameraController(context, PhotoRepository(context, "focus-${UUID.randomUUID()}"))
            // Exercise the public tap entry point without binding hardware or issuing Camera2 options.
            mapOf("focusControl" to focus, "manualRequest" to request, "manualAck" to acknowledgment)
                .forEach { (name, value) ->
                    CameraController::class.java.getDeclaredField(name).apply { isAccessible = true }
                        .set(controller, value)
                }
            return controller
        }

        fun confirmTap() {
            focus.tap(100f, 100f, ManualControls(), statuses::add)
            requests.last().set(FocusMeteringResult.create(true))
            assertEquals(FocusStatus.FOCUSED, statuses.last())
        }
    }
}

private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        call(method.name)
    } as T
