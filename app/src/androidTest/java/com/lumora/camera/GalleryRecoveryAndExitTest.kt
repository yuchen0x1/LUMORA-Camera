package com.lumora.camera

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoIndexRecoveryOutcome
import com.lumora.camera.data.PhotoRecord
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.ui.LumoraTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GalleryRecoveryAndExitTest {
    // Match CameraPermissionNavigationTest: IO must not resume the Compose recomposer on IO.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "gallery-recovery-exit-test-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val repository = PhotoRepository(context, namespace)
    private val media = File(context.cacheDir, namespace).apply { check(mkdirs()) }
    private val session = GallerySession()
    private var backCalls = 0

    @Before fun startRecomposer() { compose.mainClock.advanceTimeByFrame() }

    @After fun cleanup() {
        // Only this isolated namespace can contain the renders made by these tests.
        repository.getRecords().map { Uri.parse(it.enhancedUri) }.distinct()
            .filter { it.scheme == "content" && it.authority == "media" }.forEach {
                context.contentResolver.delete(it, null, null)
            }
        context.deleteSharedPreferences(namespace)
        media.deleteRecursively()
    }

    @Test fun previewAndCancelNeverRestoreUntilTheUserConfirms() {
        val record = fixture()
        val backup = JSONArray().put(json(record)).toString()
        assertTrue(prefs.edit().putString("recordsRecovery.readable", backup)
            .putString("recordsRecovery.broken", "{truncated").commit())
        val original = prefs.all.toMap()
        val bytes = File(Uri.parse(record.originalUri).path!!).readBytes()
        showGallery()
        awaitRecoveryNotice()
        assertEquals("Opening the library must not rewrite evidence", original, prefs.all)
        assertTrue(repository.getRecords().isEmpty())

        compose.onNodeWithTag("index-recovery-preview").performClick()
        compose.waitUntil(10_000) { session.recoveryPreview != null && !session.busy }
        assertEquals(listOf(record), session.recoveryPreview!!.records)
        compose.onNodeWithTag("index-recovery-confirm").assertIsEnabled()
        assertEquals("A preview is not a restore", original, prefs.all)
        compose.onNodeWithText("暂不恢复").performClick()
        assertFalse(session.recoveryOpen)
        assertEquals(original, prefs.all)
        assertTrue(repository.getRecords().isEmpty())

        compose.onNodeWithTag("index-recovery-preview").performClick()
        compose.waitUntil(10_000) { session.recoveryPreview != null && !session.busy }
        compose.onNodeWithTag("index-recovery-confirm").performClick()
        compose.waitUntil(10_000) { session.recoveryResult != null && !session.busy }
        assertEquals(PhotoIndexRecoveryOutcome.RESTORED, session.recoveryResult!!.outcome)
        assertEquals(1, session.recoveryResult!!.restoredCount)
        compose.onNodeWithTag("index-recovery-result").assertIsDisplayed()
        assertEquals(listOf(record), PhotoRepository(context, namespace).getRecords())
        assertEquals(backup, prefs.getString("recordsRecovery.readable", null))
        assertEquals("{truncated", prefs.getString("recordsRecovery.broken", null))
        assertArrayEquals("Recovery must not modify photo bytes", bytes, File(Uri.parse(record.originalUri).path!!).readBytes())
    }

    @Test fun aBackupWithoutReadableMediaDoesNotOfferOrClaimSuccessfulRecovery() {
        val missing = fixture().copy(originalUri = Uri.fromFile(File(media, "missing.jpg")).toString(),
            enhancedUri = Uri.fromFile(File(media, "missing.jpg")).toString())
        assertTrue(prefs.edit().putString("recordsRecovery.missing", JSONArray().put(json(missing)).toString()).commit())
        val original = prefs.all.toMap()
        showGallery()
        awaitRecoveryNotice()
        compose.onNodeWithTag("index-recovery-preview").performClick()
        compose.waitUntil(10_000) { session.recoveryPreview != null && !session.busy }
        assertEquals(1, session.recoveryPreview!!.unreadableMediaCount)
        assertTrue(session.recoveryPreview!!.records.isEmpty())
        compose.onNodeWithTag("index-recovery-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("index-recovery-result").assertDoesNotExist()
        assertNull(session.recoveryResult)
        assertEquals(original, prefs.all)
        assertTrue(repository.getRecords().isEmpty())
    }

    @Test fun privacyExplainsPersistentSharedAccessAndNonDestructiveRemoval() {
        showGallery()
        compose.onNodeWithContentDescription("照片与隐私").performClick()
        compose.onNodeWithText("导入仅访问你选择的照片", substring = true).assertIsDisplayed()
        compose.onNodeWithText("目前不会在移除或导入失败时自动撤销共享权限", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        assertTrue(repository.getRecords().isEmpty())
        assertFalse(prefs.contains("records"))
    }

    @Test fun toolbarAndSystemBackBothProtectTheSameDirtyDraftAndUndoHistory() {
        val record = fixture()
        repository.addRecord(record)
        showEditor(record)
        changeStrength()
        val draft = compose.runOnIdle { session.draft }
        assertTrue(session.hasUnsavedChanges)
        assertTrue(session.canUndo)

        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithTag("editor-keep-editing").performClick()
        assertEquals(draft, session.draft)
        assertTrue(session.hasUnsavedChanges)
        assertTrue(session.canUndo)
        assertEquals(0, backCalls)

        Espresso.pressBack()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithTag("editor-discard").performClick()
        compose.runOnIdle {
            assertNull(session.draft)
            assertFalse(session.hasUnsavedChanges)
            assertEquals(record, session.selected)
            assertEquals(0, backCalls)
        }
        assertEquals(listOf(record), repository.getRecords())
        assertTrue(File(Uri.parse(record.originalUri).path!!).exists())
    }

    @Test fun unchangedAndUndoneDraftsCanLeaveWithoutADiscardPrompt() {
        val record = fixture()
        repository.addRecord(record)
        showEditor(record)
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertDoesNotExist()
        assertNull(session.draft)

        compose.onNodeWithText("编辑").performScrollTo().performClick()
        changeStrength()
        compose.onNodeWithContentDescription("撤销调整").performClick()
        assertFalse(session.hasUnsavedChanges)
        assertTrue(session.canRedo)
        Espresso.pressBack()
        compose.onNodeWithText("放弃未保存的修改？").assertDoesNotExist()
        assertNull(session.draft)
        assertEquals(listOf(record), repository.getRecords())
    }

    @Test fun fullResolutionSaveClearsDirtyAndHistoryOnlyAfterConfirmedSuccess() {
        val record = fixture()
        repository.addRecord(record)
        val bytes = File(Uri.parse(record.originalUri).path!!).readBytes()
        showEditor(record)
        changeStrength()
        assertTrue(session.hasUnsavedChanges)
        compose.onNodeWithText("保存新版本").performClick()
        compose.waitUntil(30_000) { !session.busy && session.draft == null }
        compose.runOnIdle {
            assertNull(session.message)
            assertFalse(session.hasUnsavedChanges)
            assertFalse(session.canUndo)
            assertFalse(session.canRedo)
            assertEquals(record.enhancedUri, session.selected!!.parentUri)
            assertNotEquals(record.enhancedUri, session.selected!!.enhancedUri)
        }
        assertEquals(2, repository.getRecords().size)
        assertEquals(session.selected, PhotoRepository(context, namespace).getRecords().first())
        assertArrayEquals(bytes, File(Uri.parse(record.originalUri).path!!).readBytes())
    }

    @Test fun failedDiskCommitKeepsTheEditorDirtyAndDoesNotPretendSaveCompleted() {
        val record = fixture()
        repository.addRecord(record)
        val controlled = GalleryCommitContext(context, prefs)
        val failingRepository = PhotoRepository(controlled, namespace)
        showEditor(record, failingRepository)
        changeStrength()
        val draft = compose.runOnIdle { session.draft }
        controlled.failNextCommit = true
        compose.onNodeWithText("保存新版本").performClick()
        compose.waitUntil(30_000) { !session.busy && session.message != null }
        compose.runOnIdle {
            assertEquals(1, controlled.failedCommits)
            assertEquals(record, session.selected)
            assertEquals(draft, session.draft)
            assertTrue(session.hasUnsavedChanges)
            assertTrue(session.canUndo)
        }
        assertTrue(failingRepository.getRecoveryStatus().writeUnconfirmed)
        compose.onNodeWithText("好").performClick()
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithTag("editor-keep-editing").performClick()
        assertEquals(draft, session.draft)
        assertTrue(File(Uri.parse(record.originalUri).path!!).exists())
    }

    @Test fun unreadableSourceSaveFailureDoesNotClearDirtyOrUndo() {
        val record = fixture()
        repository.addRecord(record)
        showEditor(record)
        changeStrength()
        val draft = compose.runOnIdle { session.draft }
        assertTrue(File(Uri.parse(record.originalUri).path!!).delete())
        compose.onNodeWithText("保存新版本").performClick()
        compose.waitUntil(10_000) { !session.busy && session.message != null }
        compose.runOnIdle {
            assertEquals(record, session.selected)
            assertEquals(draft, session.draft)
            assertTrue(session.hasUnsavedChanges)
            assertTrue(session.canUndo)
        }
        assertEquals(listOf(record), repository.getRecords())
    }

    private fun showGallery(repo: PhotoRepository = repository) {
        compose.setContent { LumoraTheme { GalleryScreen(repo, onBack = { backCalls++ }, session = session) } }
    }

    private fun showEditor(record: PhotoRecord, repo: PhotoRepository = repository) {
        showGallery(repo)
        compose.runOnIdle { session.open(record); session.edit(record) }
        compose.onNodeWithText("保存新版本").assertIsDisplayed()
    }

    private fun changeStrength() {
        compose.onNodeWithTag("editor-strength-${OptimizationStrength.NATURAL.name}")
            .performScrollTo().performClick()
    }

    private fun awaitRecoveryNotice() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("index-recovery-notice")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fixture(): PhotoRecord {
        val file = File(media, "source.jpg")
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff789abc.toInt())
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = Uri.fromFile(file).toString()
        return PhotoRecord(uri, uri, "natural", 10, sourceOnly = true)
    }

    private fun json(record: PhotoRecord) = JSONObject().apply {
        put("originalUri", record.originalUri)
        put("enhancedUri", record.enhancedUri)
        put("styleId", record.styleId)
        put("createdAt", record.createdAt)
        put("capturedAt", record.capturedAt)
        put("strength", record.strength.name)
        put("intensity", record.intensity.toDouble())
        put("sourceOnly", record.sourceOnly)
        put("sourceIsRendered", record.sourceIsRendered)
    }
}

private class GalleryCommitContext(base: Context, backing: SharedPreferences) : ContextWrapper(base) {
    var failNextCommit = false
    var failedCommits = 0
    private val controlled = object : SharedPreferences by backing {
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { editor.putInt(key, value) }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                override fun commit(): Boolean {
                    if (failNextCommit) {
                        failNextCommit = false
                        failedCommits++
                        editor.apply()
                        return false
                    }
                    return editor.commit()
                }
            }
        }
    }
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = controlled
}
