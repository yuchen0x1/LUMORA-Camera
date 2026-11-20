package com.lumora.camera.update

import java.net.URI
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateProtocolTest {
    private val installedName = "2.1.3"
    private val offeredName = "2.1.4"
    private val installedCode = 7L
    private val offeredCode = 8L
    private val packageName = "com.lumora.camera"
    private val apkName = "LUMORA-Camera-$offeredName-Android-universal.apk"
    private val apkSize = 1_024L
    private val apkDigest = "a".repeat(64)
    private val metadataDigest = "b".repeat(64)
    private val signerDigest = "c".repeat(64)
    private val releaseRoot = "https://github.com/" +
        RELEASE_API.removePrefix("https://api.github.com/repos/").removeSuffix("/latest")

    @Test fun versionComparisonIsNumericAndNeverOffersEqualOrOlderVersions() {
        assertTrue(newerVersion("2.1.10", "2.1.9"))
        assertTrue(newerVersion("2.10.0", "2.9.99"))
        assertTrue(newerVersion("3.0.0", "2.99.99"))
        assertFalse(newerVersion(installedName, installedName))
        assertFalse(newerVersion("2.1.2", installedName))
        assertFalse(newerVersion("1.99.99", installedName))
    }

    @Test fun versionComparisonRejectsNonCanonicalOrUnsupportedVersions() {
        for (invalid in listOf("v2.1.4", "2.1", "2.1.4-beta", "02.1.4", "-1.0.0", "1000000000.0.0")) {
            rejects("candidate $invalid") { newerVersion(invalid, installedName) }
            rejects("installed $invalid") { newerVersion(offeredName, invalid) }
        }
    }

    @Test fun stableReleaseRetainsVerifiedAssetIdentityAndBoundsNotes() {
        val json = releaseJson().put("body", "n".repeat(4_001))
        val release = requireNotNull(parseRelease(json, installedName))
        assertEquals(offeredName, release.name)
        assertEquals(4_000, release.notes.length)
        assertEquals(Instant.parse("2026-11-21T00:00:00Z"), release.published)
        assertEquals("$releaseRoot/tag/v$offeredName", release.details)
        assertEquals(URI("$releaseRoot/download/v$offeredName/$apkName"), release.apk.uri)
        assertEquals(apkSize, release.apk.size)
        assertEquals(apkDigest, release.apk.sha256)
        assertEquals(metadataDigest, release.metadata.sha256)
        assertEquals("", requireNotNull(parseRelease(releaseJson().put("body", JSONObject.NULL), installedName)).notes)
    }

    @Test fun releaseSelectionSkipsDraftPrereleaseEqualAndOlderVersions() {
        assertNull(parseRelease(releaseJson().put("draft", true), installedName))
        assertNull(parseRelease(releaseJson().put("prerelease", true), installedName))
        assertNull(parseRelease(releaseJson(), offeredName))
        assertNull(parseRelease(releaseJson(), "2.1.5"))
        for (tag in listOf(offeredName, "v02.1.4", "v2.1.4-beta")) {
            rejects("tag $tag") { parseRelease(releaseJson().put("tag_name", tag), installedName) }
        }
    }

    @Test fun releasesRejectForeignOrAlteredDownloadUrlsForEitherAsset() {
        for (index in 0..1) {
            for (url in listOf(
                "https://example.com/download.apk",
                "http://github.com/yuchen0x1/LUMORA-Camera/releases/download/v$offeredName/$apkName",
                "$releaseRoot/download/v$offeredName/$apkName?download=1",
                "$releaseRoot/download/v2.1.2/$apkName"
            )) {
                val json = releaseJson()
                json.getJSONArray("assets").getJSONObject(index).put("browser_download_url", url)
                rejects("asset $index URL $url") { parseRelease(json, installedName) }
            }
        }
    }

    @Test fun releasesRequireExactlyOneUploadedCopyOfEachRequiredAsset() {
        for (index in 0..1) {
            val missing = releaseJson()
            missing.put("assets", JSONArray().put(missing.getJSONArray("assets").getJSONObject(1 - index)))
            rejects("missing asset $index") { parseRelease(missing, installedName) }

            val duplicate = releaseJson()
            val assets = duplicate.getJSONArray("assets")
            assets.put(JSONObject(assets.getJSONObject(index).toString()))
            rejects("duplicate asset $index") { parseRelease(duplicate, installedName) }

            val unuploaded = releaseJson()
            unuploaded.getJSONArray("assets").getJSONObject(index).put("state", "starter")
            rejects("unuploaded asset $index") { parseRelease(unuploaded, installedName) }
        }
    }

    @Test fun releasesRejectMalformedDigestsAndInvalidAssetSizes() {
        for (index in 0..1) {
            for (digest in listOf(apkDigest, "sha256:${"A".repeat(64)}", "sha256:${"g".repeat(64)}", "sha256:abcd")) {
                val json = releaseJson()
                json.getJSONArray("assets").getJSONObject(index).put("digest", digest)
                rejects("asset $index digest $digest") { parseRelease(json, installedName) }
            }
            val maximum = if (index == 0) 8_192L else MAX_APK_BYTES
            for (size in listOf<Any>(0L, -1L, maximum + 1, 1.5, "1024")) {
                val json = releaseJson()
                json.getJSONArray("assets").getJSONObject(index).put("size", size)
                rejects("asset $index size $size") { parseRelease(json, installedName) }
            }
        }
    }

    @Test fun matchingMetadataProducesAnOfferWithAnIncreasingCode() {
        val release = stableRelease()
        val offer = parseOffer(metadata(), release, packageName, installedCode)
        assertEquals(release, offer.release)
        assertEquals(offeredCode, offer.versionCode)
        assertEquals(29, offer.minSdk)
        assertEquals(signerDigest, offer.certificate)
    }

    @Test fun metadataMustMatchThePackageReleaseAssetAndStableProtocol() {
        val release = stableRelease()
        val mismatches = listOf<Pair<String, Any>>(
            "schema" to 2,
            "platform" to "ios",
            "architecture" to "arm64",
            "channel" to "beta",
            "packageName" to "com.example.other",
            "versionName" to installedName,
            "asset" to "other.apk",
            "sha256" to metadataDigest,
            "size" to apkSize + 1
        )
        for ((key, value) in mismatches) {
            rejects("metadata $key=$value") {
                parseOffer(metadata().put(key, value), release, packageName, installedCode)
            }
        }
    }

    @Test fun metadataRequiresBoundedIncreasingCodesSdkAndActualIntegerTypes() {
        val release = stableRelease()
        for (code in listOf(0L, installedCode - 1, installedCode, 2_100_000_001L)) {
            rejects("versionCode $code") {
                parseOffer(metadata().put("versionCode", code), release, packageName, installedCode)
            }
        }
        for (minimum in listOf(28L, Int.MAX_VALUE.toLong() + 1)) {
            rejects("minSdk $minimum") {
                parseOffer(metadata().put("minSdk", minimum), release, packageName, installedCode)
            }
        }
        for (key in listOf("schema", "versionCode", "minSdk", "size")) {
            val number = metadata().getLong(key)
            for (value in listOf<Any>(number.toDouble(), number.toString())) {
                rejects("noninteger $key=$value") {
                    parseOffer(metadata().put(key, value), release, packageName, installedCode)
                }
            }
        }
    }

    @Test fun metadataRequiresACanonicalSha256SignerFingerprint() {
        val release = stableRelease()
        for (certificate in listOf("", "c".repeat(63), "C".repeat(64), "g".repeat(64), "sha256:$signerDigest")) {
            rejects("certificate $certificate") {
                parseOffer(metadata().put("signerSha256", certificate), release, packageName, installedCode)
            }
        }
        // Matching this fingerprint to the installed APK is AndroidUpdater's separate responsibility.
    }

    @Test fun redirectsAllowOnlyHttpsOnTheExactAssetHostWithoutCredentialsPortOrFragment() {
        val valid = "https://release-assets.githubusercontent.com/path/app.apk?signature=example"
        assertTrue(validDownloadRedirect(URI(valid)))
        for (invalid in listOf(
            "http://release-assets.githubusercontent.com/path/app.apk",
            "https://example.com/path/app.apk",
            "https://release-assets.githubusercontent.com.example.com/path/app.apk",
            "https://github.com/path/app.apk",
            "https://user:password@release-assets.githubusercontent.com/path/app.apk",
            "https://release-assets.githubusercontent.com:443/path/app.apk",
            "https://release-assets.githubusercontent.com:8443/path/app.apk",
            "$valid#fragment"
        )) {
            assertFalse(invalid, validDownloadRedirect(URI(invalid)))
        }
    }

    private fun stableRelease() = requireNotNull(parseRelease(releaseJson(), installedName))

    private fun releaseJson() = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v$offeredName")
        .put("body", "Update reliability fixes")
        .put("published_at", "2026-11-21T00:00:00Z")
        .put("assets", JSONArray()
            .put(asset("android-update.json", 439L, metadataDigest))
            .put(asset(apkName, apkSize, apkDigest)))

    private fun asset(name: String, size: Long, digest: String) = JSONObject()
        .put("name", name)
        .put("state", "uploaded")
        .put("browser_download_url", "$releaseRoot/download/v$offeredName/$name")
        .put("digest", "sha256:$digest")
        .put("size", size)

    private fun metadata() = JSONObject()
        .put("schema", 1)
        .put("platform", "android")
        .put("architecture", "universal")
        .put("channel", "stable")
        .put("packageName", packageName)
        .put("versionName", offeredName)
        .put("versionCode", offeredCode)
        .put("minSdk", 29)
        .put("asset", apkName)
        .put("sha256", apkDigest)
        .put("size", apkSize)
        .put("signerSha256", signerDigest)

    private fun rejects(label: String, action: () -> Unit) {
        try {
            action()
            fail("Expected UpdateFailure for $label")
        } catch (_: UpdateFailure) {
            // Fail closed with the protocol's intentional validation error, not an unrelated exception.
        }
    }
}
