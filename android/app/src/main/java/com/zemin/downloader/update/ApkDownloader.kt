package com.zemin.downloader.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class ApkDownloader(private val context: Context) {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long)

    suspend fun download(
        info: AppUpdateInfo,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        val partialFile = File(updateDir, "update-${info.versionCode}.apk.part")
        val apkFile = File(updateDir, "update-${info.versionCode}.apk")
        if (apkFile.isFile) {
            // 上次会话已下载完成但未走完安装流程：摘要一致则直接复用
            if (sha256Of(apkFile) == info.sha256) return@withContext apkFile
            apkFile.delete()
        }
        updateDir.listFiles()?.filterNot { it == partialFile }?.forEach(File::delete)
        var lastError: IOException? = null
        repeat(AppUpdateConfig.MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                coroutineContext.ensureActive()
                delay(AppUpdateConfig.retryDelayMillis(attempt))
            }
            try {
                return@withContext downloadOnce(info, partialFile, apkFile, onProgress)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApkContentException) {
                partialFile.delete()
                if (!error.recoverableByFreshDownload) throw error
                lastError = error
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw (lastError ?: IOException("APK download failed"))
    }

    private suspend fun downloadOnce(
        info: AppUpdateInfo,
        partialFile: File,
        apkFile: File,
        onProgress: (Progress) -> Unit,
    ): File {
        var resumeOffset = if (partialFile.isFile) partialFile.length() else 0L
        val connection = SecureUpdateHttpClient.openApk(info.apkUrl, rangeStart = resumeOffset)
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // 206：从断点继续，流从 resumeOffset 起始
                }
                HttpURLConnection.HTTP_OK -> {
                    resumeOffset = 0L
                    if (partialFile.isFile) partialFile.delete()
                }
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (sha256Of(partialFile) == info.sha256 &&
                        partialFile.renameTo(apkFile)
                    ) {
                        return apkFile
                    }
                    resumeOffset = 0L
                    partialFile.delete()
                    throw IOException("APK partial file is not resumable")
                }
                else -> throw IOException(
                    "Unexpected update server response: ${connection.responseCode}",
                )
            }
            val totalBytes = expectedTotalBytes(
                responseCode = connection.responseCode,
                resumeOffset = resumeOffset,
                contentLength = connection.contentLengthLong,
            )
            if (totalBytes > AppUpdateConfig.MAX_APK_BYTES) {
                throw ApkContentException("APK is too large", recoverableByFreshDownload = false)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            if (resumeOffset > 0L) digest.updateFromFile(partialFile)
            var downloadedBytes = resumeOffset
            var lastProgressAt = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partialFile, /* append = */ resumeOffset > 0L)
                    .buffered()
                    .use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloadedBytes += count
                            if (totalBytes > 0L && downloadedBytes > totalBytes) {
                                throw ApkContentException(
                                    "APK download exceeds expected size",
                                    recoverableByFreshDownload = false,
                                )
                            }
                            if (downloadedBytes > AppUpdateConfig.MAX_APK_BYTES) {
                                throw ApkContentException(
                                    "APK is too large",
                                    recoverableByFreshDownload = false,
                                )
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
            if (actualSha256 != info.sha256) {
                // 断点续传可能叠加了脏数据：允许清空重来一次；全新下载仍不匹配则判定源文件异常
                throw ApkContentException(
                    "APK SHA-256 does not match",
                    recoverableByFreshDownload = resumeOffset > 0L,
                )
            }
            onProgress(
                Progress(downloadedBytes, if (totalBytes > 0L) totalBytes else downloadedBytes),
            )
            if (!partialFile.renameTo(apkFile)) throw IOException("Cannot finalize APK download")
            return apkFile
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val UPDATE_CACHE_DIR = "app-updates"
        private const val PROGRESS_INTERVAL_NS = 250_000_000L
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

/** 下载内容本身不可用（体积超限、摘要不符），区别于可重试的网络/IO 失败。 */
internal class ApkContentException(
    message: String,
    val recoverableByFreshDownload: Boolean,
) : IOException(message)

internal fun expectedTotalBytes(
    responseCode: Int,
    resumeOffset: Long,
    contentLength: Long,
): Long = when (responseCode) {
    HttpURLConnection.HTTP_PARTIAL ->
        if (contentLength > 0L) resumeOffset + contentLength else -1L
    HttpURLConnection.HTTP_OK -> if (contentLength > 0L) contentLength else -1L
    else -> -1L
}

internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateFromFile(file)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun MessageDigest.updateFromFile(file: File) {
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            update(buffer, 0, count)
        }
    }
}
