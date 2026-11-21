package com.lumora.camera.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Opaque, in-memory capability for one verified cache file. */
internal class UpdateInstallLease internal constructor()

/**
 * Owns only files allocated by this client, with fresh paths for every attempt.
 * Previously verified paths stay immutable until explicit clear, even on client reuse.
 * Active installer leases defer deletion after clear until their matching result is received.
 * Never sweep the directory: another client or an installer may still be using it.
 * Files orphaned by process death (including legacy paths) are left to cache eviction.
 */
internal class UpdateCacheFiles(private val cacheDir: File) {
    private class Session(
        val partial: File,
        val verified: File,
        var promoted: Boolean = false,
        var leases: Int = 0,
        var retired: Boolean = false
    )

    private val lock = Any()
    private val directory = File(cacheDir, "verified-update")
    private val owned = mutableSetOf<Session>()
    private val verified = mutableListOf<Session>()
    private val leases = mutableMapOf<UpdateInstallLease, Session>()
    private var generation = 0L

    // The caller must finish all download and archive verification before returning.
    suspend fun writeVerified(expectedBytes: Long, writeAndVerify: suspend (File) -> Unit) {
        val startedGeneration = synchronized(lock) { generation }
        var session: Session? = null
        var succeeded = false
        try {
            withContext(Dispatchers.IO) {
                val attempt = begin(expectedBytes, startedGeneration)
                session = attempt
                writeAndVerify(attempt.partial)
                // Archive verification may be blocking and return after cancellation.
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (attempt !in owned) throw UpdateFailure("更新下载已取消，请重新下载")
                    if (attempt.verified.exists() || !attempt.partial.renameTo(attempt.verified)) {
                        throw UpdateFailure("无法保存已校验的安装包")
                    }
                    attempt.promoted = true
                    verified.add(attempt)
                }
            }
            // Include cancellation while dispatching back from IO in the rollback boundary.
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (session !in owned) throw UpdateFailure("更新下载已取消，请重新下载")
                succeeded = true
            }
        } finally {
            session?.let { attempt ->
                synchronized(lock) {
                    attempt.partial.delete()
                    if (!succeeded) {
                        owned.remove(attempt)
                        verified.remove(attempt)
                        // An installer may acquire the lease after promotion but before
                        // cancellation reaches this rollback boundary.
                        if (attempt.promoted && attempt.leases == 0) attempt.verified.delete()
                    }
                }
            }
        }
    }

    fun verifiedFile(): File = synchronized(lock) {
        verified.lastOrNull()?.verified ?: throw UpdateFailure("安装包不完整，请重新下载")
    }

    fun acquireInstallLease(): UpdateInstallLease = synchronized(lock) {
        val session = verified.lastOrNull()?.takeIf { it.verified.isFile }
            ?: throw UpdateFailure("安装包不完整，请重新下载")
        UpdateInstallLease().also { lease ->
            session.leases++
            leases[lease] = session
        }
    }

    fun leasedFile(lease: UpdateInstallLease): File = synchronized(lock) {
        leases[lease]?.verified ?: throw UpdateFailure("安装会话已结束，请重新下载")
    }

    fun releaseInstallLease(lease: UpdateInstallLease): Boolean = synchronized(lock) {
        val session = leases.remove(lease) ?: return@synchronized false
        session.retired = true
        session.leases--
        owned.remove(session)
        verified.remove(session)
        if (session.leases == 0 && session.promoted) session.verified.delete()
        true
    }

    fun clear() = synchronized(lock) {
        generation++
        val sessions = owned.toList()
        owned.clear()
        verified.clear()
        sessions.forEach { session ->
            session.retired = true
            session.partial.delete()
            if (session.promoted && session.leases == 0) session.verified.delete()
        }
    }

    private fun begin(expectedBytes: Long, startedGeneration: Long): Session = synchronized(lock) {
        if (generation != startedGeneration) throw UpdateFailure("更新下载已取消，请重新下载")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw UpdateFailure("无法创建下载目录")
        }
        if (directory.usableSpace < expectedBytes + 16L * 1024 * 1024) {
            throw UpdateFailure("可用空间不足，清理空间后请重试")
        }
        // Allocate the partial outside the only FileProvider-exposed cache directory.
        val partial = File.createTempFile("lumora-update-", ".part.apk", cacheDir)
        Session(partial, File(directory, partial.name.removeSuffix(".part.apk") + ".apk"))
            .also { owned.add(it) }
    }
}
