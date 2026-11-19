package com.lumora.camera.update

import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant

private const val REPOSITORY = "yuchen0x1/LUMORA-Camera"
internal const val RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
private const val RELEASE_ROOT = "https://github.com/$REPOSITORY/releases"
private const val UPDATE_METADATA = "android-update.json"
internal const val MAX_APK_BYTES = 512L * 1024 * 1024
private val sha256Pattern = Regex("[a-f0-9]{64}")
private val versionPattern = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

internal class UpdateFailure(message: String) : IOException(message)

internal data class ReleaseAsset(val uri: URI, val size: Long, val sha256: String)

internal data class StableRelease(
    val name: String,
    val notes: String,
    val published: Instant,
    val details: String,
    val metadata: ReleaseAsset,
    val apk: ReleaseAsset
)

internal data class UpdateOffer(
    val release: StableRelease,
    val versionCode: Long,
    val minSdk: Int,
    val certificate: String
)

internal fun newerVersion(candidate: String, installed: String): Boolean {
    fun parts(version: String) = versionPattern.matchEntire(version)?.groupValues?.drop(1)
        ?.map(String::toInt) ?: throw UpdateFailure("版本号格式无效")
    return parts(candidate).zip(parts(installed)).firstOrNull { (a, b) -> a != b }
        ?.let { (a, b) -> a > b } ?: false
}

internal fun validDownloadRedirect(uri: URI): Boolean = uri.scheme == "https" &&
    uri.host == "release-assets.githubusercontent.com" && uri.port == -1 &&
    uri.userInfo == null && uri.fragment == null

internal fun parseRelease(json: JSONObject, currentVersion: String): StableRelease? {
    if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
    val tag = json.getString("tag_name")
    val name = tag.removePrefix("v")
    if (tag != "v$name" || !versionPattern.matches(name)) throw UpdateFailure("发布标签无效")
    if (!newerVersion(name, currentVersion)) return null
    val assets = json.getJSONArray("assets")
    fun findAsset(filename: String, maximum: Long): ReleaseAsset {
        val matches = (0 until assets.length()).map(assets::getJSONObject)
            .filter { it.getString("name") == filename && it.getString("state") == "uploaded" }
        val asset = matches.singleOrNull() ?: throw UpdateFailure("发布资产缺失或重复")
        val expected = "$RELEASE_ROOT/download/$tag/$filename"
        val digest = asset.getString("digest").removePrefix("sha256:")
        val size = asset.get("size") as? Number ?: throw UpdateFailure("发布资产大小无效")
        if (asset.getString("browser_download_url") != expected ||
            asset.getString("digest") != "sha256:$digest" || !sha256Pattern.matches(digest) ||
            size.toLong() !in 1..maximum || size.toDouble() != size.toLong().toDouble()
        ) throw UpdateFailure("发布资产来源或完整性信息无效")
        return ReleaseAsset(URI(expected), size.toLong(), digest)
    }
    val notes = if (json.isNull("body")) "" else json.getString("body").take(4_000)
    return StableRelease(
        name, notes, Instant.parse(json.getString("published_at")), "$RELEASE_ROOT/tag/$tag",
        findAsset(UPDATE_METADATA, 8_192), findAsset(apkName(name), MAX_APK_BYTES)
    )
}

internal fun parseOffer(
    manifest: JSONObject, release: StableRelease, packageName: String, installedCode: Long
): UpdateOffer {
    fun integer(key: String): Long = when (val number = manifest.get(key)) {
        is Int -> number.toLong()
        is Long -> number
        else -> throw UpdateFailure("更新元数据中的整数无效")
    }
    val code = integer("versionCode")
    val minimum = integer("minSdk")
    val certificate = manifest.getString("signerSha256")
    if (integer("schema") != 1L || manifest.getString("platform") != "android" ||
        manifest.getString("architecture") != "universal" || manifest.getString("channel") != "stable" ||
        manifest.getString("packageName") != packageName ||
        manifest.getString("versionName") != release.name ||
        manifest.getString("asset") != apkName(release.name) ||
        manifest.getString("sha256") != release.apk.sha256 || integer("size") != release.apk.size ||
        code <= installedCode || code > 2_100_000_000L || minimum !in 29..Int.MAX_VALUE.toLong() ||
        !sha256Pattern.matches(certificate)
    ) throw UpdateFailure("更新元数据与发布版本不一致")
    return UpdateOffer(release, code, minimum.toInt(), certificate)
}

private fun apkName(name: String) = "LUMORA-Camera-$name-Android-universal.apk"
