package com.zemin.downloader.ui.preview

import com.zemin.downloader.impl.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PreviewRequestPolicyTest {
    @Test
    fun xiaohongshuPreviewUsesXiaohongshuReferer() {
        val headers = PreviewRequestPolicy.headersFor(DownloadType.XIAO_HONG_SHU)

        assertEquals("https://www.xiaohongshu.com/", headers["Referer"])
        assertNotEquals("https://www.douyin.com/", headers["Referer"])
    }

    @Test
    fun douyinPreviewKeepsDouyinReferer() {
        val headers = PreviewRequestPolicy.headersFor(DownloadType.DOU_YIN)

        assertEquals("https://www.douyin.com/", headers["Referer"])
    }
}
