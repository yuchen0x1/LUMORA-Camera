package com.lumora.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumora.camera.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaptureReconciliationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withFixture(test: (CaptureJournal, PhotoRepository, String, MutableList<Uri>) -> Unit) {
        val id = "LUMORA_recovery_${UUID.randomUUID().toString().replace('-', '_')}"
        val journalName = "test_journal_$id"
        val repositoryName = "test_repository_$id"
        val uris = mutableListOf<Uri>()
        try { test(CaptureJournal(context, journalName), PhotoRepository(context, repositoryName), id, uris) }
        finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
            context.deleteSharedPreferences(journalName)
            context.deleteSharedPreferences(repositoryName)
        }
    }

    private fun source(name: String, uris: MutableList<Uri>): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LUMORA-Tests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        uris += uri
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Test fun publishedSourceBeforeCallbackIsRecoveredOnce() = withFixture { journal, repository, id, uris ->
        assertTrue(journal.begin(id, "natural", "STANDARD", 1f, 12L))
        val original = source("${id}_ORIGINAL.jpg", uris)
        CaptureReconciliation.run(context, journal, repository)
        assertEquals(original.toString(), repository.getRecords().single().originalUri)
        assertTrue(repository.getRecords().single().sourceOnly)
        assertTrue(journal.pending().isEmpty())
        CaptureReconciliation.run(context, journal, repository)
        assertEquals(1, repository.getRecords().size)
    }

    @Test fun completedRenderSurvivesInterruptedIndexReplacement() = withFixture { journal, repository, id, uris ->
        val original = source("${id}_ORIGINAL.jpg", uris)
        val rendered = source("${id}_natural.jpg", uris)
        journal.begin(id, "natural", "STANDARD", 1f, 12L)
        journal.attachSource(id, original.toString())
        repository.addRecord(PhotoRecord(original.toString(), original.toString(), "natural", 12L, sourceOnly = true, favorite = true))
        journal.markIndexed(id)
        journal.attachRender(id, rendered.toString())
        CaptureReconciliation.run(context, journal, repository)
        val result = repository.getRecords().single()
        assertEquals(rendered.toString(), result.enhancedUri)
        assertFalse(result.sourceOnly)
        assertTrue(result.favorite)
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun failedIndexCommitRetainsJournalUntilPendingWriteIsConfirmed() = withFixture { journal, _, id, uris ->
        val repositoryName = "test_repository_$id"
        val controlled = CommitControlContext(context, context.getSharedPreferences(repositoryName, Context.MODE_PRIVATE))
        val repository = PhotoRepository(controlled, repositoryName)
        val original = source("${id}_ORIGINAL.jpg", uris)
        val rendered = source("${id}_natural.jpg", uris)
        assertTrue(journal.begin(id, "natural", "STANDARD", 1f, 12L))
        assertTrue(journal.attachSource(id, original.toString()))
        assertTrue(journal.attachRender(id, rendered.toString()))
        val pending = journal.pending()
        val completed = PhotoRecord(original.toString(), rendered.toString(), "natural", 12L)

        controlled.failNextCommit = true
        val failedIndex = CaptureReconciliation.run(context, journal, repository)
        assertTrue(failedIndex, failedIndex.contains("retry=1"))
        assertEquals(1, controlled.commitCalls)
        assertTrue(repository.getRecoveryStatus().writeUnconfirmed)
        assertEquals(listOf(completed), repository.getRecords())
        assertEquals(pending, journal.pending())

        controlled.failNextCommit = true
        val failedConfirmation = CaptureReconciliation.run(context, journal, repository)
        assertTrue(failedConfirmation, failedConfirmation.contains("retry=1"))
        assertEquals(2, controlled.commitCalls)
        assertTrue(repository.getRecoveryStatus().writeUnconfirmed)
        assertEquals(listOf(completed), repository.getRecords())
        assertEquals("An in-memory completed render must not consume the journal", pending, journal.pending())

        val confirmed = CaptureReconciliation.run(context, journal, repository)
        assertTrue(confirmed, confirmed.contains("already_indexed=1"))
        assertEquals(3, controlled.commitCalls)
        assertFalse(repository.getRecoveryStatus().writeUnconfirmed)
        assertEquals(listOf(completed), repository.getRecords())
        assertTrue(journal.pending().isEmpty())
        CaptureReconciliation.run(context, journal, repository)
        assertEquals(3, controlled.commitCalls)
        assertEquals(listOf(completed), repository.getRecords())
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun failedRemovalCommitRetainsJournalAndDoesNotResurrectCaptureAfterConfirmation() = withFixture { journal, _, id, uris ->
        val repositoryName = "test_repository_$id"
        val controlled = CommitControlContext(context, context.getSharedPreferences(repositoryName, Context.MODE_PRIVATE))
        val repository = PhotoRepository(controlled, repositoryName)
        val original = source("${id}_ORIGINAL.jpg", uris)
        val rendered = source("${id}_natural.jpg", uris)
        assertTrue(journal.begin(id, "natural", "STANDARD", 1f, 12L))
        assertTrue(journal.attachSource(id, original.toString()))
        assertTrue(journal.attachRender(id, rendered.toString()))
        val pending = journal.pending()
        // Removal can happen before markIndexed; only the tombstone prevents resurrection.
        assertFalse(pending.single().sourceIndexed)
        val record = PhotoRecord(original.toString(), original.toString(), "natural", 12L, sourceOnly = true)
        repository.addRecord(record)
        controlled.failNextCommit = true
        try {
            repository.removeRecord(record)
            fail("An unconfirmed removal must not report success")
        } catch (_: IOException) { }
        assertEquals(2, controlled.commitCalls)
        assertTrue(repository.getRecoveryStatus().writeUnconfirmed)
        assertTrue(repository.getRecords().isEmpty())
        assertTrue(repository.wasCaptureRemoved(original.toString(), 12L))
        assertEquals(pending, journal.pending())

        controlled.failNextCommit = true
        val failedConfirmation = CaptureReconciliation.run(context, journal, repository)
        assertTrue(failedConfirmation, failedConfirmation.contains("retry=1"))
        assertEquals(3, controlled.commitCalls)
        assertTrue(repository.getRecoveryStatus().writeUnconfirmed)
        assertTrue(repository.getRecords().isEmpty())
        assertTrue(repository.wasCaptureRemoved(original.toString(), 12L))
        assertEquals("An unconfirmed removal must not consume the journal", pending, journal.pending())

        val confirmed = CaptureReconciliation.run(context, journal, repository)
        assertTrue(confirmed, confirmed.contains("already_indexed=1"))
        assertEquals(4, controlled.commitCalls)
        assertFalse(repository.getRecoveryStatus().writeUnconfirmed)
        assertTrue(repository.getRecords().isEmpty())
        assertTrue(repository.wasCaptureRemoved(original.toString(), 12L))
        assertTrue(journal.pending().isEmpty())
        CaptureReconciliation.run(context, journal, repository)
        assertEquals(4, controlled.commitCalls)
        assertTrue(repository.getRecords().isEmpty())
        assertTrue(repository.wasCaptureRemoved(original.toString(), 12L))
        assertTrue(journal.pending().isEmpty())
        assertNotNull(context.contentResolver.openInputStream(original)?.use { it.read() })
        assertNotNull(context.contentResolver.openInputStream(rendered)?.use { it.read() })
    }

    @Test fun intentionallyRemovedIndexedSourceIsNotResurrected() = withFixture { journal, repository, id, uris ->
        val original = source("${id}_ORIGINAL.jpg", uris)
        journal.begin(id, "natural", "STANDARD", 1f, 12L)
        journal.attachSource(id, original.toString())
        journal.markIndexed(id)
        CaptureReconciliation.run(context, journal, repository)
        assertTrue(repository.getRecords().isEmpty())
        assertTrue(journal.pending().isEmpty())
        assertNotNull(context.contentResolver.openInputStream(original)?.use { it.read() })
    }

    @Test fun recycledMediaIdCannotRecoverAnUnrelatedPhoto() = withFixture { journal, repository, id, uris ->
        val unrelated = source("${id}_UNRELATED.jpg", uris)
        journal.begin(id, "natural", "STANDARD", 1f, 12L)
        journal.attachSource(id, unrelated.toString())
        CaptureReconciliation.run(context, journal, repository)
        assertTrue(repository.getRecords().isEmpty())
        assertNotNull(context.contentResolver.openInputStream(unrelated)?.use { it.read() })
    }

    @Test fun journalCanBeReopenedBeforeSourceCallback() = withFixture { journal, _, id, _ ->
        assertTrue(journal.begin(id, "natural", "STANDARD", .6f, 12L))
        val reopened = CaptureJournal(context, "test_journal_$id").pending().single()
        assertEquals(id, reopened.id)
        assertNull(reopened.originalUri)
        assertEquals(.6f, reopened.intensity, .0001f)
    }

    @Test fun activeCaptureIsLeftToItsOwningPipeline() = withFixture { journal, repository, id, _ ->
        journal.begin(id, "natural", "STANDARD", 1f, 12L)
        CaptureReconciliation.run(context, journal, repository, isActive = { it == id })
        assertEquals(1, journal.pending().size)
        assertTrue(repository.getRecords().isEmpty())
    }
}
