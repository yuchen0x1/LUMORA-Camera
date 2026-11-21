package com.lumora.camera.update

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

/** Exercises the production file transaction, not network or APK certificate verification. */
@RunWith(AndroidJUnit4::class)
class AndroidUpdaterFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<UpdateCacheFiles>()
    private val fixtures = mutableListOf<File>()
    private val firstBytes = "first verified archive".toByteArray()
    private val secondBytes = "second verified archive".toByteArray()

    @After fun cleanup() {
        stores.forEach { it.clear() }
        // Only fixtures this test created; never recursively remove the shared cache.
        fixtures.forEach { it.delete() }
    }

    @Test fun partialIsOutsideProviderAndOnlyPublishedAfterVerificationReturns() = runBlocking {
        val files = store()
        lateinit var partial: File
        files.writeVerified(firstBytes.size.toLong()) {
            partial = it
            assertEquals(context.cacheDir.canonicalFile, it.parentFile?.canonicalFile)
            it.writeBytes(firstBytes)
            assertNoReady(files)
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", it)
                fail("A partial archive must not be exposed by FileProvider")
            } catch (_: IllegalArgumentException) {
                // Only verified-update/ is exposed by the production provider.
            }
        }
        val ready = files.verifiedFile()
        assertEquals(File(context.cacheDir, "verified-update").canonicalFile, ready.parentFile?.canonicalFile)
        assertFalse(partial.exists())
        assertArrayEquals(firstBytes, ready.readBytes())
    }

    @Test fun anotherClientsFailureAndClearKeepReadyFileAndIssuedUriReadable() = runBlocking {
        withTimeout(10_000) {
            val first = store()
            val second = store()
            val ready = publish(first, firstBytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val expected = IOException("interrupted transfer")
            lateinit var partial: File
            val downloading = async {
                try {
                    second.writeVerified(secondBytes.size.toLong()) {
                        partial = it
                        it.writeBytes(secondBytes)
                        entered.complete(Unit)
                        release.await()
                        throw expected
                    }
                    null
                } catch (error: IOException) { error }
            }
            entered.await()
            assertArrayEquals(firstBytes, ready.readBytes())
            release.complete(Unit)
            assertSame(expected, downloading.await())
            assertFalse(partial.exists())
            second.clear()
            assertEquals(ready, first.verifiedFile())
            context.contentResolver.openInputStream(uri).use { input ->
                assertNotNull(input)
                assertArrayEquals(firstBytes, requireNotNull(input).readBytes())
            }
        }
    }

    @Test fun anotherClientsPromotionAndClearDoNotRemoveAnInFlightPartial() = runBlocking {
        withTimeout(10_000) {
            val first = store()
            val second = store()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var partial: File
            val downloading = async {
                first.writeVerified(firstBytes.size.toLong()) {
                    partial = it
                    it.writeBytes(firstBytes)
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val secondReady = publish(second, secondBytes)
            assertNotEquals(partial.name.removeSuffix(".part.apk") + ".apk", secondReady.name)
            second.clear()
            assertFalse(secondReady.exists())
            assertTrue(partial.exists())
            release.complete(Unit)
            downloading.await()
            val firstReady = first.verifiedFile()
            second.clear()
            assertArrayEquals(firstBytes, firstReady.readBytes())
        }
    }

    @Test fun clientReuseKeepsPreviouslyIssuedPathsImmutableUntilExplicitClear() = runBlocking {
        val files = store()
        val previous = publish(files, firstBytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", previous)
        val current = publish(files, secondBytes)
        assertNotEquals(previous, current)
        assertArrayEquals(firstBytes, previous.readBytes())
        assertArrayEquals(secondBytes, current.readBytes())
        context.contentResolver.openInputStream(uri).use { input ->
            assertArrayEquals(firstBytes, requireNotNull(input).readBytes())
        }
        files.clear()
        assertFalse(previous.exists())
        assertFalse(current.exists())
        assertNoReady(files)
    }

    @Test fun clearRetiresButKeepsLeasedVerifiedFileUntilMatchingRelease() = runBlocking {
        val files = store()
        val foreign = store()
        val ready = publish(files, firstBytes)
        val lease = files.acquireInstallLease()
        assertEquals(ready, files.leasedFile(lease))

        files.clear()
        assertNoReady(files)
        assertTrue(ready.isFile)
        assertArrayEquals(firstBytes, ready.readBytes())
        assertFalse("A different cache owner must not release this lease", foreign.releaseInstallLease(lease))
        assertTrue(ready.isFile)

        assertTrue(files.releaseInstallLease(lease))
        assertFalse(ready.exists())
        assertFalse(files.releaseInstallLease(lease))
    }

    @Test fun failedReplacementCleansPartialWithoutRemovingPreviousReady() = runBlocking {
        val files = store()
        val previous = publish(files, firstBytes)
        val expected = IOException("archive verification failed")
        lateinit var partial: File
        try {
            files.writeVerified(secondBytes.size.toLong()) {
                partial = it
                it.writeBytes(secondBytes)
                throw expected
            }
            fail("Expected verification failure")
        } catch (error: IOException) {
            assertSame(expected, error)
        }
        assertFalse(partial.exists())
        assertEquals(previous, files.verifiedFile())
        assertArrayEquals(firstBytes, previous.readBytes())
    }

    @Test fun cancelledReplacementCleansPartialWithoutRemovingPreviousReady() = runBlocking {
        withTimeout(10_000) {
            val files = store()
            val previous = publish(files, firstBytes)
            val entered = CompletableDeferred<Unit>()
            lateinit var partial: File
            val downloading = launch {
                files.writeVerified(secondBytes.size.toLong()) {
                    partial = it
                    it.outputStream().use { output ->
                        output.write(secondBytes)
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            entered.await()
            downloading.cancelAndJoin()
            assertFalse(partial.exists())
            assertEquals(previous, files.verifiedFile())
            assertArrayEquals(firstBytes, previous.readBytes())
        }
    }

    @Test fun cancellationDuringNonCooperativeVerificationCannotPromoteItsFile() = runBlocking {
        withTimeout(10_000) {
            val files = store()
            val previous = publish(files, firstBytes)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var partial: File
            val downloading = launch {
                files.writeVerified(secondBytes.size.toLong()) {
                    partial = it
                    it.writeBytes(secondBytes)
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
            entered.await()
            downloading.cancel()
            release.complete(Unit)
            downloading.join()
            assertTrue(downloading.isCancelled)
            assertFalse(partial.exists())
            assertEquals(previous, files.verifiedFile())
            assertArrayEquals(firstBytes, previous.readBytes())
        }
    }

    @Test fun cancellationAfterPromotionBeforeIoReturnRollsBackOnlyThatAttempt() = runBlocking {
        withTimeout(10_000) {
            val files = store()
            val previous = publish(files, firstBytes)
            val pending = LinkedBlockingQueue<Runnable>()
            val returnQueued = CompletableDeferred<Unit>()
            val finishVerification = CompletableDeferred<Unit>()
            val gate = Any()
            var paused = true
            val caller = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    synchronized(gate) {
                        if (paused) {
                            pending.add(block)
                            returnQueued.complete(Unit)
                        } else Dispatchers.Default.dispatch(context, block)
                    }
                }
            }
            val downloading = launch(caller, start = CoroutineStart.UNDISPATCHED) {
                files.writeVerified(secondBytes.size.toLong()) {
                    it.writeBytes(secondBytes)
                    finishVerification.await()
                }
            }
            var completion: Runnable? = null
            lateinit var promoted: File
            try {
                // Force suspension before IO can finish; a fast return need not dispatch.
                finishVerification.complete(Unit)
                returnQueued.await()
                // IO has promoted the file, but the caller cannot observe success yet.
                completion = checkNotNull(pending.poll())
                promoted = files.verifiedFile()
                assertNotEquals(previous, promoted)
                assertTrue(promoted.isFile)
            } finally {
                downloading.cancel()
                val queued = synchronized(gate) {
                    paused = false
                    mutableListOf<Runnable>().also { pending.drainTo(it) }
                }
                queued.forEach { it.run() }
                completion?.run()
            }
            downloading.join()
            assertTrue(downloading.isCancelled)
            assertFalse(promoted.exists())
            assertEquals(previous, files.verifiedFile())
            assertArrayEquals(firstBytes, previous.readBytes())
        }
    }

    @Test fun clearedLateWriterCannotPromoteOrCleanUpTheSameClientsReplacement() = runBlocking {
        withTimeout(10_000) {
            val files = store()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var oldPartial: File
            val obsolete = async {
                try {
                    files.writeVerified(firstBytes.size.toLong()) {
                        oldPartial = it
                        it.writeBytes(firstBytes)
                        entered.complete(Unit)
                        release.await()
                        // Model blocking IO that recreates its path after clear returned.
                        it.writeBytes(firstBytes)
                    }
                    null
                } catch (error: UpdateFailure) { error }
            }
            entered.await()
            files.clear()
            val replacement = publish(files, secondBytes)
            release.complete(Unit)
            assertNotNull(obsolete.await())
            assertFalse(oldPartial.exists())
            assertEquals(replacement, files.verifiedFile())
            assertArrayEquals(secondBytes, replacement.readBytes())
        }
    }

    @Test fun promotionFailureDoesNotOverwriteOrDeleteAnUnownedDestination() = runBlocking {
        val files = store()
        lateinit var partial: File
        lateinit var existing: File
        try {
            files.writeVerified(firstBytes.size.toLong()) {
                partial = it
                it.writeBytes(firstBytes)
                existing = fixture(File(context.cacheDir, "verified-update/" +
                    it.name.removeSuffix(".part.apk") + ".apk"), secondBytes)
            }
            fail("Promotion must not replace an existing destination")
        } catch (_: UpdateFailure) {
            // A failed promotion only owns the partial, not this pre-existing target.
        }
        files.clear()
        assertFalse(partial.exists())
        assertArrayEquals(secondBytes, existing.readBytes())
        assertNoReady(files)
    }

    @Test fun newClientDoesNotAdoptOrSweepFilesLeftByAnotherProcess() = runBlocking {
        val files = store()
        publish(files, firstBytes) // Ensure the production verified directory exists.
        files.clear()
        val orphanPartial = fixture(File(context.cacheDir, "lumora-update-${UUID.randomUUID()}.part.apk"), firstBytes)
        val orphanVerified = fixture(File(context.cacheDir, "verified-update/lumora-update-${UUID.randomUUID()}.apk"), secondBytes)
        val nextClient = store()
        assertNoReady(nextClient)
        val ready = publish(nextClient, firstBytes)
        nextClient.clear()
        assertFalse(ready.exists())
        assertArrayEquals(firstBytes, orphanPartial.readBytes())
        assertArrayEquals(secondBytes, orphanVerified.readBytes())
    }

    private fun store() = UpdateCacheFiles(context.cacheDir).also { stores.add(it) }

    private suspend fun publish(files: UpdateCacheFiles, bytes: ByteArray): File {
        files.writeVerified(bytes.size.toLong()) { it.writeBytes(bytes) }
        return files.verifiedFile()
    }

    private fun fixture(file: File, bytes: ByteArray): File {
        assertTrue("A test fixture must not overwrite an existing file", file.createNewFile())
        fixtures.add(file)
        file.writeBytes(bytes)
        return file
    }

    private fun assertNoReady(files: UpdateCacheFiles) {
        try {
            files.verifiedFile()
            fail("No verified file should be published")
        } catch (_: UpdateFailure) {
            // No automatic adoption of unrelated or unverified files.
        }
    }
}
