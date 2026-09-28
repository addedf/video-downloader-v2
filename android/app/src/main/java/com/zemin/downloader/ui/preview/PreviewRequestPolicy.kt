package com.zemin.downloader.ui.preview

import com.zemin.downloader.impl.DownloadType

internal object PreviewRequestPolicy {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val DOUYIN_REFERER = "https://www.douyin.com/"
    private const val XIAOHONGSHU_REFERER = "https://www.xiaohongshu.com/"

    fun headersFor(downloadType: DownloadType): Map<String, String> = linkedMapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to when (downloadType) {
            DownloadType.DOU_YIN -> DOUYIN_REFERER
            DownloadType.XIAO_HONG_SHU -> XIAOHONGSHU_REFERER
        },
    )
}
