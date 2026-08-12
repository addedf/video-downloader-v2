package com.zemin.downloader.ui.preview

import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolveCapabilities
import com.zemin.downloader.common.ResolveCounts
import com.zemin.downloader.common.ResolvedResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewUiPolicyTest {
    @Test
    fun videoUsesSingleDynamicTabGroup() {
        val preview = preview(
            mediaType = "video",
            resources = listOf(resource("video"), resource("cover"), resource("audio")),
        )

        val state = PreviewUiPolicy.stateFor(preview)

        assertEquals(listOf(ResourceTab.VIDEO, ResourceTab.COVER, ResourceTab.AUDIO), state.tabs)
        assertEquals(ResourceTab.VIDEO, state.defaultTab)
    }

    @Test
    fun galleryDefaultsToImagesWithoutLiveOption() {
        val preview = preview(
            mediaType = "gallery",
            resources = listOf(resource("image"), resource("cover"), resource("audio")),
        )

        val state = PreviewUiPolicy.stateFor(preview)

        assertEquals(listOf(ResourceTab.IMAGE, ResourceTab.COVER, ResourceTab.AUDIO), state.tabs)
        assertFalse(PreviewUiPolicy.shouldShowLiveOption(preview, ResourceTab.IMAGE))
    }

    @Test
    fun liveOptionOnlyAppearsOnImageTabWhenMotionAssetsExist() {
        val preview = preview(
            mediaType = "live_photo",
            resources = listOf(resource("image"), resource("cover"), resource("audio")),
            capabilities = ResolveCapabilities(hasImages = true, hasLiveVideo = true),
            counts = ResolveCounts(images = 1, covers = 1, audios = 1, liveVideos = 1),
        )

        assertTrue(PreviewUiPolicy.shouldShowLiveOption(preview, ResourceTab.IMAGE))
        assertFalse(PreviewUiPolicy.shouldShowLiveOption(preview, ResourceTab.COVER))
        assertFalse(PreviewUiPolicy.shouldShowLiveOption(preview, ResourceTab.AUDIO))
    }

    @Test
    fun malformedEmptyResponseProducesNoTabs() {
        val state = PreviewUiPolicy.stateFor(preview(mediaType = "gallery"))

        assertTrue(state.tabs.isEmpty())
    }

    private fun preview(
        mediaType: String,
        resources: List<ResolvedResource> = emptyList(),
        capabilities: ResolveCapabilities = ResolveCapabilities(),
        counts: ResolveCounts = ResolveCounts(),
    ) = PyResolveResult(
        ok = true,
        message = "ok",
        error = null,
        sourceUrl = "https://www.douyin.com/note/1",
        sourceId = "1",
        title = "title",
        author = "author",
        coverUrl = null,
        mediaType = mediaType,
        resources = resources,
        schemaVersion = 2,
        capabilities = capabilities,
        counts = counts,
    )

    private fun resource(type: String) = ResolvedResource(
        id = "${type}_1",
        index = 1,
        title = type,
        mediaType = type,
        downloadUrls = listOf("https://cdn.test/$type"),
    )
}
