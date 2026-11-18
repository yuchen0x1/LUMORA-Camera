package com.lumora.camera

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.image.PhotoEditParameters
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoRepositoryMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "migration-test-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val repo = PhotoRepository(context, namespace)

    @After fun cleanup() { context.deleteSharedPreferences(namespace) }

    @Test fun legacyRecordAndSingleFileLegacyRecordRemainReadableAfterEdit() {
        prefs.edit().putString("records", """[
            {"originalUri":"content://image/source","enhancedUri":"content://image/one","createdAt":7,"styleId":"portra"},
            {"originalUri":"content://image/old","enhancedUri":"content://image/old","createdAt":6,"styleId":"vintage"}
        ]""").commit()
        val (one, old) = repo.getRecords()
        assertFalse(one.favorite)
        assertFalse(one.recipeKnown)
        assertFalse(one.sourceIsRendered)
        assertTrue(old.sourceIsRendered)
        assertEquals(PhotoEditParameters.NATURAL, one.edits)
        assertEquals(OptimizationStrength.STANDARD, one.strength)
        val version = one.copy(
            enhancedUri = "content://image/two", createdAt = 8, parentUri = one.enhancedUri,
            strength = OptimizationStrength.STRONG, intensity = 0.6f,
            edits = PhotoEditParameters(exposure = 1.2f, tint = -0.2f, detail = 0.5f)
        )
        repo.addRecord(version)
        repo.setFavorite(version.enhancedUri, true)
        repo.setStyleFavorite("vintage", true)
        val reopened = PhotoRepository(context, namespace)
        assertEquals(listOf(version.copy(favorite = true), one, old), reopened.getRecords())
        assertEquals(setOf("vintage"), reopened.favoriteStyleIds)
        reopened.removeRecord(version)
        assertEquals(listOf(one, old), repo.getRecords())
    }

    @Test fun completedCaptureReplacesOnlyItsSourcePlaceholder() {
        val pending = PhotoRecord("src", "src", "natural", 10, sourceOnly = true)
        repo.addRecord(pending)
        repo.setFavorite("src", true)
        val other = PhotoRecord("src", "unrelated-edit", "portra", 11)
        repo.addRecord(other)
        val complete = PhotoRecord("src", "result", "natural", 10)
        repo.replaceSourceRecord(complete)
        assertEquals(listOf(other, complete.copy(favorite = true)), repo.getRecords())
    }

    @Test fun removedCaptureStaysHiddenOnCompletionAndUndoRestoresFinishedVersion() {
        val camera = PhotoRepository(context, namespace)
        val source = PhotoRecord("src", "src", "natural", 10, sourceOnly = true)
        val done = source.copy(enhancedUri = "result", sourceOnly = false)
        camera.addRecord(source)
        repo.setFavorite("src", true)
        repo.styleSampleUri = "src"
        val removal = requireNotNull(repo.removeRecord(source))
        repo.styleSampleUri = "another-source"
        camera.replaceSourceRecord(done)
        assertTrue(repo.getRecords().isEmpty())
        repo.undoRemoval(removal)
        assertEquals(listOf(done.copy(favorite = true)), repo.getRecords())
        assertEquals("another-source", repo.styleSampleUri)
        repo.setFavorite("result", false)
        camera.replaceSourceRecord(done.copy(favorite = true))
        repo.undoRemoval(removal)
        assertEquals(listOf(done), repo.getRecords())
    }

    @Test fun undoBeforeCaptureFinishesLetsCompletionUpdateTheRestoredRow() {
        val source = PhotoRecord("src", "src", "natural", 10, sourceOnly = true)
        val done = source.copy(enhancedUri = "result", sourceOnly = false)
        repo.addRecord(source)
        val removal = requireNotNull(repo.removeRecord(source))
        repo.undoRemoval(removal)
        repo.setFavorite("src", true)
        repo.replaceSourceRecord(done)
        assertEquals(listOf(done.copy(favorite = true)), repo.getRecords())
    }

    @Test fun independentCaptureAndEditorRepositoriesKeepBothSetsOfUpdates() {
        val camera = PhotoRepository(context, namespace)
        val editor = PhotoRepository(context, namespace)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val captures = pool.submit {
                start.await()
                repeat(10) { camera.addRecord(PhotoRecord("source-$it", "result-$it", "natural", it.toLong())) }
            }
            val edits = pool.submit {
                start.await()
                repeat(10) { editor.addRecord(PhotoRecord("source-$it", "edit-$it", "vintage", 10L + it)) }
            }
            start.countDown()
            captures.get(10, java.util.concurrent.TimeUnit.SECONDS)
            edits.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(20, repo.getRecords().distinctBy { it.enhancedUri }.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
