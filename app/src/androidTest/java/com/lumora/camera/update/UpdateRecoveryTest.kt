package com.lumora.camera.update

import android.app.Application
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class UpdateRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val namespace = "update-recovery-${UUID.randomUUID()}"
    private val prefs = application.getSharedPreferences(namespace, 0)
    private val store = ViewModelStore()
    private val client = FakeUpdates()
    private var now = TimeUnit.DAYS.toMillis(100)
    private lateinit var model: UpdateViewModel

    @Before fun setup() {
        prefs.edit().putString("frequency", UpdateFrequency.WEEKLY.name).commit()
        createModel()
    }

    @After fun cleanup() {
        main { store.clear() }
        application.deleteSharedPreferences(namespace)
    }

    @Test fun offlineAutomaticCheckRetriesAfterShortBackoffAndSurvivesRecreation() {
        client.checkResult = { throw UpdateFailure("网络不可用") }
        check(manual = false)
        assertEquals(0, prefs.getLong("lastSuccessfulCheck", 0))
        assertEquals(now, prefs.getLong("lastFailedCheck", 0))
        main { model.check(manual = false) }
        assertEquals(1, client.checks)
        now += UpdateCheckPolicy.failureBackoffMillis
        createModel()
        client.checkResult = { null }
        check(manual = false)
        assertEquals(2, client.checks)
        assertEquals(now, prefs.getLong("lastSuccessfulCheck", 0))
        assertFalse(prefs.contains("lastFailedCheck"))
    }

    @Test fun oldAttemptTimestampDoesNotSuppressFirstSuccessfulCheck() {
        prefs.edit().putLong("lastChecked", now).commit()
        check(manual = false)
        assertEquals(1, client.checks)
        main { model.check(manual = false) }
        assertEquals(1, client.checks)
    }

    @Test fun successfulNoOfferUsesCompletionTimeAndWeeklyCadence() {
        client.checkResult = { now += 5_000; null }
        check(manual = false)
        assertEquals(now, prefs.getLong("lastSuccessfulCheck", 0))
        now += TimeUnit.DAYS.toMillis(7) - 1
        main { model.check(manual = false) }
        assertEquals(1, client.checks)
        now++
        check(manual = false)
        assertEquals(2, client.checks)
    }

    @Test fun manualRetryBypassesFailureBackoffAndSuccessNoticeCanBeDismissed() {
        client.checkResult = { throw UpdateFailure("网络不可用") }
        check(manual = true)
        assertTrue((model.state as UpdateState.Notice).retryable)
        showDialog()
        compose.onNodeWithText("网络不可用").assertIsDisplayed()
        client.checkResult = { null }
        compose.onNodeWithText("重新检查").performClick()
        await { model.state is UpdateState.Notice && !(model.state as UpdateState.Notice).retryable }
        assertEquals(2, client.checks)
        compose.onNodeWithText("没有可用的正式更新").assertIsDisplayed()
        compose.onNodeWithText("重新检查").assertDoesNotExist()
        compose.onNodeWithText("知道了").performClick()
        assertEquals(UpdateState.Idle, model.state)
    }

    @Test fun deferredOfferIsHiddenAutomaticallyButManualCheckStillShowsIt() {
        client.checkResult = { offer }
        check(manual = true)
        main { model.later(offer) }
        prefs.edit().remove("lastSuccessfulCheck").commit()
        check(manual = false)
        assertEquals(UpdateState.Idle, model.state)
        check(manual = true)
        assertEquals(UpdateState.Available(offer), model.state)
    }

    @Test fun downloadFailureIsVisibleOutsideSettingsAndCanBeRetried() {
        available()
        client.downloadResult = { throw UpdateFailure("下载校验失败") }
        main { model.download(offer) }
        await { model.state is UpdateState.Notice }
        showDialog()
        compose.onNodeWithText("下载校验失败").assertIsDisplayed()
        // Returning to the foreground must not erase an unacknowledged operation error.
        main { model.check(manual = false) }
        compose.onNodeWithText("下载校验失败").assertIsDisplayed()
        compose.onNodeWithText("重新检查").performClick()
        await { model.state is UpdateState.Available }
        assertEquals(2, client.checks)
    }

    @Test fun installFailureClearsVerifiedFileAndShowsRecoveryWithoutLaunching() {
        ready()
        client.installResult = { throw UpdateFailure("安装包已失效") }
        var launched = false
        main { model.install(offer) { launched = true } }
        await { model.state is UpdateState.Notice }
        assertFalse(launched)
        assertEquals(1, client.clears)
        showDialog()
        compose.onNodeWithText("安装包已失效").assertIsDisplayed()
        compose.onNodeWithText("重新检查").assertIsDisplayed()
    }

    @Test fun cancelledDownloadRestoresOfferAndDoesNotRecordCheckFailure() {
        available()
        client.downloadResult = { awaitCancellation() }
        main { model.download(offer) }
        await { model.state is UpdateState.Downloading }
        main { model.cancelDownload() }
        await { model.state is UpdateState.Available }
        assertFalse(prefs.contains("lastFailedCheck"))
        client.downloadResult = {}
        main { model.download(offer) }
        await { model.state is UpdateState.Ready }
    }

    @Test fun returningFromInstallerCleansUpAndAllowsRecheck() {
        ready()
        var launched = false
        main { model.install(offer) { launched = true } }
        await { launched }
        main { model.installationReturned() }
        assertEquals(1, client.clears)
        assertTrue((model.state as UpdateState.Notice).retryable)
        main { model.check(manual = true) }
        await { model.state is UpdateState.Available }
    }

    @Test fun choosingLaterDuringVerificationNeverLaunchesAnInstallerAfterDismissal() {
        ready()
        val intent = CompletableDeferred<Intent>()
        val cancelled = CompletableDeferred<Unit>()
        client.installResult = {
            try { intent.await() } finally { cancelled.complete(Unit) }
        }
        var launched = false
        main { model.install(offer) { launched = true } }
        main { model.later(offer) }
        intent.complete(Intent(Intent.ACTION_VIEW))
        await { cancelled.isCompleted }
        instrumentation.waitForIdleSync()
        assertFalse(launched)
        assertEquals(UpdateState.Idle, model.state)
        assertEquals(1, client.clears)
    }

    @Test fun verificationFailureAfterChoosingLaterCannotReplaceTheDismissedState() {
        ready()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        client.installResult = {
            withContext(NonCancellable) {
                try {
                    release.await()
                    throw UpdateFailure("安装校验失败")
                } finally { finished.complete(Unit) }
            }
        }
        var launched = false
        main { model.install(offer) { launched = true } }
        main { model.later(offer) }
        release.complete(Unit)
        await { finished.isCompleted }
        instrumentation.waitForIdleSync()
        assertFalse(launched)
        assertEquals(UpdateState.Idle, model.state)
        assertEquals(1, client.clears)
    }

    private fun createModel() = main {
        model = UpdateViewModel(application, client, prefs) { now }
        store.put("updates", model)
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000, condition)
    private fun check(manual: Boolean) {
        val before = client.checks
        main { model.check(manual) }
        await { client.checks > before && model.state != UpdateState.Checking }
        instrumentation.waitForIdleSync()
    }
    private fun available() {
        client.checkResult = { offer }
        check(manual = true)
        assertEquals(UpdateState.Available(offer), model.state)
    }
    private fun ready() {
        available()
        main { model.download(offer) }
        await { model.state is UpdateState.Ready }
    }
    private fun showDialog() {
        compose.setContent { MaterialTheme { UpdateDialog(model) } }
    }

    private class FakeUpdates : UpdateClient {
        override val versionName = "2.1.3"
        var checks = 0
        var clears = 0
        var checkResult: suspend () -> UpdateOffer? = { null }
        var downloadResult: suspend () -> Unit = {}
        var installResult: suspend () -> Intent = { Intent(Intent.ACTION_VIEW) }
        override suspend fun check(): UpdateOffer? { checks++; return checkResult() }
        override fun incompatibility(offer: UpdateOffer): String? = null
        override suspend fun download(offer: UpdateOffer, progress: (Long) -> Unit) = downloadResult()
        override suspend fun installIntent(offer: UpdateOffer): Intent = installResult()
        override fun clearDownloaded() { clears++ }
    }

    private companion object {
        val asset = ReleaseAsset(URI("https://github.com/yuchen0x1/LUMORA-Camera/releases/download/v2.1.4/test.apk"), 10, "a".repeat(64))
        val offer = UpdateOffer(StableRelease("2.1.4", "", Instant.parse("2026-11-21T00:00:00Z"),
            "https://github.com/yuchen0x1/LUMORA-Camera/releases/tag/v2.1.4", asset, asset), 8, 29, "b".repeat(64))
    }
}
