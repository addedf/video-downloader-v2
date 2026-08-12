package com.zemin.downloader.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object SecureUpdateHttpClient {
    fun openManifest(): HttpURLConnection = openFollowingRedirects(
        initialUrl = AppUpdateConfig.MANIFEST_URL,
        isAllowedUrl = AppUpdateConfig::isAllowedManifestUrl,
    )

    fun openApk(apkUrl: String): HttpURLConnection = openFollowingRedirects(
        initialUrl = apkUrl,
        isAllowedUrl = AppUpdateConfig::isAllowedApkRedirectUrl,
    )

    fun readManifest(connection: HttpURLConnection): String {
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > AppUpdateConfig.MAX_MANIFEST_BYTES) {
                    throw IOException("Update manifest is too large")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun openFollowingRedirects(
        initialUrl: String,
        isAllowedUrl: (String) -> Boolean,
    ): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(AppUpdateConfig.MAX_REDIRECTS + 1) { redirectCount ->
            if (!isAllowedUrl(current.toExternalForm())) throw IOException("Update URL is not allowed")
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = AppUpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = AppUpdateConfig.READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
                setRequestProperty("User-Agent", AppUpdateConfig.USER_AGENT)
            }
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) return connection
            if (responseCode !in REDIRECT_CODES || redirectCount == AppUpdateConfig.MAX_REDIRECTS) {
                connection.errorStream?.close()
                connection.disconnect()
                throw IOException("Unexpected update server response: $responseCode")
            }
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    throw IOException("Update redirect has no location")
                }
            val next = URL(current, location)
            connection.inputStream.runCatching { close() }
            connection.disconnect()
            current = next
        }
        throw IOException("Too many update redirects")
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
