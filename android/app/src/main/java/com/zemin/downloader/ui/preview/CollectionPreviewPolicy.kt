package com.zemin.downloader.ui.preview

import com.zemin.downloader.common.PyResolveResult

/** 游标续页只合并同一主页，按作品媒体标识去重；失败保留给调用方显示。 */
object CollectionPreviewPolicy {
    fun merge(previous: PyResolveResult?, next: PyResolveResult): PyResolveResult {
        if (previous == null || !next.ok) return next
        if (previous.sourceId != next.sourceId || next.collection == null) return next
        // Keep preview order while refreshing signed media URLs from overlapping pages.
        val resources = (previous.resources + next.resources).associateBy { it.id }.values.toList()
        val videos = resources.count { it.mediaType == "video" }
        val images = resources.count { it.mediaType == "image" }
        val liveVideos = resources.count { it.mediaType == "image" && it.liveVideo?.available == true }
        return next.copy(
            resources = resources,
            mediaType = if (videos > 0 && images > 0) "mixed" else if (videos > 0) "video" else "gallery",
            counts = next.counts.copy(videos = videos, images = images, liveVideos = liveVideos),
            capabilities = next.capabilities.copy(
                hasVideo = videos > 0, hasImages = images > 0, hasLiveVideo = liveVideos > 0,
            ),
        )
    }
}
