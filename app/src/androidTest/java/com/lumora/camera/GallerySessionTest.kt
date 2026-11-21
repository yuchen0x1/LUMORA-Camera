package com.lumora.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumora.camera.data.PhotoRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GallerySessionTest {
    private val a = PhotoRecord("content://test/original-a", "content://test/result-a", "natural", 1L)
    private val b = PhotoRecord("content://test/original-b", "content://test/result-b", "natural", 2L)

    @Test fun delayedRemovalDoesNotDiscardAnotherPhotosDraft() {
        val session = GallerySession()
        session.open(a)
        session.open(b)
        session.edit(b)
        val draft = requireNotNull(session.draft).copy(intensity = .4f)
        session.change(draft)
        session.closeIfRemoved(a)
        assertEquals(b, session.selected)
        assertEquals(draft, session.draft)
        assertTrue(session.canUndo)
    }

    @Test fun removalClosesOnlyTheMatchingUneditedDetail() {
        val session = GallerySession()
        session.open(a)
        session.edit(a)
        val draft = session.draft
        session.closeIfRemoved(a)
        assertEquals(draft, session.draft)
        session.finishEditing()
        session.closeIfRemoved(a)
        assertNull(session.selected)
    }
}
