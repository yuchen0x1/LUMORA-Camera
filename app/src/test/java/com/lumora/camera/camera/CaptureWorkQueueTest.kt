package com.lumora.camera.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CaptureWorkQueueTest {
    @Test fun admittedWorkSurvivesScreenCancellation() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CaptureWorkQueue(owner, 1)
            val finish = CompletableDeferred<Unit>()
            var indexed = false
            val work = queue.submit { finish.await(); indexed = true; 7 }
            val screen = async { work.await() }
            yield()
            screen.cancel()
            finish.complete(Unit)
            assertEquals(7, work.await())
            assertTrue(indexed)
        } finally { owner.cancel() }
    }

    @Test fun cancelledAdmissionNeverFiresAnotherShutter() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CaptureWorkQueue(owner, 1)
            val finish = CompletableDeferred<Unit>()
            val first = queue.submit { finish.await() }
            var fired = false
            val waiting = async { queue.submit { fired = true } }
            yield()
            assertFalse(waiting.isCompleted)
            waiting.cancel()
            waiting.join()
            finish.complete(Unit)
            first.await()
            yield()
            assertFalse(fired)
            assertEquals(9, queue.submit { 9 }.await())
        } finally { owner.cancel() }
    }

    @Test fun failedRenderReleasesItsBoundedSlot() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CaptureWorkQueue(owner, 1)
            val failed = queue.submit<Int> { error("render") }
            assertTrue(runCatching { failed.await() }.isFailure)
            assertEquals(2, queue.submit { 2 }.await())
        } finally { owner.cancel() }
    }

    @Test fun permitsOnlyTwoInFlightOperations() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CaptureWorkQueue(owner)
            val finish = CompletableDeferred<Unit>()
            val first = queue.submit { finish.await() }
            val second = queue.submit { finish.await() }
            var thirdStarted = false
            val waiting = async { queue.submit { thirdStarted = true } }
            yield()
            assertFalse(thirdStarted)
            finish.complete(Unit)
            first.await(); second.await(); waiting.await().await()
            assertTrue(thirdStarted)
        } finally { owner.cancel() }
    }
}
