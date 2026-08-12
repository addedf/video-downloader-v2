package com.zemin.downloader.update

import java.net.URI

object AppUpdateConfig {
    const val MANIFEST_URL = "https://updates.menkange.com/android/update.json"
    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 15_000
    const val MAX_REDIRECTS = 5
    const val MAX_MANIFEST_BYTES = 128 * 1024
    const val MAX_APK_BYTES = 250L * 1024L * 1024L
    const val USER_AGENT = "DouYinDownloader-Android-Update/1"

    private const val UPDATE_HOST = "updates.menkange.com"
    private const val GITHUB_HOST = "github.com"
    private const val GITHUB_RELEASE_PREFIX = "/addedf/video-downloader/releases/download/"

    fun isAllowedManifestUrl(value: String): Boolean {
        val uri = parseSecureUri(value) ?: return false
        return uri.host.equals(UPDATE_HOST, ignoreCase = true) &&
            uri.path == "/android/update.json" &&
            uri.query == null
    }

    fun isAllowedApkUrl(value: String): Boolean {
        val uri = parseSecureUri(value) ?: return false
        val host = uri.host.lowercase()
        return when (host) {
            UPDATE_HOST -> uri.query == null &&
                uri.path.startsWith("/android/") && uri.path.endsWith(".apk")
            GITHUB_HOST -> uri.query == null &&
                uri.path.startsWith(GITHUB_RELEASE_PREFIX) && uri.path.endsWith(".apk")
            else -> false
        }
    }

    fun isAllowedApkRedirectUrl(value: String): Boolean {
        if (isAllowedApkUrl(value)) return true
        val uri = parseSecureUri(value) ?: return false
        return uri.host.lowercase() in setOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )
    }

    private fun parseSecureUri(value: String): URI? = runCatching {
        URI(value).takeIf { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.port in setOf(-1, 443)
        }
    }.getOrNull()
}
