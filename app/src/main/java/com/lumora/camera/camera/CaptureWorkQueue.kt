package com.lumora.camera.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore

/** Admission is cancellable; admitted captures belong to the process, not the screen. */
internal class CaptureWorkQueue(private val scope: CoroutineScope, capacity: Int = 2) {
    private val slots = Semaphore(capacity)

    suspend fun <T> submit(work: suspend () -> T): Deferred<T> {
        slots.acquire()
        return try {
            scope.async { work() }.also { job ->
                // Also releases admission if the owner was canceled before the body could start.
                job.invokeOnCompletion { slots.release() }
            }
        } catch (error: Throwable) {
            slots.release()
            throw error
        }
    }
}
