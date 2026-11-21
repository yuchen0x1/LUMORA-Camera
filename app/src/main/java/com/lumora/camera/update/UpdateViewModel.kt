package com.lumora.camera.update

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Notice(val text: String, val retryable: Boolean = false) : UpdateState
    data class Available(val offer: UpdateOffer) : UpdateState
    data class Downloading(val offer: UpdateOffer, val received: Long = 0, val bytesPerSecond: Long = 0) : UpdateState
    data class Ready(val offer: UpdateOffer) : UpdateState
    data class Installing(
        val offer: UpdateOffer,
        val sessionId: String,
        val phase: InstallPhase
    ) : UpdateState
}

internal enum class InstallPhase { VERIFYING, LAUNCHING, WAITING_FOR_RESULT }

internal data class UpdateInstallLaunch(
    val intent: Intent,
    val sessionId: String
)

internal class UpdateViewModel(
    application: Application,
    private val updater: UpdateClient,
    private val prefs: SharedPreferences,
    private val clock: () -> Long
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AndroidUpdater(application),
        application.getSharedPreferences("lumora_updates", 0), System::currentTimeMillis)

    val installedVersion get() = updater.versionName
    var frequency by mutableStateOf(UpdateFrequency.entries.firstOrNull {
        it.name == prefs.getString("frequency", null)
    } ?: UpdateFrequency.MANUAL)
        private set
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var job: Job? = null
    private data class InstallAttempt(
        val offer: UpdateOffer,
        val sessionId: String,
        var prepared: UpdateInstall? = null,
        var launched: Boolean = false,
        var dismissed: Boolean = false,
        var filesCleared: Boolean = false
    )
    private var installAttempt: InstallAttempt? = null

    fun changeFrequency(label: String) {
        frequency = UpdateFrequency.entries.first { it.label == label }
        prefs.edit().putString("frequency", frequency.name).apply()
        if (frequency != UpdateFrequency.MANUAL) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (installAttempt != null || job?.isActive == true || state is UpdateState.Available ||
            state is UpdateState.Ready || (!manual && state is UpdateState.Notice)) return
        val now = clock()
        if (!manual && !UpdateCheckPolicy.isDue(frequency, now,
                prefs.getLong("lastSuccessfulCheck", 0), prefs.getLong("lastFailedCheck", 0))) return
        if (manual) state = UpdateState.Checking
        job = viewModelScope.launch {
            try {
                val offer = updater.check()
                val completed = clock()
                // Older lastChecked values include failed attempts and cannot prove success.
                prefs.edit().putLong("lastSuccessfulCheck", completed).remove("lastFailedCheck").apply()
                val deferred = offer?.release?.name == prefs.getString("laterVersion", null) &&
                    completed < prefs.getLong("laterUntil", 0)
                state = when {
                    offer != null && (manual || !deferred) -> UpdateState.Available(offer)
                    manual -> UpdateState.Notice("没有可用的正式更新")
                    else -> UpdateState.Idle
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                prefs.edit().putLong("lastFailedCheck", clock()).apply()
                state = if (manual) UpdateState.Notice(message(error, "检查更新失败，请确认网络后重试"), retryable = true)
                else UpdateState.Idle
            }
        }
    }

    fun incompatibility(offer: UpdateOffer) = updater.incompatibility(offer)

    fun later(offer: UpdateOffer) {
        prefs.edit().putString("laterVersion", offer.release.name)
            .putLong("laterUntil", clock() + TimeUnit.DAYS.toMillis(7)).apply()
        val attempt = installAttempt
        if (attempt != null) {
            if (attempt.offer == offer) {
                attempt.dismissed = true
                if (!attempt.launched) job?.cancel()
                // clearDownloaded retires the file, but an already-launched lease keeps its bytes readable.
                clearInstallFiles(attempt)
                state = UpdateState.Idle
            }
            // Once launch is being submitted, only its matching activity result can release the lease.
            return
        }
        if (state is UpdateState.Ready) {
            job?.cancel()
            updater.clearDownloaded()
        }
        state = UpdateState.Idle
    }

    fun download(offer: UpdateOffer) {
        if (installAttempt != null || job?.isActive == true || state != UpdateState.Available(offer)) return
        state = UpdateState.Downloading(offer)
        job = viewModelScope.launch {
            try {
                val started = System.nanoTime()
                var last = started
                updater.download(offer) { received ->
                    val now = System.nanoTime()
                    if (now - last >= 250_000_000L) {
                        last = now
                        val elapsed = max(1L, TimeUnit.NANOSECONDS.toMillis(now - started))
                        state = UpdateState.Downloading(offer, received, received * 1000 / elapsed)
                    }
                }
                state = UpdateState.Ready(offer)
            } catch (error: CancellationException) {
                state = UpdateState.Available(offer)
                throw error
            } catch (error: Exception) {
                state = UpdateState.Notice(message(error, "下载中断或写入失败，请重新检查并下载"), retryable = true)
            }
        }
    }

    fun cancelDownload() {
        if (state is UpdateState.Downloading) job?.cancel()
    }

    fun install(offer: UpdateOffer, launch: (UpdateInstallLaunch) -> Unit) {
        if (installAttempt != null || job?.isActive == true || state != UpdateState.Ready(offer)) return
        val attempt = InstallAttempt(offer, UUID.randomUUID().toString())
        installAttempt = attempt
        state = UpdateState.Installing(offer, attempt.sessionId, InstallPhase.VERIFYING)
        job = viewModelScope.launch {
            var prepared: UpdateInstall? = null
            try {
                val install = updater.installIntent(offer)
                prepared = install
                attempt.prepared = install
                currentCoroutineContext().ensureActive()
                if (installAttempt !== attempt || attempt.dismissed) return@launch
                state = UpdateState.Installing(offer, attempt.sessionId, InstallPhase.LAUNCHING)
                attempt.launched = true
                launch(UpdateInstallLaunch(install.intent, attempt.sessionId))
                if (installAttempt === attempt) {
                    state = UpdateState.Installing(offer, attempt.sessionId, InstallPhase.WAITING_FOR_RESULT)
                } else {
                    // A synchronous callback already consumed this exact session.
                    prepared = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (installAttempt === attempt) {
                    installAttempt = null
                    clearInstallFiles(attempt)
                    attempt.prepared?.let(updater::releaseInstall)
                    attempt.prepared = null
                    prepared = null
                    state = UpdateState.Notice(message(error, "无法启动系统安装，请重新下载"), retryable = true)
                }
            } finally {
                if (installAttempt === attempt && !attempt.launched) {
                    installAttempt = null
                    (attempt.prepared ?: prepared)?.let(updater::releaseInstall)
                    attempt.prepared = null
                    prepared = null
                    if (!attempt.dismissed && state == UpdateState.Installing(offer, attempt.sessionId,
                            InstallPhase.VERIFYING)) state = UpdateState.Ready(offer)
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun installationReturned(sessionId: String, result: ActivityResult) {
        val attempt = installAttempt ?: return
        if (!attempt.launched || attempt.sessionId != sessionId) return
        installAttempt = null
        try {
            clearInstallFiles(attempt)
        } finally {
            attempt.prepared?.let(updater::releaseInstall)
            attempt.prepared = null
        }
        val installing = state as? UpdateState.Installing
        if (!attempt.dismissed && installing?.sessionId == sessionId) {
            state = UpdateState.Notice("系统安装已结束，当前仍可使用；如需重试请再次检查更新", retryable = true)
        }
    }

    fun clearNotice() {
        if (state is UpdateState.Notice) state = UpdateState.Idle
    }

    private fun clearInstallFiles(attempt: InstallAttempt) {
        if (attempt.filesCleared) return
        attempt.filesCleared = true
        updater.clearDownloaded()
    }

    private fun message(error: Exception, fallback: String) =
        (error as? UpdateFailure)?.message ?: fallback
}
