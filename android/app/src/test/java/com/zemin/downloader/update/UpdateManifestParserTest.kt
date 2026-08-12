package com.zemin.downloader.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {
    @Test
    fun parsesValidManifest() {
        val info = UpdateManifestParser.parse(validManifest())

        assertEquals(5L, info.versionCode)
        assertTrue(info.isNewerThan(4L))
        assertFalse(info.isRequiredFor(4L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUntrustedApkHost() {
        UpdateManifestParser.parse(validManifest().replace(
            "https://github.com/addedf/video-downloader/releases/download/v2.1.0/app.apk",
            "https://example.com/app.apk",
        ))
    }

    @Test
    fun acceptsOwnedUpdateHostApk() {
        val manifest = validManifest().replace(
            "https://github.com/addedf/video-downloader/releases/download/v2.1.0/app.apk",
            "https://updates.menkange.com/android/DouYinDownloader-v2.3.3-arm64-v8a.apk",
        )

        val info = UpdateManifestParser.parse(manifest)

        assertEquals("updates.menkange.com", java.net.URI(info.apkUrl).host)
    }

    @Test
    fun rejectsOwnedUpdateHostApkWithQuery() {
        assertFalse(AppUpdateConfig.isAllowedApkUrl(
            "https://updates.menkange.com/android/app.apk?token=secret"
        ))
    }

    @Test(expected = Exception::class)
    fun rejectsUnknownFields() {
        UpdateManifestParser.parse(validManifest().replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"unexpected\": true,",
        ))
    }

    @Test
    fun acceptsOnlyOwnedManifestEndpoint() {
        assertTrue(AppUpdateConfig.isAllowedManifestUrl(AppUpdateConfig.MANIFEST_URL))
        assertFalse(AppUpdateConfig.isAllowedManifestUrl("http://updates.menkange.com/android/update.json"))
        assertFalse(AppUpdateConfig.isAllowedManifestUrl("https://updates.menkange.com.evil.test/android/update.json"))
    }

    private fun validManifest() = """
        {
          "schemaVersion": 1,
          "versionCode": 5,
          "versionName": "2.1.0",
          "minSupportedVersionCode": 4,
          "apkUrl": "https://github.com/addedf/video-downloader/releases/download/v2.1.0/app.apk",
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "changelog": "修复问题",
          "publishedAt": "2026-07-28T15:00:00Z"
        }
    """.trimIndent()
}
