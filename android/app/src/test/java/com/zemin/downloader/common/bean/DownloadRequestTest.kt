package com.zemin.downloader.common.bean

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRequestTest {
    @Test
    fun serializesV2SelectionWithSnakeCaseProtocolKeys() {
        val request = DownloadRequest(
            source = DownloadSource(
                url = "https://www.douyin.com/note/123",
                id = "123",
            ),
            expectedWorkType = "live_photo",
            selection = DownloadSelection(
                resourceType = "image",
                resourceIds = listOf("image_1"),
                includeLiveVideo = true,
            ),
        )
        val adapter = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(DownloadRequest::class.java)

        val json = adapter.toJson(request)

        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"expected_work_type\":\"live_photo\""))
        assertTrue(json.contains("\"resource_type\":\"image\""))
        assertTrue(json.contains("\"include_live_video\":true"))
        assertEquals(request, adapter.fromJson(json))
    }
}
