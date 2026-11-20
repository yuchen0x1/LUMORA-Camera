package com.lumora.camera

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.image.PhotoEditParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun malformedIndexIsPreservedWhenNewCapturesAreAdded() {
        val damaged = "{truncated"
        prefs.edit().putString("records", damaged).commit()
        assertTrue(repo.getRecords().isEmpty())
        assertEquals(damaged, prefs.getString("records", null))
        repo.setFavorite("missing", true)
        assertEquals(damaged, prefs.getString("records", null))

        val record = PhotoRecord("source", "result", "natural", 1)
        repo.addRecord(record)
        assertEquals(listOf(record), PhotoRepository(context, namespace).getRecords())
        assertEquals(listOf(damaged), prefs.all.filterKeys { it.startsWith("recordsRecovery.") }.values.toList())

        repo.setFavorite("result", true)
        assertEquals(1, prefs.all.keys.count { it.startsWith("recordsRecovery.") })
        prefs.edit().putString("records", "[broken").commit()
        repo.addRecord(record.copy(enhancedUri = "second"))
        assertEquals(setOf(damaged, "[broken"),
            prefs.all.filterKeys { it.startsWith("recordsRecovery.") }.values.toSet())
    }

    @Test fun observationContinuesAfterReplacingADamagedIndex() = runBlocking {
        withTimeout(10_000) {
            prefs.edit().putString("records", "{truncated").commit()
            val initial = CompletableDeferred<Unit>()
            val emissions = async {
                repo.observeRecords().onEach { if (it.isEmpty()) initial.complete(Unit) }
                    .take(2).toList()
            }
            initial.await()
            val record = PhotoRecord("source", "result", "natural", 1)
            PhotoRepository(context, namespace).addRecord(record)
            assertEquals(listOf(emptyList<PhotoRecord>(), listOf(record)), emissions.await())
        }
        Unit
    }

    @Test fun nonArrayIndexesRemainUnchangedWhenRead() {
        listOf("", "null", "{}", "not-json").forEach { damaged ->
            prefs.edit().putString("records", damaged).commit()
            assertTrue(repo.getRecords().isEmpty())
            assertEquals(damaged, prefs.getString("records", null))
        }
    }

    @Test fun styleIntensityIsFiniteAndClampedOnReadAndWrite() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            prefs.edit().putFloat("styleIntensity", invalid).commit()
            assertEquals(1f, repo.styleIntensity, 0f)
            repo.styleIntensity = invalid
            assertEquals(1f, prefs.getFloat("styleIntensity", 0f), 0f)
        }
        repo.styleIntensity = -0.5f
        assertEquals(0f, repo.styleIntensity, 0f)
        repo.styleIntensity = 1.5f
        assertEquals(1f, repo.styleIntensity, 0f)
        repo.styleIntensity = 0.4f
        assertEquals(0.4f, repo.styleIntensity, 0f)
    }

    @Test fun recordIntensityIsNormalizedOnReadAndWrite() {
        prefs.edit().putString("records", """[
            {"originalUri":"source","enhancedUri":"legacy","intensity":"NaN"},
            {"originalUri":"source","enhancedUri":"large","intensity":3},
            {"originalUri":"source","enhancedUri":"negative","intensity":-2}
        ]""").commit()
        assertEquals(mapOf("legacy" to 1f, "large" to 1f, "negative" to 0f),
            repo.getRecords().associate { it.enhancedUri to it.intensity })
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 2f, 0.6f)
            .forEachIndexed { index, intensity ->
                val uri = "result-$index"
                repo.addRecord(PhotoRecord("source", uri, "natural", index.toLong(), intensity = intensity))
                val expected = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 1f
                assertEquals(expected, repo.getRecords().first { it.enhancedUri == uri }.intensity, 0f)
            }
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
