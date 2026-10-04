package com.zemin.downloader.ui.preview

import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.bean.PyCollectionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPreviewPolicyTest {
    private fun resource(id: String, type: String) = ResolvedResource(
        id = id, mediaType = type, downloadUrls = listOf("https://pbs.twimg.com/$id"),
    )

    private fun preview(vararg resources: ResolvedResource) = PyResolveResult(
        ok = true, message = "尚未取全", sourceId = "profile-nasa", resources = resources.toList(),
        collection = PyCollectionResponse(nextCursor = "next"),
    )

    @Test
    fun profileOffersAllAndBothMediaTypes() {
        val preview = preview(resource("a", "image"), resource("b", "video"))
        val state = PreviewUiPolicy.stateFor(preview)
        assertEquals(listOf(ResourceTab.ALL, ResourceTab.VIDEO, ResourceTab.IMAGE), state.tabs)
        assertEquals(2, PreviewUiPolicy.resourcesFor(preview, ResourceTab.ALL).size)
        assertFalse(PreviewUiPolicy.shouldShowLiveOption(preview, ResourceTab.ALL))
    }

    @Test
    fun mixedPostOffersAllWithoutCollection() {
        val preview = preview(resource("a", "image"), resource("b", "video"))
            .copy(collection = null, mediaType = "mixed")
        assertEquals(ResourceTab.ALL, PreviewUiPolicy.stateFor(preview).defaultTab)
    }

    @Test
    fun continuationDeduplicatesAndRecomputesCounts() {
        val first = preview(resource("a", "image"))
        val next = preview(resource("a", "image"), resource("b", "video"))
        val merged = CollectionPreviewPolicy.merge(first, next)
        assertEquals(2, merged.resources.size)
        assertEquals(1, merged.counts.videos)
        assertEquals(1, merged.counts.images)
        assertTrue(merged.capabilities.hasImages && merged.capabilities.hasVideo)
    }

    @Test
    fun emptyLastPagePreservesMediaAndEndsContinuation() {
        val merged = CollectionPreviewPolicy.merge(preview(resource("a", "image")),
            preview().copy(collection = PyCollectionResponse(complete = true)))
        assertEquals(1, merged.resources.size)
        assertTrue(merged.collection?.complete == true)
        assertEquals(null, merged.collection?.nextCursor)
    }

    @Test
    fun repeatedWorkRefreshesExpiredUrlsWithoutChangingOrder() {
        val first = preview(resource("a", "image"), resource("b", "video"))
        val refreshed = resource("a", "image").copy(downloadUrls = listOf("https://pbs.twimg.com/a?fresh=1"))
        val merged = CollectionPreviewPolicy.merge(first, preview(refreshed))
        assertEquals(listOf("a", "b"), merged.resources.map { it.id })
        assertEquals(refreshed.downloadUrls, merged.resources.first().downloadUrls)
    }

    @Test
    fun differentWorksUsingSamePlayEndpointRemainDistinct() {
        val first = preview(resource("123-video_1", "video").copy(
            downloadUrls = listOf("https://www.douyin.com/aweme/v1/play/?video_id=first"),
        ))
        val next = preview(resource("456-video_1", "video").copy(
            downloadUrls = listOf("https://www.douyin.com/aweme/v1/play/?video_id=second"),
        ))
        assertEquals(2, CollectionPreviewPolicy.merge(first, next).resources.size)
    }

    @Test
    fun profileLiveMediaCanBeIncludedOnAllTab() {
        val live = preview(resource("456-image_1", "image").copy(
            liveVideo = com.zemin.downloader.common.ResolvedLiveVideo(available = true),
        ))
        val merged = CollectionPreviewPolicy.merge(live, preview())
        assertEquals(1, merged.counts.liveVideos)
        assertTrue(PreviewUiPolicy.shouldShowLiveOption(merged, ResourceTab.ALL))
    }

    @Test
    fun errorsAndOtherProfilesAreNotMerged() {
        val first = preview(resource("a", "image"))
        val error = preview().copy(ok = false)
        assertEquals(error, CollectionPreviewPolicy.merge(first, error))
        val other = preview(resource("b", "video")).copy(sourceId = "profile-other")
        assertEquals(other, CollectionPreviewPolicy.merge(first, other))
    }
}
