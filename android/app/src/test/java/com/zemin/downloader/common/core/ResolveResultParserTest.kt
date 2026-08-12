package com.zemin.downloader.common.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveResultParserTest {
    @Test
    fun parsesV2LivePhotoAndUsesActualResourceCounts() {
        val result = ResolveResultParser.parse(v2Json())

        assertTrue(result.ok)
        assertEquals(2, result.schemaVersion)
        assertEquals("live_photo", result.mediaType)
        assertEquals(4, result.resources.size)
        assertEquals(2, result.counts.images)
        assertEquals(1, result.counts.liveVideos)
        assertTrue(result.capabilities.hasLiveVideo)
        assertEquals("https://cdn.test/cover.jpg", result.coverUrl)
    }

    @Test
    fun parsesLegacyResponseForXhsCompatibility() {
        val result = ResolveResultParser.parse(
            """
            {
              "ok": true,
              "message": "解析成功",
              "source_url": "https://www.xiaohongshu.com/explore/1",
              "source_id": "1",
              "title": "旧协议",
              "author": "作者",
              "media_type": "image",
              "resources": [
                {"title": "图片 1", "media_type": "image", "download_urls": ["https://cdn.test/1.jpg"]}
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.ok)
        assertEquals(1, result.schemaVersion)
        assertEquals(1, result.resources.size)
        assertNull(result.error)
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        val result = ResolveResultParser.parse("""{"schema_version":3,"ok":true}""")

        assertFalse(result.ok)
        assertTrue(result.message.contains("版本"))
    }

    @Test
    fun rejectsSuccessfulV2ResponseWithoutResources() {
        val result = ResolveResultParser.parse(
            """
            {
              "schema_version": 2,
              "ok": true,
              "message": "解析成功",
              "source": {"platform": "douyin", "input_url": "u", "resolved_url": "u", "id": "1"},
              "work": {"type": "gallery", "title": "空作品", "resources": {}}
            }
            """.trimIndent()
        )

        assertFalse(result.ok)
        assertEquals("解析结果中没有可保存的资源", result.error)
    }

    private fun v2Json() = """
        {
          "schema_version": 2,
          "ok": true,
          "message": "解析成功",
          "source": {
            "platform": "douyin",
            "input_url": "https://v.douyin.com/test/",
            "resolved_url": "https://www.douyin.com/note/123",
            "id": "123"
          },
          "work": {
            "type": "live_photo",
            "title": "Live 测试",
            "author": {"id": "author-1", "name": "作者"},
            "capabilities": {
              "has_video": false,
              "has_images": true,
              "has_cover": true,
              "has_audio": true,
              "has_live_video": true
            },
            "counts": {"images": 99, "covers": 99, "audios": 99, "live_videos": 99},
            "resources": {
              "videos": [],
              "images": [
                {
                  "id": "image_1", "index": 1, "type": "image", "title": "原图 01",
                  "preview_urls": ["https://cdn.test/1.webp"],
                  "download_urls": ["https://cdn.test/1.jpg"],
                  "live_video": {"available": true, "download_urls": ["https://cdn.test/1.mp4"]}
                },
                {
                  "id": "image_2", "index": 2, "type": "image", "title": "原图 02",
                  "download_urls": ["https://cdn.test/2.jpg"],
                  "live_video": {"available": false, "download_urls": []}
                }
              ],
              "covers": [
                {"id": "cover_1", "index": 1, "type": "cover", "title": "封面", "preview_urls": ["https://cdn.test/cover.jpg"], "download_urls": ["https://cdn.test/cover.jpg"]}
              ],
              "audios": [
                {"id": "audio_1", "index": 1, "type": "audio", "title": "音频", "download_urls": ["https://cdn.test/audio.m4a"]}
              ]
            }
          }
        }
    """.trimIndent()
}
