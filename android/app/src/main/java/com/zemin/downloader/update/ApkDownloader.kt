package com.zemin.downloader.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class ApkDownloader(private val context: Context) {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long)

    suspend fun download(
        info: AppUpdateInfo,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        updateDir.listFiles()?.forEach(File::delete)
        val partialFile = File(updateDir, "update-${info.versionCode}.apk.part")
        val apkFile = File(updateDir, "update-${info.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = SecureUpdateHttpClient.openApk(info.apkUrl)
        try {
            val totalBytes = connection.contentLengthLong
            if (totalBytes > AppUpdateConfig.MAX_APK_BYTES) throw IOException("APK is too large")
            var downloadedBytes = 0L
            var lastProgressAt = 0L
            connection.inputStream.buffered().use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        if (downloadedBytes > AppUpdateConfig.MAX_APK_BYTES) {
                            throw IOException("APK is too large")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_NS) {
                            lastProgressAt = now
                            onProgress(Progress(downloadedBytes, totalBytes))
                        }
                    }
                }
            }
            if (downloadedBytes <= 0L) throw IOException("APK download is empty")
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != info.sha256) throw IOException("APK SHA-256 does not match")
            onProgress(Progress(downloadedBytes, downloadedBytes))
            if (!partialFile.renameTo(apkFile)) throw IOException("Cannot finalize APK download")
            apkFile
        } catch (error: Throwable) {
            partialFile.delete()
            apkFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val UPDATE_CACHE_DIR = "app-updates"
        private const val PROGRESS_INTERVAL_NS = 250_000_000L
    }
}
