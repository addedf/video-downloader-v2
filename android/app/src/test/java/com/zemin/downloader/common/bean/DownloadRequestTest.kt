package com.zemin.downloader.common.bean

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                includeLiveVideo = true,
                resourceIds = listOf("image_1"),
            ),
            snapshot = DownloadSnapshot(
                sourceId = "123",
                title = "测试作品",
                author = "测试作者",
                workType = "live_photo",
                resources = listOf(
                    DownloadSnapshotResource(
                        id = "image_1",
                        index = 1,
                        type = "image",
                        title = "原图 01",
                        downloadUrls = listOf("https://cdn.test/image.jpg"),
                        formatHint = "jpg",
                        liveVideo = DownloadSnapshotLiveVideo(
                            available = true,
                            downloadUrls = listOf("https://cdn.test/live.mp4"),
                            formatHint = "mp4",
                        ),
                    )
                ),
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
        assertTrue(json.contains("\"resource_ids\":[\"image_1\"]"))
        assertTrue(json.contains("\"source_id\":\"123\""))
        assertTrue(json.contains("\"download_urls\":[\"https://cdn.test/image.jpg\"]"))
        assertTrue(json.contains("\"live_video\""))
        assertEquals(request, adapter.fromJson(json))
    }

    @Test
    fun emptySelectionRemainsExplicitWhileLegacySelectionOmitsIds() {
        val adapter = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(DownloadSelection::class.java)
        val emptySelection = DownloadSelection(resourceType = "image", resourceIds = emptyList())
        val emptyJson = adapter.toJson(emptySelection)

        assertTrue(emptyJson.contains("\"resource_ids\":[]"))
        assertEquals(emptyList<String>(), adapter.fromJson(emptyJson)?.resourceIds)
        assertFalse(adapter.toJson(DownloadSelection(resourceType = "image")).contains("resource_ids"))
    }
}
