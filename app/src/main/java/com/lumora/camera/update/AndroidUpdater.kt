package com.lumora.camera.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal interface UpdateClient {
    val versionName: String
    suspend fun check(): UpdateOffer?
    fun incompatibility(offer: UpdateOffer): String?
    suspend fun download(offer: UpdateOffer, progress: (Long) -> Unit)
    suspend fun installIntent(offer: UpdateOffer): Intent
    fun clearDownloaded()
}

internal class AndroidUpdater(private val context: Context) : UpdateClient {
    private val packages = context.packageManager
    private val packageName = context.packageName
    private val installed by lazy { installedPackage() }
    override val versionName: String get() = installed.versionName.orEmpty()
    private val updates get() = File(context.cacheDir, "verified-update")
    private val apk get() = File(updates, "lumora.apk")
    private val partial get() = File(context.cacheDir, "lumora-update.part.apk")

    override suspend fun check(): UpdateOffer? = withContext(Dispatchers.IO) {
        val response = receive(URI(RELEASE_API), "application/vnd.github+json", redirects = false) { stream ->
            readJson(stream, 256 * 1024)
        } ?: return@withContext null
        val release = parseRelease(response, versionName) ?: return@withContext null
        val metadata = ByteArrayOutputStream()
        transfer(release.metadata, metadata)
        parseOffer(JSONObject(metadata.toString(Charsets.UTF_8.name())), release, packageName, installed.longVersionCode)
    }

    override fun incompatibility(offer: UpdateOffer): String? = when {
        offer.minSdk > Build.VERSION.SDK_INT -> "此版本需要 Android API ${offer.minSdk} 或以上版本"
        certificate(installed) != offer.certificate -> "当前安装包与正式版的签名不同，无法覆盖安装"
        else -> null
    }

    override suspend fun download(offer: UpdateOffer, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        incompatibility(offer)?.let { throw UpdateFailure(it) }
        if (!updates.isDirectory && !updates.mkdirs()) throw UpdateFailure("无法创建下载目录")
        if (apk.exists() && !apk.delete()) throw UpdateFailure("无法清理上次的更新文件")
        if (updates.usableSpace < offer.release.apk.size + 16L * 1024 * 1024) {
            throw UpdateFailure("可用空间不足，清理空间后请重试")
        }
        // A partial download stays outside the FileProvider path.
        try {
            partial.outputStream().buffered().use { transfer(offer.release.apk, it, progress) }
            verifyArchive(partial, offer)
            if (!partial.renameTo(apk)) throw UpdateFailure("无法保存已校验的安装包")
        } finally {
            partial.delete()
        }
    }

    override suspend fun installIntent(offer: UpdateOffer): Intent = withContext(Dispatchers.IO) {
        verifyArchive(apk, offer)
        val uri = FileProvider.getUriForFile(context, "$packageName.updates", apk)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }

    override fun clearDownloaded() {
        apk.delete()
        partial.delete()
    }

    internal fun verifyArchive(file: File, offer: UpdateOffer) {
        if (!file.isFile || file.length() != offer.release.apk.size) throw UpdateFailure("安装包不完整，请重新下载")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            var count = input.read(bytes)
            while (count >= 0) {
                hash.update(bytes, 0, count)
                count = input.read(bytes)
            }
        }
        if (hash.digest().hex() != offer.release.apk.sha256) throw UpdateFailure("安装包校验失败")
        val candidate = archivePackage(file) ?: throw UpdateFailure("Android 无法识别安装包")
        if (candidate.packageName != packageName || candidate.versionName != offer.release.name ||
            candidate.longVersionCode != offer.versionCode || candidate.longVersionCode <= installed.longVersionCode ||
            candidate.applicationInfo?.minSdkVersion != offer.minSdk ||
            certificate(candidate) != offer.certificate || certificate(installed) != offer.certificate
        ) throw UpdateFailure("安装包的版本、兼容性或签名不匹配")
    }

    private suspend fun transfer(asset: ReleaseAsset, output: OutputStream, progress: (Long) -> Unit = {}) {
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        receive(asset.uri, "application/octet-stream", redirects = true) { stream ->
            val bytes = ByteArray(64 * 1024)
            var count = stream.read(bytes)
            while (count >= 0) {
                currentCoroutineContext().ensureActive()
                total += count
                if (total > asset.size) throw UpdateFailure("下载文件超过声明大小")
                hash.update(bytes, 0, count)
                output.write(bytes, 0, count)
                progress(total)
                count = stream.read(bytes)
            }
        }
        if (total != asset.size || hash.digest().hex() != asset.sha256) {
            throw UpdateFailure("下载文件的大小或 SHA-256 不一致")
        }
    }

    private suspend fun <T> receive(
        source: URI, accept: String, redirects: Boolean, read: suspend (InputStream) -> T
    ): T? {
        var url = source
        repeat(4) {
            currentCoroutineContext().ensureActive()
            val connection = url.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "LUMORA-Camera")
                connection.setRequestProperty("Accept", accept)
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use { read(it) }
                    404 -> if (!redirects) return null else throw UpdateFailure("下载地址已失效")
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw UpdateFailure("下载重定向无效")
                        url = url.resolve(location)
                        if (!redirects || !validDownloadRedirect(url)) throw UpdateFailure("下载来源校验失败")
                    }
                    403, 429 -> throw UpdateFailure("GitHub 暂时拒绝请求，请稍后重试")
                    else -> throw UpdateFailure("GitHub 请求失败（HTTP $status）")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateFailure("下载重定向次数过多")
    }

    private fun readJson(input: InputStream, limit: Int): JSONObject {
        val data = ByteArrayOutputStream()
        val bytes = ByteArray(8_192)
        var count = input.read(bytes)
        while (count >= 0) {
            if (data.size() + count > limit) throw UpdateFailure("发布数据超出大小限制")
            data.write(bytes, 0, count)
            count = input.read(bytes)
        }
        return JSONObject(data.toString(Charsets.UTF_8.name()))
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo =
        packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    @Suppress("DEPRECATION")
    private fun archivePackage(file: File): PackageInfo? =
        packages.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun certificate(info: PackageInfo): String? = info.signingInfo?.apkContentsSigners
        ?.singleOrNull()?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
