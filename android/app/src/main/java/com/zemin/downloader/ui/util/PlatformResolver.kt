package com.zemin.downloader.ui.util

import java.net.URI
import com.zemin.downloader.impl.DownloadType

data class PlatformResolveResult(
    val downloadType: DownloadType,
    val normalizedInput: String,
    val url: String,
)

object PlatformResolver {
    private val supportedDouyinHosts = setOf(
        "douyin.com", "www.douyin.com", "v.douyin.com",
        "iesdouyin.com", "www.iesdouyin.com",
    )
    private val supportedXhsHosts = setOf(
        "xiaohongshu.com", "www.xiaohongshu.com",
        "xhslink.com", "www.xhslink.com", "xhslink.cn", "www.xhslink.cn",
        "xhs.cn", "www.xhs.cn",
    )

    private val supportedXHosts = setOf(
        "x.com",
        "www.x.com",
        "mobile.x.com",
        "twitter.com",
        "www.twitter.com",
        "mobile.twitter.com",
        "vxtwitter.com",
        "www.vxtwitter.com",
        "fixupx.com",
        "www.fixupx.com",
        "t.co",
        "www.t.co",
    )

    fun resolve(inputText: String): PlatformResolveResult? {
        val normalized = normalizeSharedText(inputText)
        if (normalized.isBlank()) return null

        val url = URL_PATTERN.find(normalized)?.value?.trimSupportedUrlEnd()
            ?: normalized.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        val type = when {
            isDouyinHost(host) -> DownloadType.DOU_YIN
            isXhsHost(host) -> DownloadType.XIAO_HONG_SHU
            isXHost(host) -> DownloadType.TWITTER
            else -> return null
        }
        return PlatformResolveResult(type, url, url)
    }

    fun isSupported(inputText: String): Boolean = resolve(inputText) != null

    private fun isDouyinHost(host: String) =
        host in supportedDouyinHosts || host.endsWith(".douyin.com") || host.endsWith(".iesdouyin.com")

    private fun isXhsHost(host: String) =
        host in supportedXhsHosts || host.endsWith(".xiaohongshu.com") || host.endsWith(".xhslink.com") ||
            host.endsWith(".xhslink.cn")

    private fun isXHost(host: String) =
        host in supportedXHosts || host.endsWith(".x.com") || host.endsWith(".twitter.com")
}

fun String.trimSupportedUrlEnd(): String = trimEnd('.', ',', ';', '，', '。', '；', ')', '）', ']')
