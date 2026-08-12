package com.zemin.downloader.ui.util

import android.net.Uri
import com.zemin.downloader.impl.DownloadType

data class PlatformResolveResult(
    val downloadType: DownloadType,
    val normalizedInput: String,
    val url: String,
)

object PlatformResolver {
    private val supportedDouyinHosts = setOf(
        "douyin.com",
        "www.douyin.com",
        "v.douyin.com",
        "iesdouyin.com",
        "www.iesdouyin.com",
    )

    fun resolve(inputText: String): PlatformResolveResult? {
        val normalized = normalizeSharedText(inputText)
        if (normalized.isBlank()) return null

        val url = URL_PATTERN.find(normalized)?.value?.trimSupportedUrlEnd()
            ?: normalized.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null

        val host = Uri.parse(url).host?.lowercase() ?: return null
        if (!isDouyinHost(host)) return null

        return PlatformResolveResult(
            downloadType = DownloadType.DOU_YIN,
            normalizedInput = url,
            url = url,
        )
    }

    fun isSupported(inputText: String): Boolean = resolve(inputText) != null

    private fun isDouyinHost(host: String): Boolean {
        return host in supportedDouyinHosts || host.endsWith(".douyin.com")
    }
}

fun String.trimSupportedUrlEnd(): String = trimEnd('.', ',', ';', '，', '。', '；', ')', '）')
