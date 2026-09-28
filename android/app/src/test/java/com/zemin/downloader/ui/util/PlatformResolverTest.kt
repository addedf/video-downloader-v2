package com.zemin.downloader.ui.util

import com.zemin.downloader.impl.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformResolverTest {
    @Test
    fun resolvesDouyinShortLinkFromShareText() {
        val result = PlatformResolver.resolve("3.14 复制打开抖音 https://v.douyin.com/abc123/ 立即观看")
        assertEquals(DownloadType.DOU_YIN, result?.downloadType)
        assertEquals("https://v.douyin.com/abc123/", result?.normalizedInput)
    }

    @Test
    fun resolvesDouyinMarkdownLinkFromShareText() {
        val result = PlatformResolver.resolve(
            "看看【作品】[https://v.douyin.com/abc123/](https://v.douyin.com/abc123/)"
        )
        assertEquals(DownloadType.DOU_YIN, result?.downloadType)
        assertEquals("https://v.douyin.com/abc123/", result?.normalizedInput)
    }

    @Test
    fun resolvesXiaohongshuExploreLink() {
        val result = PlatformResolver.resolve("https://www.xiaohongshu.com/explore/123456")
        assertEquals(DownloadType.XIAO_HONG_SHU, result?.downloadType)
    }

    @Test
    fun resolvesXhsShortLinkAndTrimsChinesePunctuation() {
        val result = PlatformResolver.resolve("看看这个 https://xhslink.com/a1b2c3。）")
        assertEquals(DownloadType.XIAO_HONG_SHU, result?.downloadType)
        assertEquals("https://xhslink.com/a1b2c3", result?.url)
    }

    @Test
    fun resolvesXhslinkCnFromShareText() {
        val result = PlatformResolver.resolve(
            "装酷但可爱 https://xhslink.cn/o/1IDhqimIGg9 去【小红书】逛逛，这篇笔记超有料！",
        )
        assertEquals(DownloadType.XIAO_HONG_SHU, result?.downloadType)
        assertEquals("https://xhslink.cn/o/1IDhqimIGg9", result?.url)
    }

    @Test
    fun rejectsUnsupportedHost() {
        assertNull(PlatformResolver.resolve("https://example.com/video/123"))
    }
}
