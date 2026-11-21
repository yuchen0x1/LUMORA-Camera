package com.lumora.camera.update

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Run only on an unlocked, dedicated emulator. The test never changes the unknown-sources app-op. */
@RunWith(AndroidJUnit4::class)
class AndroidInstallerFlowTest {
    @Test
    fun backFromSystemInstallerReleasesRetiredLease() {
        assumeTrue("Pass -e realInstaller true to open and cancel the system installer",
            InstrumentationRegistry.getArguments().getString("realInstaller") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val info = app.applicationInfo
        check(info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && info.splitSourceDirs.isNullOrEmpty()) {
            "This test requires the target's installed standalone debug APK"
        }
        check(app.packageManager.canRequestPackageInstalls()) {
            "Allow REQUEST_INSTALL_PACKAGES for ${app.packageName} on the dedicated emulator before running"
        }
        val installed = installedPackage(app)
        val source = File(info.sourceDir)
        check(source.isFile && source.length() > 0) { "No readable installed debug APK: $source" }
        val sourceDigest = source.inputStream().use { it.sha256() }
        // No network calls. The already-installed version intentionally cannot pass verifyArchive's newer-version gate.
        val url = URI("https://example.invalid/installer-fixture.apk")
        val asset = ReleaseAsset(url, source.length(), sourceDigest.joinToString("") { "%02x".format(it) })
        val offer = UpdateOffer(StableRelease(installed.versionName.orEmpty(), "", Instant.EPOCH,
            url.toString(), asset, asset), installed.longVersionCode, info.minSdkVersion, "0".repeat(64))
        val client = LocalApkClient(app, source, offer)
        val namespace = "real-installer-${UUID.randomUUID()}"
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val originalFlags = automation.serviceInfo.flags
        val viewIdFlags = originalFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        try {
            if (viewIdFlags != originalFlags) automation.serviceInfo = automation.serviceInfo.apply { flags = viewIdFlags }
            val prefs = app.getSharedPreferences(namespace, 0)
            check(prefs.edit().putString("frequency", UpdateFrequency.DAILY.name).commit())
            // Existing debug ui-test-manifest host; no MainActivity/camera or app-level Install click.
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var host: ComponentActivity
                scenario.onActivity { host = it }
                runBlocking { exerciseInstaller(app, host, prefs, client, sourceDigest, automation) }
            }
            assertEquals("The target APK must not have been reinstalled", installed.lastUpdateTime,
                installedPackage(app).lastUpdateTime)
        } finally {
            try {
                client.cleanup()
            } finally {
                app.deleteSharedPreferences(namespace)
                if (viewIdFlags != originalFlags) automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }

    private suspend fun exerciseInstaller(
        app: Application,
        host: ComponentActivity,
        prefs: SharedPreferences,
        client: LocalApkClient,
        sourceDigest: ByteArray,
        automation: UiAutomation
    ) {
        val model = onMain {
            UpdateViewModel(app, client, prefs, System::currentTimeMillis).also {
                host.viewModelStore.put("real-installer", it)
                it.check(manual = true)
                assertEquals(UpdateState.Available(client.offer), it.state)
                it.download(client.offer)
            }
        }
        await({ "Copying the installed debug APK did not reach Ready" }) {
            onMain {
                val state = model.state
                check(state !is UpdateState.Notice) { "APK fixture failed: $state" }
                state == UpdateState.Ready(client.offer)
            }
        }
        val returned = CompletableDeferred<InstallerReturn>()
        var launcher: ActivityResultLauncher<Intent>? = null
        var submittedPackage: String? = null
        try {
            val state = onMain {
                model.install(client.offer) { launch ->
                    val installer = checkNotNull(launch.intent.`package`)
                    // Registration without a LifecycleOwner is valid after STARTED. The UUID keys
                    // this actual ComponentActivity result; no test dispatches a synthetic result.
                    val registered = host.activityResultRegistry.register(
                        launch.sessionId, ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        model.installationReturned(launch.sessionId, result)
                        returned.complete(InstallerReturn(launch.sessionId, result))
                    }
                    launcher = registered
                    submittedPackage = installer // Cleanup also covers a partially submitted launch that throws.
                    registered.launch(launch.intent)
                }
                model.state
            }
            val waiting = state as? UpdateState.Installing ?: throw AssertionError("Installer launch failed: $state")
            assertEquals(InstallPhase.WAITING_FOR_RESULT, waiting.phase)
            assertEquals(waiting.sessionId, UUID.fromString(waiting.sessionId).toString())
            val installer = checkNotNull(submittedPackage)
            val prepared = checkNotNull(client.prepared)
            val leasedApk = client.files.leasedFile(prepared.lease)
            val uri = checkNotNull(prepared.intent.data)

            var foreground: String? = null
            await({ "No visible confirmation from system installer $installer (last active package: $foreground)" }) {
                if (returned.isCompleted) throw AssertionError("Installer returned before Back: ${returned.await()}")
                automation.readActiveWindow { root ->
                    foreground = root.packageName?.toString()
                    foreground == installer && root.hasConfirmationButtons()
                } == true
            }
            assertTrue("Installer must not be the app's own UI", installer != app.packageName)

            onMain {
                // Exercise MainActivity's ON_RESUME work without moving the host in front of the installer.
                prefs.edit().remove("lastSuccessfulCheck").apply() // Make the daily check due.
                model.check(manual = false)
                assertEquals(1, client.checks)
                assertEquals(waiting, model.state)
                model.later(client.offer) // Retires this owner's verified file, not its active lease.
                model.check(manual = false)
                assertEquals(1, client.checks)
                assertEquals(UpdateState.Idle, model.state)
            }
            assertTrue("Clear should retire the verified APK", runCatching { client.files.verifiedFile() }
                .exceptionOrNull() is UpdateFailure)
            assertEquals(leasedApk, client.files.leasedFile(prepared.lease))
            assertTrue("Clear removed the active install file", leasedApk.isFile)
            val retainedDigest = checkNotNull(app.contentResolver.openInputStream(uri)).use { it.sha256() }
            assertArrayEquals("FileProvider must still serve every byte of the leased APK", sourceDigest, retainedDigest)

            assertEquals("Installer lost foreground during the retention check", installer, automation.foregroundPackage())
            saveEvidence(app, automation, waiting.sessionId)
            assertFalse("Installer returned without Back", returned.isCompleted)
            assertEquals("Back may only target the verified system installer", installer, automation.foregroundPackage())
            automation.pressBack()

            val canceled = withTimeoutOrNull(WAIT_MS) { returned.await() }
                ?: throw AssertionError("No ActivityResult from $installer after Back")
            assertEquals(waiting.sessionId, canceled.sessionId)
            assertEquals("Back must cancel, never complete an installation", Activity.RESULT_CANCELED, canceled.result.resultCode)
            assertTrue("Matching ActivityResult must release the lease", runCatching { client.files.leasedFile(prepared.lease) }
                .exceptionOrNull() is UpdateFailure)
            assertFalse("Matching ActivityResult must delete the retired APK", leasedApk.exists())
            Log.i(TAG, "$installer returned RESULT_CANCELED for ${canceled.sessionId}; retired APK released")
        } finally {
            withContext(NonCancellable) {
                try {
                    submittedPackage?.let { cancelIfPending(automation, it, returned) }
                } finally {
                    onMain { launcher?.unregister() }
                }
            }
        }
    }

    private suspend fun cancelIfPending(ui: UiAutomation, installer: String, returned: CompletableDeferred<InstallerReturn>) {
        if (returned.isCompleted) return
        // On a failure, try Back only in the same system package, never in an unexpected window.
        runCatching {
            withTimeoutOrNull(5_000) {
                while (!returned.isCompleted && ui.foregroundPackage() != installer) delay(POLL_MS)
                if (!returned.isCompleted) ui.pressBack()
                returned.await()
            }
        }.onFailure { Log.w(TAG, "Could not dismiss $installer during cleanup", it) }
        if (!returned.isCompleted) Log.w(TAG, "No installer result during cleanup for $installer")
    }

    private fun saveEvidence(app: Application, ui: UiAutomation, sessionId: String) {
        val directory = File(checkNotNull(app.getExternalFilesDir(null)), "uiux")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        val screenshot = File(directory, "real-installer-$sessionId.png")
        val bitmap = checkNotNull(ui.takeScreenshot()) { "UiAutomation could not capture the installer" }
        try {
            check(screenshot.createNewFile()) { "Evidence already exists: $screenshot" }
            try {
                screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } catch (failure: Throwable) {
                screenshot.delete()
                throw failure
            }
            Log.i(TAG, "System installer screenshot: ${screenshot.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private class LocalApkClient(
        private val app: Application,
        private val source: File,
        val offer: UpdateOffer
    ) : UpdateClient {
        val files = UpdateCacheFiles(app.cacheDir)
        override val versionName = offer.release.name
        var checks = 0
            private set
        var prepared: UpdateInstall? = null
            private set

        override suspend fun check(): UpdateOffer { checks++; return offer }
        override fun incompatibility(offer: UpdateOffer): String? = null
        override suspend fun download(offer: UpdateOffer, progress: (Long) -> Unit) {
            files.writeVerified(offer.release.apk.size) { partial ->
                val copied = source.inputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                check(copied == offer.release.apk.size) { "The installed APK changed while it was copied" }
            }
        }

        override suspend fun installIntent(offer: UpdateOffer): UpdateInstall {
            val lease = files.acquireInstallLease()
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", files.leasedFile(lease))
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
                // Pin a system APK handler so no resolver or third-party app can take this handoff.
                val installer = app.packageManager.queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
                    .firstOrNull { it.activityInfo.packageName in SYSTEM_INSTALLERS }?.activityInfo?.packageName
                    ?: throw UpdateFailure("No visible AOSP/Google system installer handles APK ACTION_VIEW")
                return UpdateInstall(intent.setPackage(installer), lease).also { prepared = it }
            } catch (failure: Throwable) {
                files.releaseInstallLease(lease)
                throw failure
            }
        }

        override fun clearDownloaded() = files.clear()
        override fun releaseInstall(install: UpdateInstall) { files.releaseInstallLease(install.lease) }

        fun cleanup() {
            try {
                prepared?.let { install ->
                    try {
                        app.revokeUriPermission(checkNotNull(install.intent.data), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } finally {
                        // Fallback only for failures/timeouts. The assertions run before this cleanup.
                        files.releaseInstallLease(install.lease)
                    }
                }
            } finally {
                files.clear() // This store owns only the test's APK; never sweep the shared directory.
            }
        }
    }

    private data class InstallerReturn(val sessionId: String, val result: ActivityResult)

    private suspend fun <T> onMain(action: () -> T): T = withTimeout(WAIT_MS) {
        withContext(Dispatchers.Main) { action() }
    }

    private suspend fun await(description: () -> String, condition: suspend () -> Boolean) {
        val reached = withTimeoutOrNull(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
            true
        }
        if (reached != true) throw AssertionError(description())
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(app: Application) = app.packageManager.getPackageInfo(app.packageName, 0)

    @Suppress("DEPRECATION")
    private fun <T> UiAutomation.readActiveWindow(read: (AccessibilityNodeInfo) -> T): T? {
        val root = rootInActiveWindow ?: return null
        return try { read(root) } finally { root.recycle() }
    }

    private fun UiAutomation.foregroundPackage() = readActiveWindow { it.packageName?.toString() }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.hasConfirmationButtons(): Boolean =
        listOf("android:id/button1", "android:id/button2").all { id ->
            val nodes = findAccessibilityNodeInfosByViewId(id)
            try { nodes.any { it.isVisibleToUser && it.isEnabled } } finally { nodes.forEach { it.recycle() } }
        }

    private fun UiAutomation.pressBack() {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val sentDown = injectInputEvent(down, true)
        val sentUp = injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true)
        check(sentDown && sentUp) { "UiAutomation could not send Back to the installer" }
    }

    private fun InputStream.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = read(buffer)
        while (count != -1) {
            digest.update(buffer, 0, count)
            count = read(buffer)
        }
        return digest.digest()
    }

    private companion object {
        const val TAG = "RealInstallerTest"
        const val WAIT_MS = 20_000L
        const val POLL_MS = 100L
        val SYSTEM_INSTALLERS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
    }
}
